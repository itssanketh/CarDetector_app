package com.example.cardetector

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException

// Free keys get ~20 requests/day per model, so fall through to the next model when one is out of quota or busy.
val MODELS = listOf("gemini-flash-latest", "gemini-3.5-flash", "gemini-flash-lite-latest")

val PROMPT = """
Identify the main car in this image. Reply ONLY with JSON:
{"is_car": true/false, "make": "", "model": "", "year_range": "", "colour": "", "colour_hex": "#RRGGBB", "confidence": 0-100}
If no car is visible, set is_car to false and leave the rest empty.
""".trimIndent()

// Stages reported to the UI while a photo is processed.
const val PREPARING = 0
const val UPLOADING = 1
const val RECOGNISING = 2
const val READING = 3

fun identifyCar(original: Bitmap, onStage: (stage: Int, note: String?) -> Unit = { _, _ -> }): JSONObject {
    onStage(PREPARING, null)
    // Shrink big photos so upload is fast
    val s = 1024f / maxOf(original.width, original.height)
    val bmp = if (s < 1) Bitmap.createScaledBitmap(original, (original.width * s).toInt(), (original.height * s).toInt(), true) else original
    val jpeg = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()

    val body = JSONObject()
        .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
            .put(JSONObject().put("text", PROMPT))
            .put(JSONObject().put("inline_data", JSONObject()
                .put("mime_type", "image/jpeg")
                .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP)))))))
        .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))

    val payload = body.toString().toByteArray()
    lateinit var conn: HttpURLConnection
    for ((i, model) in MODELS.withIndex()) {
        onStage(UPLOADING, if (i > 0) "Switched to backup model" else null)
        conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("x-goog-api-key", BuildConfig.GEMINI_API_KEY)
        conn.setFixedLengthStreamingMode(payload.size) // actually sends while writing, so "Uploading" is real
        conn.outputStream.use { it.write(payload) }
        onStage(RECOGNISING, model)
        if (conn.responseCode != 429 && conn.responseCode != 503) break
    }

    if (conn.responseCode != 200)
        error("HTTP ${conn.responseCode}: ${conn.errorStream?.bufferedReader()?.readText()}")

    onStage(READING, null)
    val text = JSONObject(conn.inputStream.bufferedReader().readText())
        .getJSONArray("candidates").getJSONObject(0)
        .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
    return JSONObject(text)
}

fun friendlyError(e: Exception): String = when {
    e is UnknownHostException -> "No internet connection."
    e.message?.startsWith("HTTP 429") == true -> "Today's free AI limit is used up. Try again tomorrow."
    e.message?.startsWith("HTTP 503") == true -> "The AI service is busy. Try again."
    else -> "Something went wrong. Try again."
}

// Printed spec sheet: warm paper + black ink by day, ink + paper by night, one signal-orange accent.
private val LightColors = lightColorScheme(
    primary = SIGNAL, onPrimary = Color.Black,
    background = Color(0xFFF1EDE4), onBackground = Color(0xFF111111),
    surface = Color(0xFFF1EDE4), onSurface = Color(0xFF111111),
    onSurfaceVariant = Color(0xFF6B665C), outline = Color(0xFFD6D0C3),
)
private val DarkColors = darkColorScheme(
    primary = SIGNAL, onPrimary = Color.Black,
    background = Color(0xFF121110), onBackground = Color(0xFFF1EDE4),
    surface = Color(0xFF121110), onSurface = Color(0xFFF1EDE4),
    onSurfaceVariant = Color(0xFF8F897D), outline = Color(0xFF2E2C29),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val system = isSystemInDarkTheme()
            // ponytail: choice resets to the phone's theme on app restart; save to SharedPreferences if that matters
            var dark by rememberSaveable { mutableStateOf(system) }
            LaunchedEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            MaterialTheme(colorScheme = if (dark) DarkColors else LightColors) {
                Surface { CarScreen(dark) { dark = !dark } }
            }
        }
    }

    @Composable
    fun CarScreen(dark: Boolean, onToggleTheme: () -> Unit) {
        var photo by remember { mutableStateOf<Bitmap?>(null) }
        var car by remember { mutableStateOf<JSONObject?>(null) }
        var message by remember { mutableStateOf<String?>(null) }
        var loading by remember { mutableStateOf(false) }
        var stage by remember { mutableIntStateOf(PREPARING) }
        var stageNote by remember { mutableStateOf<String?>(null) }
        var runs by rememberSaveable { mutableIntStateOf(0) }
        val stageStarts = remember { mutableStateListOf<Long?>(null, null, null, null, null) }
        val scope = rememberCoroutineScope()

        fun analyse(b: Bitmap) {
            photo = b; car = null; message = null; loading = true; runs++
            stage = PREPARING; stageNote = null
            for (i in stageStarts.indices) stageStarts[i] = null
            scope.launch {
                try {
                    val r = withContext(Dispatchers.IO) {
                        identifyCar(b) { st, note ->
                            scope.launch { // back on the main thread
                                if (stageStarts[st] == null) stageStarts[st] = SystemClock.elapsedRealtime()
                                stage = st
                                stageNote = when {
                                    note == null -> null
                                    st == RECOGNISING -> "model $note"
                                    else -> note.lowercase()
                                }
                            }
                        }
                    }
                    if (r.optBoolean("is_car")) car = r else message = "No car found in this photo."
                } catch (e: Exception) {
                    Log.e("CarDetector", "identify failed", e)
                    message = friendlyError(e)
                } finally {
                    loading = false
                }
            }
        }

        val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) scope.launch {
                withContext(Dispatchers.IO) { decodeScaled(uri) }?.let { analyse(it) }
            }
        }
        // ponytail: low-res camera preview; switch to TakePicture + FileProvider for full-res
        val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { b ->
            b?.let { analyse(it) }
        }

        val ink = MaterialTheme.colorScheme.onBackground
        val muted = MaterialTheme.colorScheme.onSurfaceVariant

        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            // Masthead
            Row(verticalAlignment = Alignment.Bottom) {
                Text("CAR\nDETECTOR", fontSize = 40.sp, lineHeight = 38.sp, fontWeight = FontWeight.Black,
                    letterSpacing = (-1.5).sp, modifier = Modifier.weight(1f))
                TextButtonBox(if (dark) "LIGHT" else "DARK", filled = false,
                    desc = if (dark) "Switch to light theme" else "Switch to dark theme", onClick = onToggleTheme)
            }
            Spacer(Modifier.height(10.dp))
            Rule(ink, 2.dp)
            Row(Modifier.padding(vertical = 6.dp)) {
                Label("VISUAL ID · GEMINI", Modifier.weight(1f))
                Label("SHEET NO. %03d".format(runs.coerceAtLeast(1)))
            }
            Rule(ink, 1.dp)
            Spacer(Modifier.height(16.dp))

            // Photo plate
            Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).border(1.5.dp, ink)) {
                val p = photo
                if (p != null) {
                    val dim by animateFloatAsState(if (loading) 0.55f else 1f, tween(300), label = "dim")
                    Image(p.asImageBitmap(), "Selected car photo", Modifier.fillMaxSize().padding(1.5.dp).graphicsLayer { alpha = dim },
                        contentScale = ContentScale.Crop)
                } else {
                    Hatch(Modifier.fillMaxSize())
                    Column(Modifier.align(Alignment.Center).background(MaterialTheme.colorScheme.background).padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("NO PHOTO", fontFamily = MONO, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text("shoot or pick a car", fontFamily = MONO, fontSize = 12.sp, color = muted)
                    }
                }
                Label("FIG. 1", Modifier.align(Alignment.TopStart).background(ink).padding(horizontal = 6.dp, vertical = 2.dp),
                    color = MaterialTheme.colorScheme.background)
                if (loading) WorkStripe(Modifier.align(Alignment.BottomStart))
            }
            Spacer(Modifier.height(12.dp))

            // Actions
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButtonBox("● CAMERA", filled = true, desc = "Take photo", enabled = !loading, modifier = Modifier.weight(1f)) {
                    camera.launch(null)
                }
                TextButtonBox("▤ GALLERY", filled = false, desc = "Choose from gallery", enabled = !loading, modifier = Modifier.weight(1f)) {
                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            }
            Spacer(Modifier.height(28.dp))

            AnimatedContent(
                targetState = Triple(car, message, loading), label = "result",
                transitionSpec = { fadeIn(tween(250, delayMillis = 80)) togetherWith fadeOut(tween(120)) using SizeTransform(clip = false) },
            ) { (c, msg, busy) ->
                Column(Modifier.fillMaxWidth()) {
                    when {
                        c != null -> CarResult(c)
                        msg != null -> Reveal(0) {
                            Row(Modifier.fillMaxWidth().border(1.5.dp, SIGNAL).padding(16.dp)) {
                                Text("!", fontFamily = MONO, fontWeight = FontWeight.Black, color = SIGNAL, fontSize = 17.sp)
                                Spacer(Modifier.width(12.dp))
                                Text(msg, fontSize = 16.sp, lineHeight = 21.sp)
                            }
                        }
                        busy -> {
                            Label("PROCESSING")
                            Spacer(Modifier.height(10.dp))
                            StageLog(stage, stageStarts, stageNote)
                        }
                        else -> Text("Results print here.", fontFamily = MONO, fontSize = 13.sp, color = muted)
                    }
                }
            }
        }
    }

    /** Decodes a picked photo at roughly screen size instead of full 12 MP (saves memory and time). */
    private fun decodeScaled(uri: android.net.Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    @Composable
    fun Rule(color: Color, thickness: androidx.compose.ui.unit.Dp) =
        Box(Modifier.fillMaxWidth().height(thickness).background(color))

    @Composable
    fun Label(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) =
        Text(text, modifier, color = color, fontFamily = MONO, fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Medium)

    /** Square-cornered button: orange fill or ink outline. Sinks a little on touch-down. */
    @Composable
    fun TextButtonBox(
        text: String, filled: Boolean, desc: String, modifier: Modifier = Modifier,
        enabled: Boolean = true, onClick: () -> Unit,
    ) {
        val ink = MaterialTheme.colorScheme.onBackground
        val source = remember { MutableInteractionSource() }
        val pressed by source.collectIsPressedAsState()
        val shift by animateFloatAsState(if (pressed) 1f else 0f, spring(dampingRatio = 1f, stiffness = 800f), label = "press")
        val alpha by animateFloatAsState(if (enabled) 1f else 0.35f, tween(200), label = "enabled")
        Box(
            modifier
                .semantics { contentDescription = desc }
                .graphicsLayer { translationY = shift * 2.dp.toPx(); this.alpha = alpha }
                .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
                .then(if (filled) Modifier.background(SIGNAL) else Modifier.border(1.5.dp, ink))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text, fontFamily = MONO, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                color = if (filled) Color.Black else ink)
        }
    }

    @Composable
    fun CarResult(c: JSONObject) {
        val ink = MaterialTheme.colorScheme.onBackground
        val confidence = c.optInt("confidence").coerceIn(0, 100)
        val swatch = runCatching { Color(android.graphics.Color.parseColor(c.optString("colour_hex"))) }.getOrNull()

        Reveal(0) { Label("IDENTIFIED · " + c.optString("make").uppercase(), color = SIGNAL) }
        Reveal(60) {
            Text(c.optString("model"), fontSize = 44.sp, lineHeight = 44.sp, fontWeight = FontWeight.Black, letterSpacing = (-1.5).sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
        }
        Rule(ink, 2.dp)
        SpecRow("MAKE", 120) { SpecValue(c.optString("make")) }
        SpecRow("YEAR", 180) { SpecValue(c.optString("year_range")) }
        SpecRow("COLOUR", 240) {
            if (swatch != null) {
                Box(Modifier.size(16.dp).background(swatch).border(1.dp, ink))
                Spacer(Modifier.width(8.dp))
            }
            SpecValue(c.optString("colour").replaceFirstChar { it.uppercase() })
        }
        SpecRow("MATCH", 300) { SpecValue("$confidence%") }
        Reveal(360) { BlockMeter(confidence / 100f, Modifier.padding(top = 12.dp)) }
    }

    /** One line of the spec table: mono label left, value right, hairline below. */
    @Composable
    fun SpecRow(label: String, delayMs: Int, value: @Composable RowScope.() -> Unit) {
        Reveal(delayMs) {
            Column {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Label(label, Modifier.weight(1f))
                    value()
                }
                Rule(MaterialTheme.colorScheme.outline, 1.dp)
            }
        }
    }

    @Composable
    fun SpecValue(text: String) =
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

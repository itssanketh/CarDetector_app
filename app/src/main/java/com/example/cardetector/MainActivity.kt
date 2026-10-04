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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

private val LightColors = lightColorScheme(
    primary = Color(0xFF111111), onPrimary = Color.White,
    background = Color(0xFFF5F5F3), onBackground = Color(0xFF111111),
    surface = Color(0xFFF5F5F3), onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFE8E8E5), onSurfaceVariant = Color(0xFF6E6E6E),
    outline = Color(0xFFD4D4D0),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFF2F2F2), onPrimary = Color(0xFF111111),
    background = Color(0xFF070707), onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF070707), onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF1A1A1C), onSurfaceVariant = Color(0xFF8E8E93),
    outline = Color(0xFF2C2F36),
)

val LABELS = listOf("Thinking", "Searching", "Analyzing", "Composing")

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
        val stageStarts = remember { mutableStateListOf<Long?>(null, null, null, null, null) }
        val scope = rememberCoroutineScope()

        fun analyse(b: Bitmap) {
            photo = b; car = null; message = null; loading = true
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
                                    st == RECOGNISING -> "Model · $note"
                                    else -> note
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

        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        val corner = 28.dp

        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(28.dp))
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("CAR DETECTOR", style = MaterialTheme.typography.labelMedium, letterSpacing = 2.sp, color = muted)
                    Spacer(Modifier.height(6.dp))
                    Text("Identify any car.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                }
                ThemeToggle(dark, onToggleTheme)
            }
            Spacer(Modifier.height(24.dp))

            // Card is inset 3dp so the rainbow ring can show around it while the AI works.
            Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f)) {
                androidx.compose.animation.AnimatedVisibility(
                    loading, Modifier.matchParentSize(), enter = fadeIn(tween(400)), exit = fadeOut(tween(500))
                ) { RainbowGlow(corner + 3.dp, Modifier.fillMaxSize()) }

                Box(
                    Modifier.matchParentSize().padding(3.dp)
                        .clip(RoundedCornerShape(corner))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    val p = photo
                    if (p != null) {
                        Image(p.asImageBitmap(), "Selected car photo", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("No photo yet", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text("Take one or pick from your gallery", style = MaterialTheme.typography.bodyMedium, color = muted)
                        }
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        loading, Modifier.matchParentSize(), enter = fadeIn(tween(300)), exit = fadeOut(tween(400))
                    ) { ThinkingOverlay() }
                }
            }

            Spacer(Modifier.height(28.dp))

            Crossfade(targetState = Triple(car, message, loading), label = "result", modifier = Modifier.weight(1f)) { (c, msg, busy) ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    when {
                        c != null -> CarResult(c)
                        msg != null -> Reveal(0) { Text(msg, style = MaterialTheme.typography.bodyLarge, color = muted) }
                        busy -> ProcessingSteps(stage, stageStarts, stageNote)
                        else -> Text("Results appear here.", style = MaterialTheme.typography.bodyLarge, color = muted)
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { camera.launch(null) }, enabled = !loading,
                    modifier = Modifier.weight(1f).height(56.dp), shape = CircleShape
                ) { Text("Camera", style = MaterialTheme.typography.titleSmall) }
                Button(
                    onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = !loading, modifier = Modifier.weight(1f).height(56.dp), shape = CircleShape
                ) { Text("Gallery", style = MaterialTheme.typography.titleSmall) }
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

    /** Dimmed photo with the dotted orb and a cycling "Thinking / Searching / …" label. */
    @Composable
    fun ThinkingOverlay() {
        var i by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) { while (true) { delay(1150); i = (i + 1) % LABELS.size } }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ThinkingOrb(program = i, color = Color.White, modifier = Modifier.size(132.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AnimatedContent(
                        LABELS[i], label = "label",
                        transitionSpec = {
                            (fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 3 }) togetherWith
                                (fadeOut(tween(200)) + slideOutVertically(tween(200)) { -it / 3 })
                        }
                    ) { Text(it, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.titleSmall) }
                    Spacer(Modifier.width(6.dp))
                    BlinkingDots(Color.White.copy(alpha = 0.85f), Modifier.size(width = 16.dp, height = 4.dp))
                }
            }
        }
    }

    /** Circle, half filled: the classic light/dark switch. */
    @Composable
    fun ThemeToggle(dark: Boolean, onClick: () -> Unit) {
        val fg = MaterialTheme.colorScheme.onSurface
        val turn by animateFloatAsState(if (dark) 180f else 0f, tween(500, easing = EaseInOutCubic), label = "turn")
        Box(
            Modifier.size(44.dp).clip(CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                .semantics { contentDescription = if (dark) "Switch to light theme" else "Switch to dark theme" }
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(18.dp)) {
                val r = size.minDimension / 2
                drawCircle(fg, r - 1.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                drawArc(fg, 90f + turn, 180f, true, topLeft = Offset(1.dp.toPx(), 1.dp.toPx()),
                    size = Size(size.width - 2.dp.toPx(), size.height - 2.dp.toPx()))
            }
        }
    }

    @Composable
    fun CarResult(c: JSONObject) {
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        val confidence = c.optInt("confidence").coerceIn(0, 100)
        val swatch = runCatching { Color(android.graphics.Color.parseColor(c.optString("colour_hex"))) }.getOrNull()
        val bar = remember { Animatable(0f) }
        LaunchedEffect(Unit) { delay(300); bar.animateTo(confidence / 100f, tween(900, easing = EaseOutQuint)) }

        Reveal(0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(DONE_GREEN))
                Spacer(Modifier.width(8.dp))
                Text(c.optString("make").uppercase(), style = MaterialTheme.typography.labelLarge, letterSpacing = 1.5.sp, color = muted)
            }
        }
        Reveal(80) { Text(c.optString("model"), fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.height(24.dp))

        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) { Reveal(160) { Stat("Year", c.optString("year_range")) } }
            Box(Modifier.weight(1f)) {
                Reveal(220) {
                    Column {
                        Text("Colour", style = MaterialTheme.typography.labelMedium, color = muted)
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (swatch != null) {
                                Box(Modifier.size(12.dp).clip(CircleShape).background(swatch)
                                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(c.optString("colour").replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
            Box(Modifier.weight(1f)) { Reveal(280) { Stat("Confidence", "$confidence%") } }
        }
        Spacer(Modifier.height(20.dp))
        // Confidence bar that fills up, in the theme colour.
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.fillMaxWidth(bar.value).fillMaxHeight().clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        }
    }

    @Composable
    fun Stat(label: String, value: String) {
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

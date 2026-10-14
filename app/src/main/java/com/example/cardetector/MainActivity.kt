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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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

// iOS system palette: grouped background, white/elevated cards, system blue accent.
private val LightColors = lightColorScheme(
    primary = Color(0xFF007AFF), onPrimary = Color.White,
    secondaryContainer = Color(0xFFE5F0FF), onSecondaryContainer = Color(0xFF007AFF),
    background = Color(0xFFF2F2F7), onBackground = Color.Black,
    surface = Color(0xFFF2F2F7), onSurface = Color.Black,
    surfaceVariant = Color.White, onSurfaceVariant = Color(0xFF6C6C70),
    outline = Color(0xFFD1D1D6),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF0A84FF), onPrimary = Color.White,
    secondaryContainer = Color(0xFF0C2340), onSecondaryContainer = Color(0xFF4DA3FF),
    background = Color.Black, onBackground = Color.White,
    surface = Color.Black, onSurface = Color.White,
    surfaceVariant = Color(0xFF1C1C1E), onSurfaceVariant = Color(0xFF8E8E93),
    outline = Color(0xFF38383A),
)

// Viewfinder stays dark in both themes, like a camera.
val VIEWFINDER = Color(0xFF0E0E10)

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

        val corner = 36.dp

        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp)) {
            Spacer(Modifier.height(6.dp))

            // Viewfinder: takes all spare height, shrinks when results slide in below.
            // Card is inset 3dp so the rainbow ring can show around it while the AI works.
            Box(Modifier.fillMaxWidth().weight(1f)) {
                androidx.compose.animation.AnimatedVisibility(
                    loading, Modifier.matchParentSize(), enter = fadeIn(tween(400)), exit = fadeOut(tween(500))
                ) { ScanGlow(corner + 3.dp, Modifier.fillMaxSize()) }

                Box(
                    Modifier.matchParentSize().padding(3.dp)
                        .clip(RoundedCornerShape(corner))
                        .background(VIEWFINDER),
                    contentAlignment = Alignment.Center
                ) {
                    val p = photo
                    if (p != null) {
                        Image(p.asImageBitmap(), "Selected car photo", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        FrameBrackets(Modifier.fillMaxSize().padding(28.dp))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Point at any car", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
                            Spacer(Modifier.height(6.dp))
                            Text("Tap the shutter, or pick a photo.", color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp, textAlign = TextAlign.Center)
                        }
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        loading, Modifier.matchParentSize(), enter = fadeIn(tween(300)), exit = fadeOut(tween(400))
                    ) { ScanOverlay(stage, Modifier.fillMaxSize()) }
                    TitleChip(Modifier.align(Alignment.TopCenter).padding(top = 18.dp))
                }
            }

            AnimatedContent(
                targetState = Triple(car, message, loading), label = "result",
                transitionSpec = { fadeIn(tween(300, delayMillis = 100)) togetherWith fadeOut(tween(150)) using SizeTransform(clip = false) },
            ) { (c, msg, busy) ->
                Column(Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    when {
                        c != null -> CarResult(c)
                        msg != null -> Reveal(0) { Panel { Text(msg, fontSize = 17.sp, lineHeight = 22.sp) } }
                        busy -> Panel { StageProgress(stage, stageStarts, stageNote) }
                    }
                }
            }

            // Camera-style control bar: gallery · shutter · theme.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
            ) {
                GalleryButton(photo, enabled = !loading) {
                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                Shutter(enabled = !loading) { camera.launch(null) }
                ThemeToggle(dark, onToggleTheme)
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

    /** Circle, half filled: the classic light/dark switch. */
    @Composable
    fun ThemeToggle(dark: Boolean, onClick: () -> Unit) {
        val fg = MaterialTheme.colorScheme.onSurface
        val turn by animateFloatAsState(if (dark) 180f else 0f, spring(dampingRatio = 0.8f, stiffness = 300f), label = "turn")
        Box(
            Modifier.size(54.dp)
                .semantics { contentDescription = if (dark) "Switch to light theme" else "Switch to dark theme" }
                .pressable(onClick = onClick)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(20.dp)) {
                val r = size.minDimension / 2
                drawCircle(fg, r - 1.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                drawArc(fg, 90f + turn, 180f, true, topLeft = Offset(1.dp.toPx(), 1.dp.toPx()),
                    size = Size(size.width - 2.dp.toPx(), size.height - 2.dp.toPx()))
            }
        }
    }

    /** Shrinks on touch-down (not on release) with a critically damped spring, like iOS controls. */
    @Composable
    fun Modifier.pressable(enabled: Boolean = true, onClick: () -> Unit): Modifier {
        val source = remember { MutableInteractionSource() }
        val pressed by source.collectIsPressedAsState()
        val scale by animateFloatAsState(if (pressed) 0.9f else 1f, spring(dampingRatio = 1f, stiffness = 440f), label = "press")
        val alpha by animateFloatAsState(if (enabled) 1f else 0.35f, tween(200), label = "enabled")
        return graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
    }

    /** Big round shutter, like the iOS Camera app. Opens the camera. */
    @Composable
    fun Shutter(enabled: Boolean, onClick: () -> Unit) {
        val fg = MaterialTheme.colorScheme.onSurface
        Box(
            Modifier.size(78.dp)
                .semantics { contentDescription = "Take photo" }
                .pressable(enabled, onClick)
                .border(4.dp, fg, CircleShape)
                .padding(8.dp)
                .clip(CircleShape)
                .background(fg)
        )
    }

    /** Rounded square showing the last photo (or a stack glyph). Opens the gallery. */
    @Composable
    fun GalleryButton(photo: Bitmap?, enabled: Boolean, onClick: () -> Unit) {
        val shape = RoundedCornerShape(16.dp)
        Box(
            Modifier.size(54.dp)
                .semantics { contentDescription = "Choose from gallery" }
                .pressable(enabled, onClick)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.5.dp, MaterialTheme.colorScheme.outline, shape),
            contentAlignment = Alignment.Center
        ) {
            if (photo != null) {
                Image(photo.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                val fg = MaterialTheme.colorScheme.onSurface
                Canvas(Modifier.size(22.dp)) {
                    val st = Stroke(1.6.dp.toPx(), join = StrokeJoin.Round)
                    val s = size.width; val r = CornerRadius(4.dp.toPx())
                    drawRoundRect(fg.copy(alpha = 0.45f), Offset(s * 0.28f, 0f), Size(s * 0.72f, s * 0.72f), r, style = st)
                    drawRoundRect(fg, Offset(0f, s * 0.28f), Size(s * 0.72f, s * 0.72f), r, style = st)
                }
            }
        }
    }

    /** Camera-style focus brackets in the four corners of the empty viewfinder. */
    @Composable
    fun FrameBrackets(modifier: Modifier) {
        Canvas(modifier) {
            val len = 30.dp.toPx(); val w = size.width; val h = size.height
            val st = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val c = Color.White.copy(alpha = 0.7f)
            for ((x, y) in listOf(0f to 0f, w to 0f, 0f to h, w to h)) {
                val dx = if (x == 0f) len else -len
                val dy = if (y == 0f) len else -len
                drawPath(Path().apply { moveTo(x, y + dy); lineTo(x, y); lineTo(x + dx, y) }, c, style = st)
            }
        }
    }

    /** Frosted pill with the app name, floating on the viewfinder. */
    @Composable
    fun TitleChip(modifier: Modifier) {
        Row(
            modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)).padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(DONE_GREEN))
            Spacer(Modifier.width(7.dp))
            Text("Car Detector", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp)
        }
    }

    /** Rounded card on the page background. */
    @Composable
    fun Panel(content: @Composable ColumnScope.() -> Unit) {
        Column(
            Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 20.dp, vertical = 18.dp),
            content = content
        )
    }

    @Composable
    fun CarResult(c: JSONObject) {
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        val confidence = c.optInt("confidence").coerceIn(0, 100)
        val swatch = runCatching { Color(android.graphics.Color.parseColor(c.optString("colour_hex"))) }.getOrNull()
        val bar = remember { Animatable(0f) }
        LaunchedEffect(Unit) { delay(300); bar.animateTo(confidence / 100f, tween(900, easing = EaseOutQuint)) }

        Column(Modifier.padding(start = 6.dp, end = 6.dp, top = 18.dp)) {
            Reveal(0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(DONE_GREEN))
                    Spacer(Modifier.width(8.dp))
                    Text(c.optString("make").uppercase(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, color = muted)
                }
            }
            Reveal(80) { Text(c.optString("model"), fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp) }
        }
        Spacer(Modifier.height(14.dp))

        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile("Year", 160) { Text(c.optString("year_range"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
            Tile("Colour", 220) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (swatch != null) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(swatch)
                            .border(0.5.dp, MaterialTheme.colorScheme.outline, CircleShape))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(c.optString("colour").replaceFirstChar { it.uppercase() }, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Tile("Match", 280) {
                Text("$confidence%", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                // Confidence bar that fills up, in the accent colour.
                Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))) {
                    Box(Modifier.fillMaxWidth(bar.value).fillMaxHeight().clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                }
            }
        }
    }

    /** Small stat card: label on top, value below. */
    @Composable
    fun RowScope.Tile(label: String, delayMs: Int, value: @Composable ColumnScope.() -> Unit) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            Reveal(delayMs) {
                Column(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant).padding(14.dp)
                ) {
                    Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    value()
                }
            }
        }
    }
}

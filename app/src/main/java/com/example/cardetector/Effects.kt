package com.example.cardetector

// Viewfinder / motoring-magazine building blocks: corner brackets, scan line, gauge, spec rows, step log, glyphs.

import android.os.SystemClock
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

val ACCENT = Color(0xFFD7263D) // racing red, the only colour in the UI
val SERIF = FontFamily.Serif

val STEP_TITLES = listOf("Preparing photo", "Uploading", "Recognising car", "Reading answer")

/** Small letter-spaced caps used for labels and the wordmark. */
@Composable
fun Kicker(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, modifier: Modifier = Modifier) =
    Text(text.uppercase(), modifier, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)

/** Camera-viewfinder corner brackets. */
@Composable
fun Brackets(modifier: Modifier, color: Color) = Canvas(modifier) {
    val len = 26.dp.toPx(); val w = 3.dp.toPx()
    val (r, b) = size.width to size.height
    listOf(
        Offset(0f, 0f) to Offset(1f, 1f), Offset(r, 0f) to Offset(-1f, 1f),
        Offset(0f, b) to Offset(1f, -1f), Offset(r, b) to Offset(-1f, -1f),
    ).forEach { (p, d) ->
        drawLine(color, p, Offset(p.x + d.x * len, p.y), w, StrokeCap.Round)
        drawLine(color, p, Offset(p.x, p.y + d.y * len), w, StrokeCap.Round)
    }
}

/** Red line with a soft trail sweeping up and down over the photo while it's being read. */
@Composable
fun ScanLine(modifier: Modifier) {
    val y by rememberInfiniteTransition(label = "scan").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1600, easing = EaseInOutSine), RepeatMode.Reverse), label = "y"
    )
    Canvas(modifier) {
        val ly = size.height * y
        val trail = 60.dp.toPx()
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, ACCENT.copy(alpha = 0.35f)), ly - trail, ly),
            Offset(0f, ly - trail), Size(size.width, trail))
        drawLine(ACCENT, Offset(0f, ly), Offset(size.width, ly), 2.dp.toPx())
    }
}

/** Half-dial speedometer that sweeps up to [fraction]. */
@Composable
fun Gauge(fraction: Float, modifier: Modifier) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(fraction) { delay(200); p.animateTo(fraction, tween(1100, easing = EaseOutCubic)) }
    val track = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f)
    val tick = MaterialTheme.colorScheme.onSurfaceVariant
    Box(modifier, contentAlignment = Alignment.BottomCenter) {
        Canvas(Modifier.matchParentSize()) {
            val w = 10.dp.toPx()
            val d = minOf(size.width, size.height * 2) - w * 3
            val tl = Offset((size.width - d) / 2, w * 1.5f)
            drawArc(track, 180f, 180f, false, tl, Size(d, d), style = Stroke(w, cap = StrokeCap.Round))
            if (p.value > 0f) drawArc(ACCENT, 180f, 180f * p.value, false, tl, Size(d, d), style = Stroke(w, cap = StrokeCap.Round))
            // Ticks every 10% around the outside
            val c = Offset(size.width / 2, tl.y + d / 2)
            for (i in 0..10) {
                val a = Math.toRadians(180.0 + i * 18.0)
                val r1 = d / 2 + w; val r2 = r1 + if (i % 5 == 0) w else w / 2
                drawLine(tick, Offset(c.x + r1 * Math.cos(a).toFloat(), c.y + r1 * Math.sin(a).toFloat()),
                    Offset(c.x + r2 * Math.cos(a).toFloat(), c.y + r2 * Math.sin(a).toFloat()), 1.5.dp.toPx())
            }
        }
        Text("${(p.value * 100).roundToInt()}%", fontFamily = SERIF, fontWeight = FontWeight.Bold, fontSize = 30.sp)
    }
}

/** One hairline-separated spec line: label left, value right. */
@Composable
fun SpecRow(label: String, value: String, swatch: Color? = null) {
    HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f))
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Kicker(label, modifier = Modifier.weight(1f))
        if (swatch != null) {
            Box(Modifier.size(20.dp).clip(CircleShape).background(swatch)
                .border(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f), CircleShape))
            Spacer(Modifier.width(10.dp))
        }
        Text(value.ifBlank { "—" }, fontFamily = SERIF, fontWeight = FontWeight.Bold, fontSize = 20.sp)
    }
}

/** Fades and lifts its content in after [delayMs]. */
@Composable
fun Reveal(delayMs: Int, content: @Composable () -> Unit) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(delayMs.toLong())
        p.animateTo(1f, tween(400, easing = EaseOutQuint))
    }
    Box(Modifier.graphicsLayer { alpha = p.value; translationY = (1 - p.value) * 10.dp.toPx() }) { content() }
}

/** What the app is really doing, step by step, with how long each step took. */
@Composable
fun StageLog(stage: Int, starts: List<Long?>, note: String?) {
    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) { delay(100); value = SystemClock.elapsedRealtime() }
    }
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        0.3f, 1f, infiniteRepeatable(tween(600, easing = EaseInOutSine), RepeatMode.Reverse), label = "p"
    )
    val ink = MaterialTheme.colorScheme.onBackground
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        STEP_TITLES.forEachIndexed { i, title ->
            val start = starts.getOrNull(i)
            val end = if (i < stage) starts.getOrNull(i + 1) else now
            HorizontalDivider(color = ink.copy(alpha = 0.12f))
            Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("%02d".format(i + 1), Modifier.width(36.dp), fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                    color = if (i == stage) ACCENT else muted)
                Text(title, Modifier.weight(1f), fontFamily = SERIF, fontSize = 18.sp,
                    fontWeight = if (i == stage) FontWeight.Bold else FontWeight.Normal,
                    color = if (i <= stage) ink else muted.copy(alpha = 0.6f))
                when {
                    i < stage -> Text("✓", color = ink, fontWeight = FontWeight.Bold)
                    i == stage -> Box(Modifier.size(8.dp).graphicsLayer { alpha = pulse }.clip(CircleShape).background(ACCENT))
                }
                if (i <= stage && start != null && end != null)
                    Text("%.1fs".format((end - start) / 1000f), Modifier.width(52.dp), fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp, color = muted, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
        if (note != null) Text(note, Modifier.padding(top = 4.dp), fontSize = 13.sp, color = muted)
    }
}

/** Side-on silhouette line drawing of a car for the empty frame. */
@Composable
fun CarGlyph(modifier: Modifier, color: Color) = Canvas(modifier) {
    val w = size.width; val h = size.height
    val s = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
    val body = Path().apply {
        moveTo(w * 0.04f, h * 0.72f); lineTo(w * 0.04f, h * 0.52f); lineTo(w * 0.2f, h * 0.45f)
        lineTo(w * 0.34f, h * 0.18f); lineTo(w * 0.68f, h * 0.18f); lineTo(w * 0.82f, h * 0.44f)
        lineTo(w * 0.96f, h * 0.5f); lineTo(w * 0.96f, h * 0.72f)
    }
    drawPath(body, color, style = s)
    drawLine(color, Offset(w * 0.32f, h * 0.72f), Offset(w * 0.68f, h * 0.72f), s.width, StrokeCap.Round)
    drawCircle(color, h * 0.14f, Offset(w * 0.23f, h * 0.76f), style = s)
    drawCircle(color, h * 0.14f, Offset(w * 0.77f, h * 0.76f), style = s)
    drawLine(color, Offset(w * 0.5f, h * 0.18f), Offset(w * 0.5f, h * 0.45f), s.width)
    drawLine(color, Offset(w * 0.2f, h * 0.45f), Offset(w * 0.82f, h * 0.45f), s.width)
}

@Composable
fun CameraGlyph(modifier: Modifier, color: Color, hole: Color) = Canvas(modifier) {
    val w = size.width; val h = size.height
    drawRoundRect(color, Offset(w * 0.32f, h * 0.12f), Size(w * 0.36f, h * 0.2f), CornerRadius(w * 0.06f))
    drawRoundRect(color, Offset(0f, h * 0.22f), Size(w, h * 0.68f), CornerRadius(w * 0.16f))
    drawCircle(hole, w * 0.2f, Offset(w / 2, h * 0.56f))
    drawCircle(color, w * 0.11f, Offset(w / 2, h * 0.56f))
}

@Composable
fun GalleryGlyph(modifier: Modifier, color: Color) = Canvas(modifier) {
    val w = size.width; val h = size.height
    val s = Stroke(2.dp.toPx())
    drawRoundRect(color, Offset(s.width, h * 0.12f), Size(w - s.width * 2, h * 0.76f), CornerRadius(w * 0.14f), style = s)
    drawCircle(color, w * 0.08f, Offset(w * 0.32f, h * 0.36f))
    val hills = Path().apply {
        moveTo(w * 0.12f, h * 0.8f); lineTo(w * 0.4f, h * 0.5f); lineTo(w * 0.58f, h * 0.66f)
        lineTo(w * 0.7f, h * 0.56f); lineTo(w * 0.88f, h * 0.8f); close()
    }
    drawPath(hills, color)
}

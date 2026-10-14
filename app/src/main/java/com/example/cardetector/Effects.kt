package com.example.cardetector

// Camera-style "scanning" effects shown while a photo is analysed, plus the staggered reveal.

import android.os.SystemClock
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.*

val DONE_GREEN = Color(0xFF34D399)
val SCAN = Color(0xFF64D2FF)

val STEP_TITLES = listOf("Preparing photo", "Uploading to Gemini", "Recognising make & model", "Reading the answer")
private val STEP_SHORT = listOf("Prepare", "Upload", "Recognise", "Read")

/* ───────── scanner overlay ───────── */

// Fixed "feature points" (fractions of the frame) that light up as the scan line passes.
private class Spark(val x: Float, val y: Float, val r: Float)

private val SPARKS = run {
    val rnd = java.util.Random(11)
    Array(46) { Spark(0.08f + rnd.nextFloat() * 0.84f, 0.06f + rnd.nextFloat() * 0.88f, 1.2f + rnd.nextFloat() * 1.6f) }
}

/** Dimmed photo with a grid, a sweeping laser line, lit feature points, lock-on brackets and the current step. */
@Composable
fun ScanOverlay(stage: Int, modifier: Modifier = Modifier) {
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { time = (it - start) / 1e9f }
    }
    // Brackets fly in from the frame edges and lock on (slight overshoot, like a camera focusing).
    val lock = remember { Animatable(0f) }
    LaunchedEffect(Unit) { lock.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 140f)) }

    Box(modifier.background(Color.Black.copy(alpha = 0.45f))) {
        Canvas(Modifier.fillMaxSize()) {
            val t = time; val w = size.width; val h = size.height; val e = lock.value

            val step = 36.dp.toPx(); val grid = Color.White.copy(alpha = 0.06f * e.coerceIn(0f, 1f))
            var gx = step; while (gx < w) { drawLine(grid, Offset(gx, 0f), Offset(gx, h), 1f); gx += step }
            var gy = step; while (gy < h) { drawLine(grid, Offset(0f, gy), Offset(w, gy), 1f); gy += step }

            // Scan line ping-pongs top↔bottom, easing at the ends.
            val p = (1 - cos(t * 2 * PI.toFloat() / 2.4f)) / 2
            val y = h * (0.04f + 0.92f * p)
            val band = 70.dp.toPx()
            drawRect(
                Brush.verticalGradient(listOf(Color.Transparent, SCAN.copy(alpha = 0.28f), Color.Transparent), y - band, y + band),
                Offset(0f, y - band), Size(w, band * 2)
            )
            drawLine(
                Brush.horizontalGradient(listOf(Color.Transparent, SCAN, Color.White, SCAN, Color.Transparent)),
                Offset(0f, y), Offset(w, y), 2.dp.toPx()
            )

            val reach = 46.dp.toPx()
            for (s in SPARKS) {
                val a = (1 - abs(s.y * h - y) / reach).coerceIn(0f, 1f)
                if (a > 0f) drawCircle(SCAN.copy(alpha = a), s.r.dp.toPx() * (0.6f + 0.6f * a), Offset(s.x * w, s.y * h))
            }

            val half = min(w, h) * (0.5f - 0.2f * e + 0.012f * sin(t * 4f))
            val len = 26.dp.toPx()
            val st = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val c = Color.White.copy(alpha = 0.9f * e.coerceIn(0f, 1f))
            for (sx in listOf(-1f, 1f)) for (sy in listOf(-1f, 1f)) {
                val x = center.x + sx * half; val yy = center.y + sy * half
                drawPath(Path().apply { moveTo(x, yy - sy * len); lineTo(x, yy); lineTo(x - sx * len, yy) }, c, style = st)
            }
        }

        AnimatedContent(
            STEP_TITLES[stage.coerceIn(0, STEP_TITLES.lastIndex)],
            Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
            transitionSpec = {
                (fadeIn(tween(220)) + slideInVertically(spring(dampingRatio = 1f, stiffness = 500f)) { it }) togetherWith
                    (fadeOut(tween(150)) + slideOutVertically(tween(150)) { -it })
            },
            label = "stage"
        ) { title ->
            Row(
                Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PulseDot()
                Spacer(Modifier.width(8.dp))
                Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun PulseDot() {
    val a by rememberInfiniteTransition(label = "pulse").animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(600, easing = EaseInOutSine), RepeatMode.Reverse), label = "a"
    )
    Box(Modifier.size(7.dp).graphicsLayer { alpha = a; scaleX = 0.8f + 0.2f * a; scaleY = 0.8f + 0.2f * a }.clip(CircleShape).background(SCAN))
}

/* ───────── scanning edge glow ───────── */

// Two bright comets on a faint cyan ring.
private val COMETS = listOf(
    Color.Transparent, SCAN.copy(alpha = 0.5f), Color.White, SCAN, Color.Transparent,
    Color.Transparent, SCAN.copy(alpha = 0.5f), Color.White, SCAN, Color.Transparent,
)

private fun DrawScope.scanRing(angle: Float, corner: Dp) {
    clipPath(Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(corner.toPx()))) }) {
        drawRect(SCAN.copy(alpha = 0.25f))
        rotate(angle) { drawCircle(Brush.sweepGradient(COMETS), radius = size.maxDimension) }
    }
}

/** Faint cyan edge with two light streaks racing round it. Put it behind a card that is inset by a few dp. */
@Composable
fun ScanGlow(corner: Dp, modifier: Modifier = Modifier) {
    val angle by rememberInfiniteTransition(label = "glow").animateFloat(
        0f, 360f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "angle"
    )
    Box(modifier) {
        // ponytail: blur needs Android 12+; older phones just get the crisp ring
        Canvas(Modifier.matchParentSize().graphicsLayer { alpha = 0.8f }.blur(20.dp, BlurredEdgeTreatment.Unbounded)) {
            scanRing(angle, corner)
        }
        Canvas(Modifier.matchParentSize()) { scanRing(angle, corner) }
    }
}

/* ───────── staggered reveal ───────── */

/** Fades, lifts and un-blurs its content in after [delayMs]. */
@Composable
fun Reveal(delayMs: Int, content: @Composable () -> Unit) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(delayMs.toLong())
        p.animateTo(1f, tween(450, easing = EaseOutQuint))
    }
    Box(
        Modifier
            .graphicsLayer { alpha = p.value; translationY = (1 - p.value) * 8.dp.toPx() }
            .blur(((1 - p.value) * 6).dp, BlurredEdgeTreatment.Unbounded)
    ) { content() }
}

/* ───────── segmented stage progress ───────── */

/**
 * Four-segment progress bar of what the app is really doing. [stage] is the current stage,
 * [starts] holds when each stage began (elapsedRealtime ms, null = not reached).
 */
@Composable
fun StageProgress(stage: Int, starts: List<Long?>, note: String?, modifier: Modifier = Modifier) {
    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) { delay(100); value = SystemClock.elapsedRealtime() }
    }
    val fg = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Step ${stage + 1} of ${STEP_SHORT.size}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = muted, modifier = Modifier.weight(1f))
            val t0 = starts.firstOrNull()
            if (t0 != null) Text("%.1fs".format((now - t0) / 1000f), fontSize = 13.sp, color = muted)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            STEP_SHORT.forEachIndexed { i, label ->
                Column(Modifier.weight(1f)) {
                    Segment(done = i < stage, current = i == stage)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        label, fontSize = 12.sp, fontWeight = if (i == stage) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (i <= stage) fg else muted.copy(alpha = 0.6f)
                    )
                    // Real time spent in each finished step.
                    val start = starts.getOrNull(i)
                    val end = starts.getOrNull(i + 1)
                    Text(
                        if (i < stage && start != null && end != null) "%.1fs".format((end - start) / 1000f) else " ",
                        fontSize = 12.sp, color = muted
                    )
                }
            }
        }
        AnimatedVisibility(note != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Text(note.orEmpty(), fontSize = 13.sp, color = muted, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

/** One bar: fills when its step is done, runs a light sweep while it's the current step. */
@Composable
private fun Segment(done: Boolean, current: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
    val fill by animateFloatAsState(if (done) 1f else 0f, spring(dampingRatio = 1f, stiffness = 300f), label = "fill")
    val sweep by rememberInfiniteTransition(label = "sweep").animateFloat(
        -0.4f, 1f, infiniteRepeatable(tween(1100, easing = EaseInOutCubic)), label = "x"
    )
    Canvas(Modifier.fillMaxWidth().height(5.dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        if (current) {
            drawRoundRect(accent.copy(alpha = 0.25f), cornerRadius = r)
            val x = sweep * size.width
            drawRoundRect(
                accent, Offset(max(0f, x), 0f),
                Size(min(size.width, x + size.width * 0.4f) - max(0f, x), size.height), r
            )
        }
        if (fill > 0f) drawRoundRect(accent, size = Size(size.width * fill, size.height), cornerRadius = r)
    }
}

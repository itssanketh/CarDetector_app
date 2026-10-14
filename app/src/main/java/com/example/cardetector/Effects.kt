package com.example.cardetector

// Spec-sheet effects: hatched empty frame, progress stripe, step log, block meter, staggered reveal.

import android.os.SystemClock
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

val SIGNAL = Color(0xFFFF4F00)
val MONO = FontFamily.Monospace

val STEP_TITLES = listOf("PREPARE PHOTO", "UPLOAD TO GEMINI", "RECOGNISE MAKE/MODEL", "READ ANSWER")

/** Diagonal hatching for the empty photo frame. */
@Composable
fun Hatch(modifier: Modifier) {
    val ink = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f)
    Canvas(modifier.clipToBounds()) {
        val gap = 14.dp.toPx()
        var x = -size.height
        while (x < size.width) {
            drawLine(ink, Offset(x, size.height), Offset(x + size.height, 0f), 1.dp.toPx())
            x += gap
        }
    }
}

/** A short orange block sliding back and forth along the bottom edge while working. */
@Composable
fun WorkStripe(modifier: Modifier) {
    val p by rememberInfiniteTransition(label = "stripe").animateFloat(
        0f, 1f, infiniteRepeatable(tween(900, easing = EaseInOutCubic), RepeatMode.Reverse), label = "p"
    )
    Canvas(modifier.fillMaxWidth().height(4.dp)) {
        val w = size.width * 0.3f
        drawRect(SIGNAL, Offset((size.width - w) * p, 0f), Size(w, size.height))
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

/**
 * Terminal-style checklist of what the app is really doing. [stage] is the current stage,
 * [starts] holds when each stage began (elapsedRealtime ms, null = not reached).
 */
@Composable
fun StageLog(stage: Int, starts: List<Long?>, note: String?) {
    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) { delay(100); value = SystemClock.elapsedRealtime() }
    }
    val blink by rememberInfiniteTransition(label = "blink").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1000, easing = { if (it < 0.5f) 0f else 1f })), label = "b"
    )
    val ink = MaterialTheme.colorScheme.onBackground
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        STEP_TITLES.forEachIndexed { i, title ->
            val start = starts.getOrNull(i)
            val end = if (i < stage) starts.getOrNull(i + 1) else now
            Row {
                Text(
                    when { i < stage -> "[x]"; i == stage -> if (blink > 0.5f) "[■]" else "[ ]"; else -> "[ ]" },
                    fontFamily = MONO, fontSize = 14.sp, color = if (i == stage) SIGNAL else if (i < stage) ink else muted
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    title, fontFamily = MONO, fontSize = 14.sp, modifier = Modifier.weight(1f),
                    fontWeight = if (i == stage) FontWeight.Bold else FontWeight.Normal,
                    color = if (i <= stage) ink else muted
                )
                if (i <= stage && start != null && end != null)
                    Text("%.1fs".format((end - start) / 1000f), fontFamily = MONO, fontSize = 14.sp, color = muted)
            }
        }
        if (note != null) Text("> $note", fontFamily = MONO, fontSize = 13.sp, color = muted)
    }
}

/** 20 square cells, filled one by one up to [fraction]. */
@Composable
fun BlockMeter(fraction: Float, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.outline
    val p = remember { Animatable(0f) }
    LaunchedEffect(fraction) { delay(300); p.animateTo(fraction, tween(900, easing = EaseOutCubic)) }
    Canvas(modifier.fillMaxWidth().height(12.dp)) {
        val n = 20; val gap = 3.dp.toPx(); val w = (size.width - gap * (n - 1)) / n
        for (i in 0 until n) {
            val filled = (i + 1) / n.toFloat() <= p.value + 0.001f
            drawRect(if (filled) SIGNAL else track, Offset(i * (w + gap), 0f), Size(w, size.height))
        }
    }
}

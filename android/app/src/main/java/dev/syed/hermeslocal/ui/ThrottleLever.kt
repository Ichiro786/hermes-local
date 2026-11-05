package dev.syed.hermeslocal.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

enum class LeverMode { Off, Starting, Running, Stopping, Failed }

/**
 * Two-position cockpit throttle lever: down = off, up = on, nothing in between. Drag it (it snaps to the
 * nearer end on release, or follows a fling) or tap the quadrant. One haptic per switch. The only light
 * is a small status glow in the grip and at the top stop (accent when on). Lever position is read only in
 * the draw block, so moving it redraws without recomposing (smooth at 120 Hz).
 */
@Composable
fun ThrottleLever(
    on: Boolean,
    mode: LeverMode,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val pos = remember { Animatable(if (on) 1f else 0f) }
    var dragging by remember { mutableStateOf(false) }
    val currentOn by rememberUpdatedState(on)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentToggle by rememberUpdatedState(onToggle)

    // The engine is the source of truth: when it changes (start, stop, failure) the lever follows.
    LaunchedEffect(on) {
        if (!dragging) pos.animateTo(if (on) 1f else 0f, SNAP)
    }

    val scheme = MaterialTheme.colorScheme
    val accent by animateColorAsState(
        when (mode) {
            LeverMode.Off -> scheme.outline
            LeverMode.Starting, LeverMode.Stopping -> StatusColors.Amber
            LeverMode.Running -> scheme.primary
            LeverMode.Failed -> StatusColors.Red
        },
        animationSpec = tween(300), label = "leverAccent",
    )
    val glow by animateFloatAsState(if (mode == LeverMode.Off) 0f else 1f, tween(300), label = "glow")
    val pressed by animateFloatAsState(if (dragging) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 600f), label = "press")

    fun haptic(constant: Int) { view.performHapticFeedback(constant) }
    fun settle(target: Float, velocityPerSec: Float) {
        scope.launch { pos.animateTo(target, SNAP, initialVelocity = velocityPerSec) }
    }

    Canvas(
        modifier
            .semantics {
                role = Role.Switch
                stateDescription = when (mode) {
                    LeverMode.Off -> "Off"; LeverMode.Starting -> "Starting"; LeverMode.Running -> "Running"
                    LeverMode.Stopping -> "Stopping"; LeverMode.Failed -> "Failed"
                }
                onClick(label = if (on) "Stop Hermes" else "Start Hermes") { if (enabled) onToggle(!on); true }
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    if (!currentEnabled) { haptic(rejectConstant()); return@detectTapGestures }
                    haptic(confirmConstant())
                    currentToggle(!currentOn)
                })
            }
            .pointerInput(Unit) {
                val tracker = VelocityTracker()
                detectVerticalDragGestures(
                    onDragStart = {
                        tracker.resetTracking()
                        dragging = true
                        scope.launch { pos.stop() }
                    },
                    onDragEnd = {
                        dragging = false
                        val travel = size.height * TRAVEL
                        val velocity = -tracker.calculateVelocity().y / travel // travel/s, positive = up
                        val wantOn = when {
                            velocity > 2f -> true
                            velocity < -2f -> false
                            else -> pos.value > 0.5f
                        }
                        if (!currentEnabled && wantOn != currentOn) {
                            haptic(rejectConstant())
                            settle(if (currentOn) 1f else 0f, velocity)
                            return@detectVerticalDragGestures
                        }
                        settle(if (wantOn) 1f else 0f, velocity)
                        if (wantOn != currentOn) {
                            haptic(confirmConstant())
                            currentToggle(wantOn)
                        }
                    },
                    onDragCancel = {
                        dragging = false
                        settle(if (currentOn) 1f else 0f, 0f)
                    },
                    onVerticalDrag = { change, dy ->
                        change.consume()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        val raw = pos.value - dy / (size.height * TRAVEL)
                        // Rubber-band a little past the stops, like a lever meeting its gate.
                        val next = when {
                            raw > 1f -> 1f + (raw - 1f) * 0.2f
                            raw < 0f -> raw * 0.2f
                            else -> raw
                        }.coerceIn(-0.05f, 1.05f)
                        scope.launch { pos.snapTo(next) }
                    },
                )
            },
    ) {
        val p = pos.value
        drawQuadrant(scheme.surfaceContainerHigh, scheme.surfaceContainerLowest, scheme.outlineVariant)
        val slotTop = size.height * (0.5f - TRAVEL / 2f)
        val slotBottom = size.height * (0.5f + TRAVEL / 2f)
        val cx = size.width * 0.5f
        drawSlot(cx, slotTop, slotBottom, accent, glow)
        val handleY = slotBottom - p * (slotBottom - slotTop)
        drawRod(cx, slotBottom, handleY)
        drawHandle(cx, handleY, p, pressed, accent, glow)
    }
}

/** Light, quick snap: a touch of overshoot, settles in ~250 ms. */
private val SNAP = spring<Float>(dampingRatio = 0.7f, stiffness = 420f)
private const val TRAVEL = 0.62f

private fun DrawScope.drawQuadrant(top: Color, bottom: Color, edge: Color) {
    val r = CornerRadius(40.dp.toPx())
    drawRoundRect(Brush.verticalGradient(listOf(top, bottom)), cornerRadius = r)
    drawRoundRect(edge.copy(alpha = 0.45f), cornerRadius = r, style = Stroke(1.dp.toPx()))
    drawRoundRect(
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.06f), Color.Transparent), endY = size.height * 0.25f),
        cornerRadius = r,
    )
}

/** The gate the lever rides in, with a small glow at the top (ON) stop. */
private fun DrawScope.drawSlot(cx: Float, top: Float, bottom: Float, accent: Color, glow: Float) {
    val w = 26.dp.toPx()
    val pad = 22.dp.toPx()
    val t = top - pad
    val h = bottom - top + pad * 2
    if (glow > 0f) {
        drawCircle(
            Brush.radialGradient(listOf(accent.copy(alpha = 0.35f * glow), Color.Transparent), center = Offset(cx, t + w / 2), radius = w * 2.2f),
            radius = w * 2.2f, center = Offset(cx, t + w / 2),
        )
    }
    drawRoundRect(
        Brush.horizontalGradient(listOf(Color(0xFF000000), Color(0xFF16191B), Color(0xFF000000)), startX = cx - w / 2, endX = cx + w / 2),
        topLeft = Offset(cx - w / 2, t), size = Size(w, h), cornerRadius = CornerRadius(w / 2),
    )
    drawRoundRect(
        Color.White.copy(alpha = 0.07f), topLeft = Offset(cx - w / 2, t), size = Size(w, h),
        cornerRadius = CornerRadius(w / 2), style = Stroke(1.dp.toPx()),
    )
}

private fun DrawScope.drawRod(cx: Float, bottom: Float, handleY: Float) {
    val w = 10.dp.toPx()
    val pad = 16.dp.toPx()
    drawRoundRect(
        Brush.horizontalGradient(listOf(Color(0xFF5B636A), Color(0xFFC9D1D6), Color(0xFF4A5157)), startX = cx - w / 2, endX = cx + w / 2),
        topLeft = Offset(cx - w / 2, handleY), size = Size(w, (bottom + pad - handleY).coerceAtLeast(0f)), cornerRadius = CornerRadius(w / 2),
    )
}

private fun DrawScope.drawHandle(cx: Float, cy: Float, p: Float, pressed: Float, accent: Color, glow: Float) {
    // Perspective: the grip is a little smaller pushed forward (up), a little bigger while held.
    val scale = (1f - 0.06f * p.coerceIn(0f, 1f)) * (1f + 0.04f * pressed)
    val w = size.width * 0.62f * scale
    val h = 84.dp.toPx() * scale
    val r = CornerRadius(26.dp.toPx() * scale)
    val left = cx - w / 2
    val top = cy - h / 2
    for (i in 1..4) {
        drawRoundRect(
            Color.Black.copy(alpha = 0.16f), topLeft = Offset(left - i, top + i * 3f + 4f), size = Size(w + 2 * i, h + 2 * i),
            cornerRadius = r,
        )
    }
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFF4C5258), Color(0xFF25292D), Color(0xFF111315)), startY = top, endY = top + h),
        topLeft = Offset(left, top), size = Size(w, h), cornerRadius = r,
    )
    drawRoundRect(
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.28f), Color.Transparent), startY = top, endY = top + h * 0.35f),
        topLeft = Offset(left, top), size = Size(w, h), cornerRadius = r,
    )
    // Grip ridges
    val ridgeW = w * 0.6f
    for (k in 0..2) {
        val y = top + h * (0.24f + 0.12f * k)
        drawLine(Color.Black.copy(alpha = 0.45f), Offset(cx - ridgeW / 2, y), Offset(cx + ridgeW / 2, y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        drawLine(Color.White.copy(alpha = 0.08f), Offset(cx - ridgeW / 2, y + 2.dp.toPx()), Offset(cx + ridgeW / 2, y + 2.dp.toPx()), strokeWidth = 1.dp.toPx(), cap = StrokeCap.Round)
    }
    // Status light: dim when off, accent with a soft glow when on.
    val stripW = w * 0.5f
    val stripH = 6.dp.toPx()
    val sy = top + h * 0.72f
    for (g in 3 downTo 1) {
        val spread = g * 3.dp.toPx()
        drawRoundRect(
            accent.copy(alpha = 0.10f * glow), topLeft = Offset(cx - stripW / 2 - spread, sy - spread),
            size = Size(stripW + spread * 2, stripH + spread * 2), cornerRadius = CornerRadius(stripH),
        )
    }
    drawRoundRect(accent.copy(alpha = 0.35f + 0.65f * glow), topLeft = Offset(cx - stripW / 2, sy), size = Size(stripW, stripH), cornerRadius = CornerRadius(stripH / 2))
    drawRoundRect(Color.White.copy(alpha = 0.10f), topLeft = Offset(left, top), size = Size(w, h), cornerRadius = r, style = Stroke(1.dp.toPx()))
}

private fun confirmConstant() = if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS
private fun rejectConstant() = if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS

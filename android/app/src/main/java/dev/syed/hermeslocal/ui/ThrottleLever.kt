package dev.syed.hermeslocal.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

enum class LeverMode { Off, Starting, Running, Stopping, Failed }

/** Airbus-style thrust detents, bottom to top. IDLE = off, TOGA = on. */
private val DETENTS = listOf("IDLE", "CL", "FLX MCT", "TOGA")

/**
 * Cockpit throttle lever on its quadrant. Drag it up to start Hermes and down to stop, or tap the
 * quadrant. Spring physics with velocity hand-off, a haptic tick at each detent, and a status-coloured
 * light strip in the grip. All per-frame values are read inside the draw block, so moving the lever
 * only redraws (no recomposition) and stays smooth at 120 Hz.
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
    var lastDetent by remember { mutableFloatStateOf(if (on) 3f else 0f) }
    val currentOn by rememberUpdatedState(on)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentToggle by rememberUpdatedState(onToggle)

    // The engine is the source of truth: when it changes (start, stop, failure) the lever follows.
    LaunchedEffect(on) {
        if (!dragging) pos.animateTo(if (on) 1f else 0f, spring(dampingRatio = 0.62f, stiffness = 240f))
    }

    val scheme = MaterialTheme.colorScheme
    val accent by animateColorAsState(
        when (mode) {
            LeverMode.Off -> scheme.outline
            LeverMode.Starting, LeverMode.Stopping -> StatusColors.Amber
            LeverMode.Running -> scheme.primary
            LeverMode.Failed -> StatusColors.Red
        },
        animationSpec = tween(350), label = "leverAccent",
    )
    val pressed by animateFloatAsState(if (dragging) 1f else 0f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "press")
    val busy = mode == LeverMode.Starting || mode == LeverMode.Stopping
    // Looping light effects exist only while needed, so an idle lever schedules no frames.
    val chase = if (busy) {
        rememberInfiniteTransition(label = "chase").animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "chase")
    } else null
    val breathe = if (mode == LeverMode.Running) {
        rememberInfiniteTransition(label = "breathe").animateFloat(0.55f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "breathe")
    } else null

    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 1.2.sp)

    fun haptic(constant: Int) { view.performHapticFeedback(constant) }
    fun settle(target: Float, velocityPerSec: Float) {
        scope.launch {
            pos.animateTo(target, spring(dampingRatio = 0.55f, stiffness = 300f), initialVelocity = velocityPerSec)
        }
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
                    haptic(if (currentOn) HapticFeedbackConstants.VIRTUAL_KEY else confirmConstant())
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
                        haptic(gestureStartConstant())
                    },
                    onDragEnd = {
                        dragging = false
                        val travel = size.height * TRAVEL
                        val vy = tracker.calculateVelocity().y // px/s, positive = down
                        val velocity = -vy / travel
                        val p = pos.value
                        val wantOn = when {
                            velocity > 2.2f -> true
                            velocity < -2.2f -> false
                            else -> p > 0.5f
                        }
                        if (!currentEnabled && wantOn != currentOn) {
                            haptic(rejectConstant())
                            settle(if (currentOn) 1f else 0f, velocity)
                            return@detectVerticalDragGestures
                        }
                        settle(if (wantOn) 1f else 0f, velocity)
                        if (wantOn != currentOn) {
                            haptic(if (wantOn) confirmConstant() else HapticFeedbackConstants.VIRTUAL_KEY)
                            currentToggle(wantOn)
                        } else haptic(gestureEndConstant())
                    },
                    onDragCancel = {
                        dragging = false
                        settle(if (currentOn) 1f else 0f, 0f)
                    },
                    onVerticalDrag = { change, dy ->
                        change.consume()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        val travel = size.height * TRAVEL
                        // Rubber-band past the stops, like a lever hitting its gate.
                        val raw = pos.value - dy / travel
                        val next = when {
                            raw > 1f -> 1f + (raw - 1f) * 0.25f
                            raw < 0f -> raw * 0.25f
                            else -> raw
                        }.coerceIn(-0.06f, 1.06f)
                        scope.launch { pos.snapTo(next) }
                        val detent = (next.coerceIn(0f, 1f) * 3f).roundToInt().toFloat()
                        if (detent != lastDetent && abs(next * 3f - detent) < 0.12f) {
                            lastDetent = detent
                            haptic(HapticFeedbackConstants.CLOCK_TICK)
                        }
                    },
                )
            },
    ) {
        val p = pos.value
        drawQuadrant(scheme.surfaceContainerHigh, scheme.surfaceContainerLowest, scheme.outlineVariant)
        val slotTop = size.height * (0.5f - TRAVEL / 2f)
        val slotBottom = size.height * (0.5f + TRAVEL / 2f)
        val cx = size.width * 0.5f
        drawSlot(cx, slotTop, slotBottom)
        drawScale(p, slotTop, slotBottom, accent, scheme.onSurfaceVariant, measurer, labelStyle)
        drawLeds(p, slotTop, slotBottom, accent, chase?.value ?: -1f, breathe?.value ?: 1f)
        val handleY = slotBottom - p * (slotBottom - slotTop)
        drawRod(cx, slotBottom, handleY)
        drawHandle(cx, handleY, p, pressed, accent, breathe?.value ?: 1f)
    }
}

private const val TRAVEL = 0.68f

private fun DrawScope.drawQuadrant(top: Color, bottom: Color, edge: Color) {
    val r = CornerRadius(36.dp.toPx())
    drawRoundRect(Brush.verticalGradient(listOf(top, bottom)), cornerRadius = r)
    drawRoundRect(edge.copy(alpha = 0.45f), cornerRadius = r, style = Stroke(1.dp.toPx()))
    // Soft top highlight, like light falling on the pedestal.
    drawRoundRect(
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.06f), Color.Transparent), endY = size.height * 0.25f),
        cornerRadius = r,
    )
}

private fun DrawScope.drawSlot(cx: Float, top: Float, bottom: Float) {
    val w = 22.dp.toPx()
    val pad = 18.dp.toPx()
    drawRoundRect(
        Brush.horizontalGradient(listOf(Color(0xFF000000), Color(0xFF15181A), Color(0xFF000000)), startX = cx - w / 2, endX = cx + w / 2),
        topLeft = Offset(cx - w / 2, top - pad), size = Size(w, bottom - top + pad * 2), cornerRadius = CornerRadius(w / 2),
    )
    drawRoundRect(
        Color.White.copy(alpha = 0.07f), topLeft = Offset(cx - w / 2, top - pad), size = Size(w, bottom - top + pad * 2),
        cornerRadius = CornerRadius(w / 2), style = Stroke(1.dp.toPx()),
    )
}

private fun DrawScope.drawScale(
    p: Float, top: Float, bottom: Float, accent: Color, ink: Color,
    measurer: androidx.compose.ui.text.TextMeasurer, style: TextStyle,
) {
    val x = size.width * 0.30f
    val minor = 7.dp.toPx()
    val major = 16.dp.toPx()
    val nearest = (p.coerceIn(0f, 1f) * 3f).roundToInt()
    for (i in 0..12) {
        val f = i / 12f
        val y = bottom - f * (bottom - top)
        val isMajor = i % 4 == 0
        val lit = f <= p + 0.001f
        drawLine(
            if (lit) accent.copy(alpha = 0.9f) else ink.copy(alpha = 0.35f),
            Offset(x - (if (isMajor) major else minor), y), Offset(x, y),
            strokeWidth = (if (isMajor) 2.5f else 1.5f).dp.toPx(), cap = StrokeCap.Round,
        )
        if (isMajor) {
            val idx = i / 4
            val active = idx == nearest
            val layout = measurer.measure(DETENTS[idx], style.copy(color = if (active) accent else ink.copy(alpha = 0.6f)))
            drawText(layout, topLeft = Offset(x - major - 8.dp.toPx() - layout.size.width, y - layout.size.height / 2f))
        }
    }
}

private fun DrawScope.drawLeds(p: Float, top: Float, bottom: Float, accent: Color, chase: Float, breathe: Float) {
    val x = size.width * 0.74f
    val n = 14
    val h = (bottom - top) / n
    val w = 10.dp.toPx()
    for (i in 0 until n) {
        val f = (i + 0.5f) / n
        val y = bottom - (i + 1) * h
        val lit = f <= p
        var alpha = if (lit) 0.85f * breathe else 0.12f
        if (chase >= 0f) {
            val d = abs(f - chase)
            alpha = maxOf(alpha, (1f - d * 5f).coerceIn(0f, 1f))
        }
        val color = if (lit || chase >= 0f) accent else Color.White
        drawRoundRect(
            color.copy(alpha = alpha), topLeft = Offset(x, y + h * 0.18f), size = Size(w, h * 0.64f),
            cornerRadius = CornerRadius(w / 2),
        )
    }
}

private fun DrawScope.drawRod(cx: Float, bottom: Float, handleY: Float) {
    val w = 9.dp.toPx()
    val pad = 14.dp.toPx()
    drawRoundRect(
        Brush.horizontalGradient(listOf(Color(0xFF5B636A), Color(0xFFC9D1D6), Color(0xFF4A5157)), startX = cx - w / 2, endX = cx + w / 2),
        topLeft = Offset(cx - w / 2, handleY), size = Size(w, (bottom + pad - handleY).coerceAtLeast(0f)), cornerRadius = CornerRadius(w / 2),
    )
}

private fun DrawScope.drawHandle(cx: Float, cy: Float, p: Float, pressed: Float, accent: Color, glow: Float) {
    // Perspective: the grip is a little smaller when pushed forward (up), a little bigger when pressed.
    val scale = (1f - 0.07f * p.coerceIn(0f, 1f)) * (1f + 0.04f * pressed)
    val w = size.width * 0.56f * scale
    val h = 66.dp.toPx() * scale
    val r = CornerRadius(20.dp.toPx() * scale)
    val left = cx - w / 2
    val top = cy - h / 2
    // Shadow
    for (i in 1..4) {
        drawRoundRect(
            Color.Black.copy(alpha = 0.16f), topLeft = Offset(left - i, top + i * 3f + 4f), size = Size(w + 2 * i, h + 2 * i),
            cornerRadius = r,
        )
    }
    withTransform({}) {
        drawRoundRect(
            Brush.verticalGradient(listOf(Color(0xFF4A5056), Color(0xFF24282C), Color(0xFF111315)), startY = top, endY = top + h),
            topLeft = Offset(left, top), size = Size(w, h), cornerRadius = r,
        )
        // Machined top edge
        drawRoundRect(
            Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.28f), Color.Transparent), startY = top, endY = top + h * 0.35f),
            topLeft = Offset(left, top), size = Size(w, h), cornerRadius = r,
        )
        // Grip ridges
        val ridgeW = w * 0.62f
        for (k in 0..2) {
            val y = top + h * (0.24f + 0.13f * k)
            drawLine(Color.Black.copy(alpha = 0.45f), Offset(cx - ridgeW / 2, y), Offset(cx + ridgeW / 2, y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            drawLine(Color.White.copy(alpha = 0.08f), Offset(cx - ridgeW / 2, y + 2.dp.toPx()), Offset(cx + ridgeW / 2, y + 2.dp.toPx()), strokeWidth = 1.dp.toPx(), cap = StrokeCap.Round)
        }
        // Status light strip with glow
        val stripW = w * 0.7f
        val stripH = 6.dp.toPx()
        val sy = top + h * 0.72f
        for (g in 3 downTo 1) {
            val spread = g * 3.dp.toPx()
            drawRoundRect(
                accent.copy(alpha = 0.10f * glow), topLeft = Offset(cx - stripW / 2 - spread, sy - spread),
                size = Size(stripW + spread * 2, stripH + spread * 2), cornerRadius = CornerRadius(stripH),
            )
        }
        drawRoundRect(accent.copy(alpha = 0.6f + 0.4f * glow), topLeft = Offset(cx - stripW / 2, sy), size = Size(stripW, stripH), cornerRadius = CornerRadius(stripH / 2))
        drawRoundRect(Color.White.copy(alpha = 0.10f), topLeft = Offset(left, top), size = Size(w, h), cornerRadius = r, style = Stroke(1.dp.toPx()))
    }
}

private fun confirmConstant() = if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS
private fun rejectConstant() = if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
private fun gestureStartConstant() = if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.GESTURE_START else HapticFeedbackConstants.VIRTUAL_KEY
private fun gestureEndConstant() = if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.GESTURE_END else HapticFeedbackConstants.VIRTUAL_KEY

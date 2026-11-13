package com.deskpad.app.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deskpad.app.model.AppSettings
import com.deskpad.app.model.MouseButton
import com.deskpad.app.model.PointerMotionAccumulator
import com.deskpad.app.ui.theme.LocalDeskPadPalette
import com.deskpad.app.ui.theme.PadPattern
import kotlin.math.hypot
import kotlin.math.roundToInt

private data class TouchRipple(
    val id: Long,
    val center: Offset,
    val color: Color
)

@Composable
fun TrackpadSurface(
    settings: AppSettings,
    isCompact: Boolean,
    onMove: (Int, Int) -> Unit,
    onScroll: (Int, Int) -> Unit,
    onZoom: (Int) -> Unit,
    onClick: (MouseButton) -> Unit,
    onMouseButton: (MouseButton, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalDeskPadPalette.current
    val view = LocalView.current
    val touchSlop = LocalViewConfiguration.current.touchSlop

    val currentSettings by rememberUpdatedState(settings)
    val accumulator = remember { PointerMotionAccumulator() }
    val ripples = remember { mutableStateListOf<TouchRipple>() }
    var activeFingerPos by remember { mutableStateOf<Offset?>(null) }
    var gestureHint by remember { mutableStateOf<String?>(null) }
    var rippleSeq by remember { mutableStateOf(0L) }

    fun triggerHaptic(constant: Int = HapticFeedbackConstants.KEYBOARD_TAP) {
        if (currentSettings.hapticFeedback) {
            view.performHapticFeedback(constant)
        }
    }

    fun addRipple(pos: Offset, color: Color = palette.accent) {
        rippleSeq += 1L
        ripples.add(TouchRipple(rippleSeq, pos, color))
    }

    val shape = RoundedCornerShape(palette.padCornerRadius)

    Box(
        modifier = modifier
            .clip(shape)
            .background(palette.padSurface)
            .border(1.dp, palette.border, shape)
            .pointerInput(Unit) {
                var lastSingleTapUpTime = 0L
                var scrollRemX = 0f
                var scrollRemY = 0f
                var zoomRem = 0f

                awaitEachGesture {
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    val startTime = System.currentTimeMillis()
                    var maxPointers = 1
                    var totalTravel = 0f
                    var lastTapPos = firstDown.position
                    var isTapDragging = false
                    accumulator.reset()
                    scrollRemX = 0f
                    scrollRemY = 0f
                    zoomRem = 0f

                    if (currentSettings.tapToClick &&
                        (startTime - lastSingleTapUpTime) < 230L
                    ) {
                        isTapDragging = true
                        onMouseButton(MouseButton.LEFT, true)
                        gestureHint = "Dragging"
                        triggerHaptic(HapticFeedbackConstants.LONG_PRESS)
                    }

                    activeFingerPos = firstDown.position

                    var prevPinchSpan: Float? = null

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break

                        if (pressed.size > maxPointers) {
                            maxPointers = pressed.size
                            if (isTapDragging && maxPointers > 1) {
                                isTapDragging = false
                                onMouseButton(MouseButton.LEFT, false)
                            }
                        }

                        if (pressed.size == 1) {
                            prevPinchSpan = null
                            val change = pressed[0]
                            activeFingerPos = change.position
                            lastTapPos = change.position
                            if (change.positionChanged()) {
                                val delta = change.position - change.previousPosition
                                totalTravel += hypot(delta.x, delta.y)
                                val (ix, iy) = accumulator.accumulate(
                                    delta.x,
                                    delta.y,
                                    currentSettings.cursorSensitivity
                                )
                                if (ix != 0 || iy != 0) {
                                    onMove(ix, iy)
                                }
                                change.consume()
                            }
                        } else if (pressed.size == 2) {
                            activeFingerPos = (pressed[0].position + pressed[1].position) / 2f
                            val p0 = pressed[0].position
                            val p1 = pressed[1].position
                            val span = hypot(p0.x - p1.x, p0.y - p1.y)

                            val prev0 = pressed[0].previousPosition
                            val prev1 = pressed[1].previousPosition
                            val avgDelta = ((p0 - prev0) + (p1 - prev1)) / 2f
                            totalTravel += hypot(avgDelta.x, avgDelta.y)

                            val spanDelta = prevPinchSpan?.let { span - it } ?: 0f
                            prevPinchSpan = span

                            if (kotlin.math.abs(spanDelta) > 6f &&
                                kotlin.math.abs(spanDelta) > hypot(avgDelta.x, avgDelta.y) * 0.9f
                            ) {
                                gestureHint = "Zoom"
                                zoomRem += spanDelta * 0.15f
                                val iz = zoomRem.roundToInt()
                                if (iz != 0) {
                                    onZoom(iz)
                                    zoomRem -= iz
                                }
                            } else {
                                gestureHint = "Scroll"
                                val dir = if (currentSettings.naturalScrolling) 1f else -1f
                                val mult = currentSettings.scrollSensitivity * 0.45f
                                scrollRemX += avgDelta.x * mult * dir
                                scrollRemY += avgDelta.y * mult * dir
                                val sx = scrollRemX.roundToInt()
                                val sy = scrollRemY.roundToInt()
                                if (sx != 0 || sy != 0) {
                                    onScroll(sx, sy)
                                    scrollRemX -= sx
                                    scrollRemY -= sy
                                }
                            }
                            pressed.forEach { it.consume() }
                        } else {
                            // 3+ fingers
                            activeFingerPos = null
                            pressed.forEach {
                                val d = it.position - it.previousPosition
                                totalTravel += hypot(d.x, d.y)
                                it.consume()
                            }
                        }
                    }

                    activeFingerPos = null
                    gestureHint = null
                    val elapsed = System.currentTimeMillis() - startTime

                    if (isTapDragging) {
                        onMouseButton(MouseButton.LEFT, false)
                        lastSingleTapUpTime = 0L
                    } else if (totalTravel < touchSlop && elapsed < 260L) {
                        when (maxPointers) {
                            1 -> if (currentSettings.tapToClick) {
                                onClick(MouseButton.LEFT)
                                lastSingleTapUpTime = System.currentTimeMillis()
                                triggerHaptic()
                                addRipple(lastTapPos, palette.accent)
                            }
                            2 -> {
                                onClick(MouseButton.RIGHT)
                                triggerHaptic(HapticFeedbackConstants.LONG_PRESS)
                                addRipple(lastTapPos, palette.statusConnected)
                            }
                            else -> {
                                onClick(MouseButton.MIDDLE)
                                triggerHaptic(HapticFeedbackConstants.LONG_PRESS)
                                addRipple(lastTapPos, palette.accent)
                            }
                        }
                    }
                }
            }
    ) {
        // Patterned precision surface + active touch glow
        Canvas(modifier = Modifier.fillMaxSize()) {
            when (palette.padPattern) {
                PadPattern.DOT_MATRIX -> {
                    val step = 20.dp.toPx()
                    var x = step
                    while (x < size.width) {
                        var y = step
                        while (y < size.height) {
                            drawCircle(
                                color = palette.padGridColor,
                                radius = 1.25.dp.toPx(),
                                center = Offset(x, y)
                            )
                            y += step
                        }
                        x += step
                    }
                }
                PadPattern.CYBER_GRID -> {
                    val step = 28.dp.toPx()
                    var x = step
                    while (x < size.width) {
                        drawLine(
                            color = palette.padGridColor,
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = 1f
                        )
                        x += step
                    }
                    var y = step
                    while (y < size.height) {
                        drawLine(
                            color = palette.padGridColor,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1f
                        )
                        y += step
                    }
                }
                PadPattern.SUBTLE_RECESSED -> {
                    val step = 22.dp.toPx()
                    var x = step
                    while (x < size.width) {
                        var y = step
                        while (y < size.height) {
                            drawCircle(
                                color = palette.padGridColor,
                                radius = 1.dp.toPx(),
                                center = Offset(x, y)
                            )
                            y += step
                        }
                        x += step
                    }
                }
            }

            // Center subtle reticle
            drawCircle(
                color = palette.accent.copy(alpha = 0.14f),
                radius = 18.dp.toPx(),
                center = Offset(size.width / 2f, size.height / 2f),
                style = Stroke(width = 1.2.dp.toPx())
            )

            // Live finger glow
            activeFingerPos?.let { pos ->
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            palette.accent.copy(alpha = 0.28f),
                            Color.Transparent
                        ),
                        center = pos,
                        radius = 48.dp.toPx()
                    ),
                    radius = 48.dp.toPx(),
                    center = pos
                )
            }
        }

        // Animated tap ripples
        ripples.forEach { ripple ->
            TapRippleOverlay(
                ripple = ripple,
                onFinished = { ripples.remove(ripple) }
            )
        }

        // Minimal corner gesture legend
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = gestureHint ?: if (isCompact) "Compact Trackpad" else "1-Finger Move • Tap Click",
                color = if (gestureHint != null) palette.accent else palette.textMuted.copy(alpha = 0.72f),
                fontSize = 11.sp,
                fontWeight = if (gestureHint != null) FontWeight.SemiBold else FontWeight.Medium
            )
            if (!isCompact) {
                Text(
                    text = "2-Finger Scroll & Right Click",
                    color = palette.textMuted.copy(alpha = 0.72f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun TapRippleOverlay(
    ripple: TouchRipple,
    onFinished: () -> Unit
) {
    val progress = remember(ripple.id) { Animatable(0f) }

    LaunchedEffect(ripple.id) {
        progress.animateTo(1f, animationSpec = tween(durationMillis = 360))
        onFinished()
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val p = progress.value
        val radius = (10.dp.toPx() + 34.dp.toPx() * p)
        val alpha = (1f - p) * 0.65f
        drawCircle(
            color = ripple.color.copy(alpha = alpha),
            radius = radius,
            center = ripple.center,
            style = Stroke(width = 2.dp.toPx())
        )
    }
}

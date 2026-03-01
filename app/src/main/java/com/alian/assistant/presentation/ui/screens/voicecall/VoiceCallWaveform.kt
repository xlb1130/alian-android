package com.alian.assistant.presentation.ui.screens.voicecall

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.alian.assistant.presentation.ui.theme.BaoziTheme
import kotlin.math.PI
import kotlin.math.sin

/**
 * 声波可视化组件
 * @param isAnimating 是否正在动画
 * @param color 声波颜色
 */
@Composable
fun VoiceCallWaveform(
    isAnimating: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    val colors = BaoziTheme.colors

    if (isAnimating) {
        AnimatedWaveform(
            color = color,
            modifier = modifier
        )
    } else {
        IdleWaveform(
            color = colors.textHint.copy(alpha = 0.3f),
            modifier = modifier
        )
    }
}

/**
 * 动画声波
 */
@Composable
fun AnimatedWaveform(
    color: Color,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")

    // 创建多个波纹，每个有不同的相位和速度
    val phase1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase1"
    )

    val phase2 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase2"
    )

    val phase3 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase3"
    )

    val phase4 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase4"
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
    ) {
        val centerX = size.width / 2
        val centerY = size.height / 2
        val maxRadius = size.minDimension / 2 * 0.85f

        // 绘制四个同心圆波纹
        val phases = listOf(phase1, phase2, phase3, phase4)
        phases.forEachIndexed { index, phase ->
            val radius = maxRadius * (0.25f + 0.75f * phase)
            val alpha = (1f - phase) * 0.6f

            // 使用渐变描边
            drawCircle(
                color = color.copy(alpha = alpha),
                radius = radius,
                center = Offset(centerX, centerY),
                style = Stroke(width = (2.5f - index * 0.3f).dp.toPx())
            )
        }

        // 绘制中心圆 - 带有发光效果
        drawCircle(
            color = color.copy(alpha = 0.15f),
            radius = 45.dp.toPx(),
            center = Offset(centerX, centerY)
        )

        drawCircle(
            color = color.copy(alpha = 0.25f),
            radius = 38.dp.toPx(),
            center = Offset(centerX, centerY)
        )

        drawCircle(
            color = color,
            radius = 32.dp.toPx(),
            center = Offset(centerX, centerY)
        )

        // 中心高光
        drawCircle(
            color = Color.White.copy(alpha = 0.3f),
            radius = 20.dp.toPx(),
            center = Offset(centerX, centerY)
        )
    }
}

/**
 * 空闲声波
 */
@Composable
fun IdleWaveform(
    color: Color,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "idle")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
    ) {
        val centerX = size.width / 2
        val centerY = size.height / 2

        // 绘制外层光晕
        drawCircle(
            color = color.copy(alpha = alpha * 0.5f),
            radius = 55.dp.toPx(),
            center = Offset(centerX, centerY)
        )

        // 绘制静态波纹圈
        drawCircle(
            color = color.copy(alpha = alpha),
            radius = 48.dp.toPx(),
            center = Offset(centerX, centerY),
            style = Stroke(width = 1.5.dp.toPx())
        )

        // 绘制中心圆
        drawCircle(
            color = color.copy(alpha = 0.15f),
            radius = 40.dp.toPx(),
            center = Offset(centerX, centerY)
        )

        drawCircle(
            color = color.copy(alpha = 0.25f),
            radius = 34.dp.toPx(),
            center = Offset(centerX, centerY)
        )

        drawCircle(
            color = color,
            radius = 28.dp.toPx(),
            center = Offset(centerX, centerY)
        )

        // 中心高光
        drawCircle(
            color = Color.White.copy(alpha = 0.2f),
            radius = 16.dp.toPx(),
            center = Offset(centerX, centerY)
        )
    }
}

/**
 * 条形声波可视化
 */
@Composable
fun BarWaveform(
    isAnimating: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    val colors = BaoziTheme.colors

    if (isAnimating) {
        AnimatedBarWaveform(
            color = color,
            modifier = modifier
        )
    } else {
        IdleBarWaveform(
            color = colors.textHint.copy(alpha = 0.3f),
            modifier = modifier
        )
    }
}

/**
 * 动画条形声波
 */
@Composable
fun AnimatedBarWaveform(
    color: Color,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "barWaveform")
    val barCount = 20
    
    val bars = (0 until barCount).map { index ->
        val phase = (index.toFloat() / barCount) * 2 * PI
        val height by infiniteTransition.animateFloat(
            initialValue = 0.2f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 800 + (index * 50),
                    easing = FastOutSlowInEasing
                ),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar_$index"
        )
        Pair(index, height)
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(100.dp)
    ) {
        val barWidth = size.width / barCount
        val maxBarHeight = size.height * 0.8f
        val centerY = size.height / 2

        bars.forEach { (index, height) ->
            val barHeight = maxBarHeight * height
            val x = index * barWidth
            val y = centerY - barHeight / 2

            drawRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(
                    width = barWidth * 0.6f,
                    height = barHeight
                )
            )
        }
    }
}

/**
 * 空闲条形声波
 */
@Composable
fun IdleBarWaveform(
    color: Color,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "idleBar")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "idleScale"
    )

    val barCount = 20

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(100.dp)
    ) {
        val barWidth = size.width / barCount
        val maxBarHeight = size.height * 0.8f
        val centerY = size.height / 2
        val barHeight = maxBarHeight * scale

        for (index in 0 until barCount) {
            val x = index * barWidth
            val y = centerY - barHeight / 2

            drawRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(
                    width = barWidth * 0.6f,
                    height = barHeight
                )
            )
        }
    }
}

/**
 * 正弦波声波可视化
 */
@Composable
fun SineWaveform(
    isAnimating: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    val colors = BaoziTheme.colors

    if (isAnimating) {
        AnimatedSineWaveform(
            color = color,
            modifier = modifier
        )
    } else {
        IdleSineWaveform(
            color = colors.textHint.copy(alpha = 0.3f),
            modifier = modifier
        )
    }
}

/**
 * 动画正弦波
 */
@Composable
fun AnimatedSineWaveform(
    color: Color,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "sineWave")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2 * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(100.dp)
    ) {
        val centerY = size.height / 2
        val amplitude = size.height * 0.3f
        val path = Path()

        val step = size.width / 100
        for (x in 0..100) {
            val xPos = x * step
            val yPos = centerY + amplitude * sin((x * 0.1f + phase).toDouble()).toFloat()
            
            if (x == 0) {
                path.moveTo(xPos, yPos)
            } else {
                path.lineTo(xPos, yPos)
            }
        }

        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
        )
    }
}

/**
 * 空闲正弦波
 */
@Composable
fun IdleSineWaveform(
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(100.dp)
    ) {
        val centerY = size.height / 2
        val amplitude = size.height * 0.1f
        val path = Path()

        val step = size.width / 100
        for (x in 0..100) {
            val xPos = x * step
            val yPos = centerY + amplitude * sin((x * 0.1f).toDouble()).toFloat()
            
            if (x == 0) {
                path.moveTo(xPos, yPos)
            } else {
                path.lineTo(xPos, yPos)
            }
        }

        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
        )
    }
}
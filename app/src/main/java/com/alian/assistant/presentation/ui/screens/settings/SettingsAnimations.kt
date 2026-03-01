package com.alian.assistant.presentation.ui.screens.settings

import androidx.compose.animation.core.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import kotlinx.coroutines.delay

/**
 * 交错淡入动画修饰符
 */
@Composable
fun Modifier.staggeredFadeIn(index: Int): Modifier = this then composed {
    var visible by remember {
        mutableStateOf(false)
    }
    val scaleAnimation = animateFloatAsState(
        targetValue = if (visible) 1f else 0.95f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "scale"
    )
    val alphaAnimation = animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "alpha"
    )
    LaunchedEffect(Unit) {
        delay(index * 50L)
        visible = true
    }
    alpha(alphaAnimation.value)
        .scale(scaleAnimation.value)
}
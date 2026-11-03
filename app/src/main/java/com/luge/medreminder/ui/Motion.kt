package com.luge.medreminder.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val Emphasized = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
val EmphasizedAccel = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
val EaseInOutSine = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)

/** Material fade-through with small direction-aware shared-axis offset (px). */
fun fadeThrough(dir: Int, offsetPx: Int, horizontal: Boolean = true): ContentTransform {
    val inOff: (Int) -> IntOffset = { if (horizontal) IntOffset(offsetPx * dir, 0) else IntOffset(0, offsetPx * dir) }
    val enter = fadeIn(tween(260, delayMillis = 60, easing = Emphasized)) +
        scaleIn(tween(300, delayMillis = 60, easing = Emphasized), initialScale = 0.98f) +
        slideIn(tween(300, delayMillis = 60, easing = Emphasized)) { inOff(it.width) }
    val exit = fadeOut(tween(100, easing = EmphasizedAccel)) + scaleOut(tween(100, easing = EmphasizedAccel), targetScale = 0.98f)
    return enter togetherWith exit
}

@Composable
fun dpPx(v: Int) = with(LocalDensity.current) { v.dp.roundToPx() }

/** Keys that already played their entrance; prevents replay on tab return/recomposition. */
object SeenEntrance { val keys = mutableSetOf<String>() }

/** Soft staggered entrance: fade + 10dp rise with spring; plays only once per key. */
@Composable
fun Modifier.entrance(key: String, index: Int): Modifier {
    val first = remember(key) { SeenEntrance.keys.add(key) }
    val alpha = remember(key) { Animatable(if (first) 0f else 1f) }
    val ty = remember(key) { Animatable(if (first) 1f else 0f) }
    val px = with(LocalDensity.current) { 10.dp.toPx() }
    if (first) LaunchedEffect(key) {
        delay(35L * index)
        kotlinx.coroutines.coroutineScope {
            launch { alpha.animateTo(1f, tween(320, easing = Standard)) }
            launch { ty.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow)) }
        }
    }
    return graphicsLayer { this.alpha = alpha.value; translationY = ty.value * px }
}


/** Counter that cross-fades with a slight vertical slide when it changes. */
@Composable
fun <T> AnimatedValue(value: T, content: @Composable (T) -> Unit) {
    AnimatedContent(value, label = "counter", transitionSpec = {
        (fadeIn(tween(240, easing = Emphasized)) + slideInVertically(tween(300, easing = Emphasized)) { it / 3 }) togetherWith
            (fadeOut(tween(120, easing = EmphasizedAccel)) + slideOutVertically(tween(160, easing = EmphasizedAccel)) { -it / 3 }) using SizeTransform(clip = false)
    }) { content(it) }
}

package com.ajay.storyboard.ui.common

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

/** Click / long-press with a small spring "press" (scales down a little, or lifts when [lift] > 0). */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.pressable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    pressedScale: Float = 0.96f,
    lift: Float = 0f,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium), label = "press")
    val dy by animateFloatAsState(if (pressed) -lift else 0f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium), label = "lift")
    this
        .graphicsLayer { scaleX = scale; scaleY = scale; translationY = dy * density }
        .combinedClickable(interactionSource = source, indication = LocalIndication.current, onClick = onClick, onLongClick = onLongClick)
}


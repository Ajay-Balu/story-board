package com.ajay.storyboard.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/*
 * Material buttons are pills whatever the theme says. The Almanac theme gives every control a 6px
 * radius, so these wrappers keep Material's buttons (states, ripple, accessibility) with that shape.
 * Same names and parameters as androidx.compose.material3; files import these instead.
 */

val ControlShape: RoundedCornerShape = RoundedCornerShape(6.dp)

@Composable
fun Button(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape = ControlShape,
    colors: ButtonColors = ButtonDefaults.buttonColors(), elevation: ButtonElevation? = null, border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding, interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.Button(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)

@Composable
fun OutlinedButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape = ControlShape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = BorderStroke(1.dp, if (enabled) androidx.compose.material3.MaterialTheme.colorScheme.onSurface
        else androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding, interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.OutlinedButton(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)

@Composable
fun TextButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape = ControlShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface),
    elevation: ButtonElevation? = null, border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding, interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.TextButton(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)

@Composable
fun FilledTonalButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape = ControlShape,
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(), elevation: ButtonElevation? = null, border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding, interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.FilledTonalButton(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)

/* Flat floating buttons: no shadow (depth is a rule and a paper step), the magenta fill marks the primary action. */

@Composable
fun ExtendedFloatingActionButton(
    onClick: () -> Unit, icon: @Composable () -> Unit, text: @Composable () -> Unit, modifier: Modifier = Modifier, expanded: Boolean = true,
) = androidx.compose.material3.ExtendedFloatingActionButton(
    text = text, icon = icon, onClick = onClick, modifier = modifier, expanded = expanded, shape = ControlShape,
    containerColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
    contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
    elevation = androidx.compose.material3.FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
)

@Composable
fun FloatingActionButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    androidx.compose.material3.FloatingActionButton(
        onClick = onClick, modifier = modifier, shape = ControlShape,
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
        contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
        elevation = androidx.compose.material3.FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
        content = content,
    )

@Composable
fun SmallFloatingActionButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    androidx.compose.material3.SmallFloatingActionButton(
        onClick = onClick, modifier = modifier, shape = ControlShape,
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
        contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
        elevation = androidx.compose.material3.FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
        content = content,
    )

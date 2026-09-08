package com.nuvio.tv.ui.screens.calendar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioTheme

private val IconButtonSize = 40.dp
private const val BUTTON_FOCUSED_SCALE = 1.05f

/** Round icon-only button used for month navigation. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun CalendarIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = CircleShape
            )
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = BUTTON_FOCUSED_SCALE),
        modifier = modifier.size(IconButtonSize)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = NuvioTheme.colors.TextPrimary,
            modifier = Modifier
                .fillMaxSize()
                .padding(NuvioTheme.spacing.xs)
        )
    }
}

/** Pill-shaped text button ("Today", "Next release"). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun CalendarPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(NuvioTheme.radii.lg)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                shape = shape
            ),
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = shape
            )
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = BUTTON_FOCUSED_SCALE),
        modifier = modifier
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = NuvioTheme.colors.TextPrimary,
            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.sm)
        )
    }
}

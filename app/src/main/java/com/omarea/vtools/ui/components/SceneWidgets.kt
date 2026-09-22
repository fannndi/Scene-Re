package com.omarea.vtools.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omarea.vtools.ui.theme.SceneSpacing

/**
 * Additional Material 3 building blocks for Scene feature screens.
 *
 * These complement [SceneCard] / [SceneNavCard] / [SceneSectionHeader] / [SceneKeyValueRow] and are
 * intentionally small: colour comes from the theme, spacing from [SceneSpacing], and no screen
 * should need to hand-style a card or a chip.
 */

/** Semantic accent used by chips, icon wells, bars and gauges. */
enum class SceneTone {
    PRIMARY,
    SECONDARY,
    TERTIARY,
    WARNING,
    ERROR,
    NEUTRAL
}

/** Container/content colour pair for a [SceneTone]. */
@Composable
fun sceneToneColors(tone: SceneTone): Pair<Color, Color> {
    return when (tone) {
        SceneTone.PRIMARY -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        SceneTone.SECONDARY -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        SceneTone.TERTIARY -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        SceneTone.WARNING -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        SceneTone.ERROR -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        SceneTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/** Strong colour used for progress fills. */
@Composable
fun sceneToneColor(tone: SceneTone): Color {
    return when (tone) {
        SceneTone.PRIMARY -> MaterialTheme.colorScheme.primary
        SceneTone.SECONDARY -> MaterialTheme.colorScheme.secondary
        SceneTone.TERTIARY -> MaterialTheme.colorScheme.tertiary
        SceneTone.WARNING -> MaterialTheme.colorScheme.tertiary
        SceneTone.ERROR -> MaterialTheme.colorScheme.error
        SceneTone.NEUTRAL -> MaterialTheme.colorScheme.outline
    }
}

/** Compact status chip, e.g. "Root required" or "Active". */
@Composable
fun SceneChip(
    label: String,
    tone: SceneTone = SceneTone.NEUTRAL,
    modifier: Modifier = Modifier
) {
    val (container, content) = sceneToneColors(tone)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = container,
        contentColor = content
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = SceneSpacing.sm, vertical = SceneSpacing.xs / 2)
        )
    }
}

/** Circular tinted icon container used in card headers. */
@Composable
fun SceneIconWell(
    iconRes: Int,
    tone: SceneTone = SceneTone.PRIMARY,
    size: Int = 34,
    modifier: Modifier = Modifier
) {
    val (container, content) = sceneToneColors(tone)
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(container),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = content,
            modifier = Modifier.size((size * 0.55f).dp)
        )
    }
}

/** Titled card with an optional icon well and trailing slot. */
@Composable
fun SceneSectionCard(
    title: String,
    iconRes: Int? = null,
    accent: SceneTone = SceneTone.PRIMARY,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(SceneSpacing.md),
            verticalArrangement = Arrangement.spacedBy(SceneSpacing.sm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (iconRes != null) {
                    SceneIconWell(iconRes = iconRes, tone = accent)
                    Spacer(modifier = Modifier.width(SceneSpacing.sm))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                trailing?.invoke()
            }
            content()
        }
    }
}

/** Big-number card: label, value with optional unit, optional caption. */
@Composable
fun SceneMetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    caption: String? = null,
    tone: SceneTone = SceneTone.PRIMARY
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(SceneSpacing.md),
            verticalArrangement = Arrangement.spacedBy(SceneSpacing.xs)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge,
                    color = sceneToneColor(tone)
                )
                if (unit != null) {
                    Spacer(modifier = Modifier.width(SceneSpacing.xs))
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (caption != null) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Thin horizontal progress bar. [fraction] is clamped to 0..1. */
@Composable
fun SceneBar(
    fraction: Float,
    tone: SceneTone = SceneTone.PRIMARY,
    modifier: Modifier = Modifier
) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 250),
        label = "SceneBar"
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animated)
                .fillMaxHeight()
                .background(sceneToneColor(tone))
        )
    }
}

/** Ring gauge with the value rendered in the middle. */
@Composable
fun SceneGauge(
    fraction: Float,
    valueText: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    tone: SceneTone = SceneTone.PRIMARY
) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 350),
        label = "SceneGauge"
    )
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val indicatorColor = sceneToneColor(tone)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = size.minDimension * 0.10f
            val inset = strokeWidth / 2f
            val arcSize = Size(size.minDimension - strokeWidth, size.minDimension - strokeWidth)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = trackColor,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            drawArc(
                color = indicatorColor,
                startAngle = 135f,
                sweepAngle = 270f * animated,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = valueText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Generic list row: title, optional summary and a trailing slot.
 *
 * Used for read-only key/value style lists where the value is not a plain string (a switch, a chip,
 * a chevron). For simple text pairs prefer [SceneKeyValueRow].
 */
@Composable
fun SceneListItem(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val rowModifier = modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(SceneSpacing.sm))
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        .padding(horizontal = SceneSpacing.md, vertical = SceneSpacing.sm)
    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(SceneSpacing.xs / 2)
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            if (summary != null) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        trailing?.invoke()
    }
}

/** Centered placeholder shown when a list has nothing to display. */
@Composable
fun SceneEmptyState(
    title: String,
    description: String? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(SceneSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SceneSpacing.sm)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Animated placeholder used while data is being read off the main thread. */
@Composable
fun SceneSkeleton(
    modifier: Modifier = Modifier,
    height: Int = 18,
    cornerRadius: Int = 6
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(RoundedCornerShape(cornerRadius.dp))
            .shimmerEffect()
    )
}

/**
 * Shimmer brush for skeleton placeholders, adapted from RvKernel-Manager (GPL-3.0, Rve27).
 *
 * The gradient sweeps along the X axis using theme colours, so it works in light and dark mode.
 */
fun Modifier.shimmerEffect(
    widthOfShadowBrush: Int = 500,
    angleOfAxisY: Float = 270f,
    durationMillis: Int = 1000
): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "Shimmer")
    val translateAnimation = transition.animateFloat(
        initialValue = 0f,
        targetValue = (durationMillis + widthOfShadowBrush).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Shimmer animation"
    ).value

    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val shimmerColors = listOf(
        onSurfaceVariant.copy(alpha = 0.10f),
        onSurfaceVariant.copy(alpha = 0.25f),
        onSurfaceVariant.copy(alpha = 0.10f)
    )

    background(
        brush = Brush.linearGradient(
            colors = shimmerColors,
            start = Offset(x = translateAnimation - widthOfShadowBrush, y = 0f),
            end = Offset(x = translateAnimation, y = angleOfAxisY)
        )
    )
}

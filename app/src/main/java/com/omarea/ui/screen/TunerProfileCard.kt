package com.omarea.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.omarea.engine.ProfileKey
import com.omarea.ui.theme.SceneDimens
import com.omarea.ui.theme.ScenePalette
import com.omarea.vtools.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * One editable profile rendered as a full row on the Tuner profile card.
 * Tap applies the profile (engine ON) or opens its editor (engine OFF).
 */
data class ProfileRowState(
    val mode: String,
    val title: String,
    val summary: String,
    val active: Boolean,
    val modified: Boolean
)

/**
 * State of the Tuner "Profile" card: master switch, TRUE OFF and the four
 * profile rows. Built by [FragmentCpuModes] off the main thread.
 *
 * [engineEnabled] is false in Monitor mode (no root): the switch is locked and
 * [engineNote] explains why.
 */
data class TunerProfileCardState(
    val engineOn: Boolean = true,
    val engineEnabled: Boolean = true,
    val engineNote: String = "",
    val trueOff: Boolean = false,
    val sourceLabel: String = "",
    val profiles: List<ProfileRowState> = emptyList()
)

@Composable
internal fun TunerProfileCard(
    state: TunerProfileCardState,
    applyingMode: String? = null,
    applyResult: String = "",
    onEngineToggle: (Boolean) -> Unit,
    onTrueOffToggle: (Boolean) -> Unit,
    onProfileClick: (String) -> Unit,
    onProfileEdit: (String) -> Unit,
    onSourceClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = SceneDimens.cardRadius,
        insideMargin = PaddingValues(SceneDimens.cardPadding),
        colors = CardDefaults.defaultColors()
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.profile_title),
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(SceneDimens.spaceXs))
                    Text(
                        text = state.sourceLabel,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onSourceClick)
                    )
                    if (state.engineNote.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(SceneDimens.spaceXs))
                        Text(
                            text = state.engineNote,
                            style = MiuixTheme.textStyles.footnote2,
                            color = ScenePalette.amber
                        )
                    }
                }
                Spacer(modifier = Modifier.width(SceneDimens.spaceM))
                Switch(
                    checked = state.engineOn,
                    enabled = state.engineEnabled,
                    onCheckedChange = onEngineToggle
                )
            }

            Spacer(modifier = Modifier.height(SceneDimens.spaceS))

            state.profiles.forEach { profile ->
                ProfileCardRow(
                    profile,
                    applying = applyingMode == profile.mode,
                    onClick = { onProfileClick(profile.mode) },
                    onEdit = { onProfileEdit(profile.mode) }
                )
            }

            if (applyResult.isNotEmpty()) {
                Spacer(modifier = Modifier.height(SceneDimens.spaceXs))
                Text(
                    text = applyResult,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant
                )
            }

            Spacer(modifier = Modifier.height(SceneDimens.spaceS))

            // TRUE OFF lives in the same card as the profile master switch:
            // one place that answers "what may Scene do right now".
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.true_off_title),
                        style = MiuixTheme.textStyles.footnote1,
                        color = if (state.trueOff) MiuixTheme.colorScheme.onSurface
                        else MiuixTheme.colorScheme.onSurfaceContainerVariant
                    )
                    Spacer(modifier = Modifier.height(SceneDimens.spaceXs))
                    Text(
                        text = stringResource(
                            if (state.trueOff) R.string.true_off_desc_on else R.string.true_off_desc_off
                        ),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant
                    )
                }
                Spacer(modifier = Modifier.width(SceneDimens.spaceM))
                Switch(checked = state.trueOff, onCheckedChange = onTrueOffToggle)
            }
        }
    }
}

@Composable
private fun ProfileCardRow(
    state: ProfileRowState,
    applying: Boolean = false,
    onClick: () -> Unit,
    onEdit: () -> Unit = {}
) {
    val visual = modeVisual(state.mode)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SceneDimens.rowMinHeight)
            .clickable(enabled = !applying, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(visual.icon),
            contentDescription = null,
            tint = visual.tint,
            modifier = Modifier.size(SceneDimens.iconSize)
        )
        Spacer(modifier = Modifier.width(SceneDimens.iconGap))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.title,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurface
            )
            // In-flight apply replaces the summary so the row reads as busy.
            val summary = if (applying) {
                stringResource(R.string.profile_apply_running)
            } else {
                state.summary
            }
            if (summary.isNotEmpty()) {
                Spacer(modifier = Modifier.height(SceneDimens.spaceXs))
                Text(
                    text = summary,
                    style = MiuixTheme.textStyles.footnote2,
                    color = if (applying) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.onSurfaceContainerVariant
                )
            }
        }
        if (applying) return@ProfileCardRow
        // Explicit second affordance: the row applies, this opens the editor
        // (the old behaviour switched meaning with hidden engine state).
        Text(
            text = stringResource(R.string.profile_edit),
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.10f))
                .clickable(onClick = onEdit)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
        Spacer(modifier = Modifier.width(SceneDimens.spaceS))
        if (state.modified) {
            Text(
                text = stringResource(R.string.profile_modified),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier
                    .background(
                        color = MiuixTheme.colorScheme.primary.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
        if (state.active) {
            Spacer(modifier = Modifier.width(SceneDimens.spaceS))
            Icon(
                painter = painterResource(R.drawable.check),
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(SceneDimens.iconSize)
            )
        }
    }
}

private data class ModeVisual(val icon: Int, val tint: Color)

@Composable
private fun modeVisual(mode: String): ModeVisual = when (mode) {
    ProfileKey.POWERSAVE -> ModeVisual(R.drawable.mode_powersave, ScenePalette.blue)
    ProfileKey.BALANCE -> ModeVisual(R.drawable.mode_balance, ScenePalette.green)
    ProfileKey.PERFORMANCE -> ModeVisual(R.drawable.mode_performance, ScenePalette.lime)
    else -> ModeVisual(R.drawable.mode_fast, ScenePalette.amber)
}

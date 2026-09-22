package com.omarea.vtools.kernel

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.omarea.vtools.R
import com.omarea.vtools.ui.components.SceneChip
import com.omarea.vtools.ui.components.SceneSectionCard
import com.omarea.vtools.ui.components.SceneTone
import com.omarea.vtools.ui.theme.SceneSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Profiles tab: applies the bundled `assets/kernel-profiles` scripts.
 *
 * The script is copied to private storage and executed through the active shell backend, which
 * needs root for the writes it performs; without root the buttons are disabled and a chip explains
 * why. The last applied profile is remembered in SharedPreferences.
 */
@Composable
internal fun KernelProfilesScreen(refreshKey: Int, hasRoot: Boolean, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf<KernelProfile?>(null) }
    var applying by remember { mutableStateOf<KernelProfile?>(null) }

    val appliedMessage = stringResource(R.string.kernel_profiles_applied)
    val failedMessage = stringResource(R.string.kernel_profiles_failed)

    LaunchedEffect(refreshKey) {
        current = withContext(Dispatchers.IO) { KernelProfiles.current(context) }
    }

    fun apply(profile: KernelProfile) {
        scope.launch {
            applying = profile
            val ok = withContext(Dispatchers.IO) { KernelProfiles.apply(context, profile) }
            applying = null
            if (ok) {
                current = profile
                onMessage(appliedMessage)
            } else {
                onMessage(failedMessage)
            }
        }
    }

    KernelScreen {
        SceneSectionCard(
            title = stringResource(R.string.kernel_section_profiles),
            iconRes = R.drawable.ic_menu_vboot,
            accent = SceneTone.TERTIARY,
            trailing = { if (!hasRoot) KernelRootRequiredChip() }
        ) {
            Text(
                text = stringResource(R.string.kernel_profiles_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.kernel_profiles_scene_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            KernelProfile.values().forEach { profile ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = profileTitle(profile),
                                style = MaterialTheme.typography.titleSmall
                            )
                            if (profile == current) {
                                Spacer(modifier = Modifier.width(SceneSpacing.sm))
                                SceneChip(
                                    label = stringResource(R.string.kernel_profiles_current),
                                    tone = SceneTone.PRIMARY
                                )
                            }
                        }
                        Text(
                            text = profileDescription(profile),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        onClick = { apply(profile) },
                        enabled = hasRoot && applying == null
                    ) {
                        Text(
                            if (applying == profile) {
                                stringResource(R.string.kernel_profiles_applying)
                            } else {
                                stringResource(R.string.kernel_profiles_apply)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun profileTitle(profile: KernelProfile): String {
    return stringResource(
        when (profile) {
            KernelProfile.POWERSAVE -> R.string.kernel_profile_powersave
            KernelProfile.BALANCE -> R.string.kernel_profile_balance
            KernelProfile.PERFORMANCE -> R.string.kernel_profile_performance
        }
    )
}

@Composable
private fun profileDescription(profile: KernelProfile): String {
    return stringResource(
        when (profile) {
            KernelProfile.POWERSAVE -> R.string.kernel_profile_powersave_desc
            KernelProfile.BALANCE -> R.string.kernel_profile_balance_desc
            KernelProfile.PERFORMANCE -> R.string.kernel_profile_performance_desc
        }
    )
}

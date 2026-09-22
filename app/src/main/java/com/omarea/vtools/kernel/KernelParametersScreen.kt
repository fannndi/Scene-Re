package com.omarea.vtools.kernel

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.omarea.library.shell.KernelParameter
import com.omarea.library.shell.KernelParameters
import com.omarea.library.shell.KernelParamType
import com.omarea.vtools.R
import com.omarea.vtools.ui.components.SceneEmptyState
import com.omarea.vtools.ui.components.SceneSectionCard
import com.omarea.vtools.ui.theme.SceneSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Parameters tab: the [KernelParameters] registry, filtered to what this kernel actually exposes.
 *
 * BOOL rows are switches (0/1), INT/TEXT rows open an input dialog, READ_ONLY rows only display the
 * value. [KernelParameters.write] verifies each write by reading the node back, so a refused write
 * surfaces as a snackbar instead of a silent no-op.
 */
@Composable
internal fun KernelParametersScreen(refreshKey: Int, hasRoot: Boolean, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var parameters by remember { mutableStateOf<List<KernelParameter>>(emptyList()) }
    var values by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var reloadTrigger by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<KernelParameter?>(null) }

    val writeFailed = stringResource(R.string.kernel_write_failed)

    LaunchedEffect(refreshKey, reloadTrigger) {
        loading = true
        withContext(Dispatchers.IO) {
            val currentValues = KernelParameters.readAll()
            values = currentValues
            parameters = KernelParameters.all.filter { currentValues.containsKey(it.path) }
        }
        loading = false
    }

    fun write(path: String, value: String) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { KernelParameters.write(path, value) }
            if (ok) {
                reloadTrigger++
            } else {
                onMessage(writeFailed)
            }
        }
    }

    KernelScreen {
        if (loading && parameters.isEmpty()) {
            KernelLoading()
            return@KernelScreen
        }
        if (parameters.isEmpty()) {
            SceneEmptyState(
                title = stringResource(R.string.kernel_parameters_unavailable),
                description = stringResource(R.string.kernel_parameters_unavailable_desc)
            )
            return@KernelScreen
        }

        SceneSectionCard(
            title = stringResource(R.string.kernel_section_parameters),
            iconRes = R.drawable.settings,
            trailing = { if (!hasRoot) KernelRootRequiredChip() }
        ) {
            parameters.forEachIndexed { index, parameter ->
                if (index > 0) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = SceneSpacing.xs))
                }
                val value = values[parameter.path].orEmpty()
                when (parameter.type) {
                    KernelParamType.BOOL -> KernelSwitchRow(
                        title = parameter.label,
                        summary = value,
                        checked = value == "1",
                        enabled = hasRoot,
                        onCheckedChange = { enabled ->
                            write(parameter.path, if (enabled) "1" else "0")
                        }
                    )

                    KernelParamType.READ_ONLY -> KernelInfoRow(parameter.label, value)

                    else -> KernelActionRow(
                        title = parameter.label,
                        value = value.ifBlank { stringResource(R.string.kernel_value_unavailable) },
                        enabled = hasRoot,
                        onClick = { editing = parameter }
                    )
                }
            }
        }
    }

    editing?.let { parameter ->
        val numericOnly = parameter.type == KernelParamType.INT
        KernelInputDialog(
            title = parameter.label,
            initialValue = values[parameter.path].orEmpty(),
            numericOnly = numericOnly,
            isValid = { value ->
                if (numericOnly) {
                    value.toIntOrNull() != null
                } else {
                    KernelShell.isSafeTextValue(value)
                }
            },
            onDismiss = { editing = null },
            onConfirm = { value -> write(parameter.path, value) }
        )
    }
}

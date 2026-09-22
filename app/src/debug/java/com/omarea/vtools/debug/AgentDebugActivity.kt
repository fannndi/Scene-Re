package com.omarea.vtools.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import com.omarea.vtools.activities.ActivityBase
import com.omarea.vtools.ui.components.SceneCard
import com.omarea.vtools.ui.theme.SceneTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Debug-only diagnostics screen for AI agents and developers.
 *
 * Launch over USB:
 *   adb shell am start -n com.omarea.vtools/.debug.AgentDebugActivity
 *
 * The screen renders a JSON capability snapshot, can persist it to
 * `/sdcard/Android/data/com.omarea.vtools/files/agent-debug-snapshot.json`
 * and can dump the current logcat buffer.
 */
class AgentDebugActivity : ActivityBase() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val composeView = ComposeView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }
        setContentView(composeView)
        AgentDebug.log("AgentDebugActivity opened")
        composeView.setContent {
            SceneTheme(mode = themeMode) {
                AgentDebugScreen()
            }
        }
    }

    @Composable
    private fun AgentDebugScreen() {
        val context = this
        var snapshot by remember { mutableStateOf("") }
        var logcat by remember { mutableStateOf("") }
        var outputPath by remember { mutableStateOf("") }
        var reloadKey by remember { mutableIntStateOf(0) }

        LaunchedEffect(reloadKey) {
            snapshot = withContext(Dispatchers.IO) {
                val json = AgentDebug.buildSnapshot(context)
                // Persist automatically so agents can read it over adb without touching the UI.
                outputPath = AgentDebug.writeSnapshot(context, json)
                json
            }
            AgentDebug.log("snapshot refreshed")
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = "Agent Debug",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Debug-only diagnostics for AI agents. No system state is modified.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { reloadKey++ }) {
                    Text("Refresh")
                }
                OutlinedButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("SceneAgentSnapshot", snapshot))
                    Toast.makeText(context, "Snapshot copied", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Copy JSON")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val path = AgentDebug.writeSnapshot(context, snapshot)
                    outputPath = path
                    AgentDebug.log("snapshot written", path)
                    Toast.makeText(context, "Written to $path", Toast.LENGTH_LONG).show()
                }) {
                    Text("Write file")
                }
                OutlinedButton(onClick = {
                    logcat = ""
                    AgentDebug.log("logcat dump requested")
                }) {
                    Text("Clear log")
                }
                Button(onClick = {
                    logcat = AgentDebug.dumpLogcat()
                }) {
                    Text("Dump logcat")
                }
            }
            if (outputPath.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Snapshot file: $outputPath",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            SceneCard(modifier = Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Text(
                        text = snapshot,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            if (logcat.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "logcat",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                SceneCard(modifier = Modifier.fillMaxWidth()) {
                    SelectionContainer {
                        Text(
                            text = logcat,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

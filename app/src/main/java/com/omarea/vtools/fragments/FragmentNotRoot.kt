package com.omarea.vtools.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.omarea.common.ui.ThemeMode
import com.omarea.permissions.CheckRootStatus
import com.omarea.vtools.R
import com.omarea.vtools.activities.ActivityBase
import com.omarea.vtools.activities.ActivityPrivilege
import com.omarea.vtools.databinding.FragmentNotRootBinding
import com.omarea.vtools.privilege.PrivilegeManager
import com.omarea.vtools.privilege.PrivilegeTier
import com.omarea.vtools.ui.theme.SceneSpacing
import com.omarea.vtools.ui.theme.SceneTheme

/**
 * Shown on the Overview tab when no privileged access is available.
 * Offers a retry for the root tier and a shortcut to the privilege mode selector.
 */
class FragmentNotRoot : Fragment() {
    private var _binding: FragmentNotRootBinding? = null
    private val binding get() = _binding!!
    private lateinit var themeMode: ThemeMode

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        _binding = FragmentNotRootBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!::themeMode.isInitialized) {
            themeMode = (activity as? ActivityBase)?.themeMode ?: ThemeMode()
        }
        binding.composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        binding.composeView.setContent {
            SceneTheme(mode = themeMode) {
                NotRootScreen(
                    canRetryRoot = PrivilegeManager.tier == PrivilegeTier.ROOT,
                    onRetry = { retryRoot() },
                    onOpenPrivilege = {
                        startActivity(Intent(context, ActivityPrivilege::class.java))
                    }
                )
            }
        }
    }

    private fun retryRoot() {
        CheckRootStatus(this.context!!, Runnable {
            if (this.activity != null) {
                this.activity!!.recreate()
            }
        }, false, null).forceGetRoot()
    }

    @Composable
    private fun NotRootScreen(
        canRetryRoot: Boolean,
        onRetry: () -> Unit,
        onOpenPrivilege: () -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(SceneSpacing.lg),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.not_root_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(SceneSpacing.sm))
            Text(
                text = stringResource(R.string.not_root_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(SceneSpacing.lg))
            Button(
                onClick = onOpenPrivilege,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.not_root_open_privilege))
            }
            if (canRetryRoot) {
                Spacer(modifier = Modifier.height(SceneSpacing.sm))
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.btn_retry))
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun createPage(): androidx.fragment.app.Fragment {
            val fragment = FragmentNotRoot()
            return fragment
        }
    }
}

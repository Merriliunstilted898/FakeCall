package com.upnp.fakeCall.ui.screens

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.upnp.fakeCall.FakeCallViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: FakeCallViewModel,
    onBack: () -> Unit,
    onRequestPermissions: () -> Unit
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        viewModel.onAudioFileSelected(uri)
    }

    val legacyStoragePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            audioPickerLauncher.launch("audio/*")
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            LargeTopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors()
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            if (!state.hasRequiredPermissions) {
                Text(
                    text = "Phone permissions are required for Telecom registration.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge
                )
                Button(onClick = onRequestPermissions) {
                    Text("Grant Phone Permissions")
                }
            }

            OutlinedTextField(
                value = state.providerName,
                onValueChange = viewModel::onProviderNameChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Provider Name") },
                singleLine = true
            )

            Button(onClick = viewModel::saveProvider) {
                Text("Save & Register Provider")
            }

            Button(onClick = { openCallingAccounts(context, viewModel) }) {
                Text("Enable Provider in System")
            }

            Text(
                text = if (state.isProviderEnabled) {
                    "Provider is enabled in Calling Accounts."
                } else {
                    "Provider is not enabled in Calling Accounts yet."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = if (state.isProviderEnabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )

            Text(
                text = "Call Audio",
                style = MaterialTheme.typography.titleLarge
            )

            Text(
                text = "Selected: ${state.selectedAudioName.ifBlank { "Default" }}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(onClick = {
                if (needsLegacyStoragePermission(context)) {
                    legacyStoragePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                } else {
                    audioPickerLauncher.launch("audio/*")
                }
            }) {
                Text("Select Audio File")
            }

            Button(onClick = viewModel::clearAudioSelection) {
                Text("No Audio File")
            }

            Text(
                text = "Microphone Recording",
                style = MaterialTheme.typography.titleLarge
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (state.isRecordingEnabled) "Enabled" else "Disabled",
                    style = MaterialTheme.typography.bodyLarge
                )
                Switch(
                    checked = state.isRecordingEnabled,
                    onCheckedChange = viewModel::onRecordingEnabledChange
                )
            }

            if (state.statusMessage.isNotBlank()) {
                Text(
                    text = state.statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun needsLegacyStoragePermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return false
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.READ_EXTERNAL_STORAGE
    ) != PackageManager.PERMISSION_GRANTED
}

private fun openCallingAccounts(context: Context, viewModel: FakeCallViewModel) {
    val intent = viewModel.openCallingAccountsIntent()
    runCatching {
        context.startActivity(intent)
    }.onFailure {
        if (it is ActivityNotFoundException) {
            // Ignore silently on unsupported devices.
        }
    }
}

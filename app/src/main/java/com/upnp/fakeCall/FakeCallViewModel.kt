package com.upnp.fakeCall

import android.app.Application
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.telecom.TelecomManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class FakeCallUiState(
    val providerName: String = "Fake Call Provider",
    val callerName: String = "",
    val callerNumber: String = "",
    val selectedDelaySeconds: Int = 10,
    val selectedAudioUri: String = "",
    val selectedAudioName: String = "Default",
    val hasRequiredPermissions: Boolean = false,
    val isProviderEnabled: Boolean = false,
    val isTimerRunning: Boolean = false,
    val timerEndsAtMillis: Long = 0L,
    val statusMessage: String = "",
    val isRecordingEnabled: Boolean = true,
    val recordingsFolderName: String = "Downloads/FakeCall"
)

class FakeCallViewModel(application: Application) : AndroidViewModel(application) {

    private val telecomHelper = TelecomHelper(application)
    private val prefs = application.getSharedPreferences(PREFS_NAME, 0)

    private val _uiState = MutableStateFlow(
        FakeCallUiState(
            providerName = prefs.getString(KEY_PROVIDER_NAME, "Fake Call Provider").orEmpty(),
            callerName = prefs.getString(KEY_CALLER_NAME, "").orEmpty(),
            callerNumber = prefs.getString(KEY_CALLER_NUMBER, "").orEmpty(),
            selectedDelaySeconds = prefs.getInt(KEY_DELAY_SECONDS, 10),
            selectedAudioUri = prefs.getString(KEY_AUDIO_URI, "").orEmpty(),
            selectedAudioName = prefs.getString(KEY_AUDIO_NAME, "Default").orEmpty(),
            timerEndsAtMillis = prefs.getLong(KEY_TIMER_ENDS_AT, 0L),
            isRecordingEnabled = prefs.getBoolean(KEY_RECORDING_ENABLED, true),
            recordingsFolderName = prefs.getString(KEY_RECORDINGS_FOLDER_NAME, "Downloads/FakeCall").orEmpty()
        )
    )
    val uiState: StateFlow<FakeCallUiState> = _uiState.asStateFlow()

    val delayOptionsSeconds: List<Int> = listOf(0, 10, 30, 60, 120, 300)

    init {
        viewModelScope.launch {
            while (isActive) {
                syncRunningTimerState()
                if (uiState.value.hasRequiredPermissions) {
                    refreshProviderStatus()
                }
                delay(1_000L)
            }
        }
    }

    fun onPermissionStateChanged(granted: Boolean) {
        _uiState.update {
            it.copy(
                hasRequiredPermissions = granted,
                isProviderEnabled = if (granted) it.isProviderEnabled else false,
                statusMessage = if (granted) it.statusMessage else "Grant phone permissions to continue."
            )
        }

        if (granted) {
            ensurePhoneAccountRegistered()
            refreshProviderStatus()
        }
    }

    fun onProviderNameChange(value: String) {
        _uiState.update { it.copy(providerName = value) }
    }

    fun onCallerNameChange(value: String) {
        _uiState.update { it.copy(callerName = value) }
    }

    fun onCallerNumberChange(value: String) {
        _uiState.update { it.copy(callerNumber = value) }
    }

    fun onDelaySelected(delaySeconds: Int) {
        _uiState.update { it.copy(selectedDelaySeconds = delaySeconds) }
        prefs.edit().putInt(KEY_DELAY_SECONDS, delaySeconds).apply()
    }

    fun onAudioFileSelected(uri: Uri?) {
        if (uri == null) {
            return
        }

        val app = getApplication<Application>()
        val resolver = app.contentResolver

        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val displayName = runCatching {
            resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                }
        }.getOrNull() ?: fallbackNameFromUri(uri)

        prefs.edit()
            .putString(KEY_AUDIO_URI, uri.toString())
            .putString(KEY_AUDIO_NAME, displayName)
            .apply()

        _uiState.update {
            it.copy(
                selectedAudioUri = uri.toString(),
                selectedAudioName = displayName,
                statusMessage = "Selected audio file: $displayName"
            )
        }
    }

    fun clearAudioSelection() {
        prefs.edit()
            .remove(KEY_AUDIO_URI)
            .putString(KEY_AUDIO_NAME, "Default")
            .apply()

        _uiState.update {
            it.copy(
                selectedAudioUri = "",
                selectedAudioName = "Default",
                statusMessage = "Disabling audio output on Call."
            )
        }
    }


    fun onRecordingEnabledChange(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_RECORDING_ENABLED, enabled).apply()
        _uiState.update {
            it.copy(
                isRecordingEnabled = enabled,
                statusMessage = if (enabled) "Call recording enabled." else "Call recording disabled."
            )
        }
    }

    fun onRecordingFolderSelected(uri: Uri?) {
        if (uri == null) return

        val app = getApplication<Application>()
        val resolver = app.contentResolver

        runCatching {
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }

        val folderName = runCatching { readableTreeLabel(uri) }.getOrDefault("Selected folder")

        prefs.edit()
            .putString(KEY_RECORDINGS_TREE_URI, uri.toString())
            .putString(KEY_RECORDINGS_FOLDER_NAME, folderName)
            .apply()

        _uiState.update {
            it.copy(
                recordingsFolderName = folderName,
                statusMessage = "Recording folder set to: $folderName"
            )
        }
    }

    fun clearRecordingFolderSelection() {
        prefs.edit()
            .remove(KEY_RECORDINGS_TREE_URI)
            .putString(KEY_RECORDINGS_FOLDER_NAME, "Downloads/FakeCall")
            .apply()

        _uiState.update {
            it.copy(
                recordingsFolderName = "Downloads/FakeCall",
                statusMessage = "Recording folder reset to Downloads/FakeCall."
            )
        }
    }

    fun saveProvider() {
        if (!uiState.value.hasRequiredPermissions) {
            _uiState.update { it.copy(statusMessage = "Grant phone permissions first.") }
            return
        }

        val providerName = uiState.value.providerName.trim().ifBlank { "Fake Call Provider" }
        val registered = telecomHelper.registerOrUpdatePhoneAccount(providerName)

        prefs.edit().putString(KEY_PROVIDER_NAME, providerName).apply()

        _uiState.update {
            it.copy(
                providerName = providerName,
                statusMessage = if (registered) {
                    "Provider saved. Verify it is enabled in Calling Accounts."
                } else {
                    "Could not register provider. Check phone permissions and try again."
                }
            )
        }

        refreshProviderStatus()
    }

    fun openCallingAccountsIntent(): Intent {
        return Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun onTriggerOrCancelClicked() {
        if (uiState.value.isTimerRunning) {
            cancelTimer()
        } else {
            scheduleFakeCall()
        }
    }

    private fun scheduleFakeCall() {
        val state = uiState.value
        if (!state.hasRequiredPermissions) {
            _uiState.update { it.copy(statusMessage = "Grant phone permissions before scheduling.") }
            return
        }
        if (!state.isProviderEnabled) {
            _uiState.update {
                it.copy(statusMessage = "Enable this app in system Calling Accounts first.")
            }
            return
        }

        val number = state.callerNumber.trim()
        if (number.isBlank()) {
            _uiState.update { it.copy(statusMessage = "Enter a caller number before scheduling.") }
            return
        }

        prefs.edit()
            .putString(KEY_CALLER_NAME, state.callerName)
            .putString(KEY_CALLER_NUMBER, number)
            .apply()

        FakeCallSchedulerService.start(
            context = getApplication(),
            delaySeconds = state.selectedDelaySeconds,
            callerName = state.callerName,
            callerNumber = number,
            providerName = state.providerName
        )

        if (state.selectedDelaySeconds > 0) {
            val endsAt = System.currentTimeMillis() + state.selectedDelaySeconds * 1_000L
            prefs.edit().putLong(KEY_TIMER_ENDS_AT, endsAt).apply()
            _uiState.update {
                it.copy(
                    isTimerRunning = true,
                    timerEndsAtMillis = endsAt,
                    statusMessage = "Timer started for ${formatDelay(state.selectedDelaySeconds)}."
                )
            }
        } else {
            prefs.edit().remove(KEY_TIMER_ENDS_AT).apply()
            _uiState.update {
                it.copy(
                    isTimerRunning = false,
                    timerEndsAtMillis = 0L,
                    statusMessage = "Triggering incoming call now."
                )
            }
        }
    }

    private fun cancelTimer() {
        FakeCallSchedulerService.cancel(getApplication())
        prefs.edit().remove(KEY_TIMER_ENDS_AT).apply()
        _uiState.update {
            it.copy(
                isTimerRunning = false,
                timerEndsAtMillis = 0L,
                statusMessage = "Timer cancelled."
            )
        }
    }

    private fun syncRunningTimerState() {
        val endsAt = prefs.getLong(KEY_TIMER_ENDS_AT, 0L)
        if (endsAt <= 0L) {
            if (uiState.value.isTimerRunning || uiState.value.timerEndsAtMillis != 0L) {
                _uiState.update { it.copy(isTimerRunning = false, timerEndsAtMillis = 0L) }
            }
            return
        }

        val now = System.currentTimeMillis()
        if (now >= endsAt) {
            prefs.edit().remove(KEY_TIMER_ENDS_AT).apply()
            _uiState.update {
                it.copy(isTimerRunning = false, timerEndsAtMillis = 0L)
            }
        } else {
            if (!uiState.value.isTimerRunning || uiState.value.timerEndsAtMillis != endsAt) {
                _uiState.update {
                    it.copy(isTimerRunning = true, timerEndsAtMillis = endsAt)
                }
            }
        }
    }

    private fun ensurePhoneAccountRegistered() {
        telecomHelper.registerOrUpdatePhoneAccount(uiState.value.providerName)
    }

    private fun refreshProviderStatus() {
        if (!uiState.value.hasRequiredPermissions) {
            _uiState.update { it.copy(isProviderEnabled = false) }
            return
        }
        val enabled = telecomHelper.isAccountEnabled()
        _uiState.update { it.copy(isProviderEnabled = enabled) }
    }

    private fun fallbackNameFromUri(uri: Uri): String {
        val retriever = MediaMetadataRetriever()
        return runCatching {
            retriever.setDataSource(getApplication(), uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
        }.getOrNull().orEmpty().ifBlank {
            uri.lastPathSegment ?: "Selected audio"
        }.also {
            runCatching { retriever.release() }
        }
    }

    private fun readableTreeLabel(uri: Uri): String {
        val treeDocId = DocumentsContract.getTreeDocumentId(uri)
        if (treeDocId.isBlank()) return "Selected folder"
        val parts = treeDocId.split(':', limit = 2)
        return when {
            parts.size == 2 && parts[0] == "primary" && parts[1].isNotBlank() -> parts[1]
            parts.size == 2 && parts[1].isNotBlank() -> parts[1]
            else -> treeDocId
        }
    }

    companion object {
        private const val PREFS_NAME = "fake_call_prefs"
        private const val KEY_PROVIDER_NAME = "provider_name"
        private const val KEY_CALLER_NAME = "caller_name"
        private const val KEY_CALLER_NUMBER = "caller_number"
        private const val KEY_DELAY_SECONDS = "delay_seconds"
        private const val KEY_TIMER_ENDS_AT = "timer_ends_at"
        private const val KEY_AUDIO_URI = "audio_uri"
        private const val KEY_AUDIO_NAME = "audio_name"
        private const val KEY_RECORDING_ENABLED = "recording_enabled"
        private const val KEY_RECORDINGS_TREE_URI = "recordings_tree_uri"
        private const val KEY_RECORDINGS_FOLDER_NAME = "recordings_folder_name"

        fun formatDelay(seconds: Int): String {
            return when {
                seconds <= 0 -> "Now"
                seconds < 60 -> "$seconds seconds"
                seconds % 60 == 0 -> "${seconds / 60} minute${if (seconds >= 120) "s" else ""}"
                else -> "${seconds / 60}m ${seconds % 60}s"
            }
        }
    }
}

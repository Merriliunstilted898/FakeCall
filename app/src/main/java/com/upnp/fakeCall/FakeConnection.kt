package com.upnp.fakeCall

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FakeConnection(
    private val context: Context,
    private val callerName: String,
    private val callerNumber: String
) : Connection() {

    private var mediaPlayer: MediaPlayer? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var mediaRecorder: MediaRecorder? = null
    private var recordingTempFile: File? = null
    private var recordingDestination: RecordingDestination? = null
    private val audioManager: AudioManager = context.getSystemService(AudioManager::class.java)

    init {
        val displayName = callerName.ifBlank { callerNumber }
        setAddress(Uri.fromParts(PhoneAccount.SCHEME_TEL, callerNumber, null), TelecomManager.PRESENTATION_ALLOWED)
        setCallerDisplayName(displayName, TelecomManager.PRESENTATION_ALLOWED)
        setConnectionCapabilities(CAPABILITY_MUTE)
        setAudioModeIsVoip(true)
        setInitializing()
        setRinging()
    }

    override fun onAnswer() {
        setActive()
        try {
            setAudioRoute(CallAudioState.ROUTE_SPEAKER)
        } catch (_: Throwable) {
            // Device or OEM may reject forced route changes.
        }
        runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = true
        }
        startVoicePlayback()
        maybeStartMicRecording()
    }

    override fun onReject() {
        disconnectWithCause(DisconnectCause.REJECTED)
    }

    override fun onDisconnect() {
        disconnectWithCause(DisconnectCause.LOCAL)
    }

    override fun onAbort() {
        disconnectWithCause(DisconnectCause.CANCELED)
    }

    override fun onCallAudioStateChanged(state: CallAudioState) {
        super.onCallAudioStateChanged(state)
        runCatching {
            audioManager.isMicrophoneMute = state.isMuted
        }
    }

    private fun disconnectWithCause(code: Int) {
        stopAndReleasePlayer()
        stopAndReleaseRecording()
        runCatching {
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = AudioManager.MODE_NORMAL
        }
        setDisconnected(DisconnectCause(code))
        destroy()
    }

    private fun startVoicePlayback() {
        stopAndReleasePlayer()

        requestAudioFocus()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val selectedUri = loadSelectedAudioUri()
        if (selectedUri == null) {
            Log.i(TAG, "No audio file selected; skipping call playback.")
            return
        }

        val started = startPlayerFromUri(selectedUri, audioAttributes)
        if (!started) {
            Log.e(TAG, "Failed to start call playback for uri=$selectedUri")
        }
    }

    private fun maybeStartMicRecording() {
        if (!isRecordingEnabled()) return
        if (!hasRecordAudioPermission()) return

        runCatching { CallRecordingForegroundService.start(context) }
            .onFailure { Log.e(TAG, "Failed to start recording foreground service.", it) }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "fake_call_$timestamp.m4a"
        val destination = createRecordingDestination(filename) ?: return
        val tempFile = buildTempRecordingFile(filename)

        runCatching {
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(256_000)
                setAudioSamplingRate(48_000)
                setAudioChannels(1)
                setOutputFile(tempFile.absolutePath)
                prepare()
                start()
            }

            mediaRecorder = recorder
            recordingTempFile = tempFile
            recordingDestination = destination
        }.onFailure {
            runCatching { tempFile.delete() }
            cleanupRecordingDestination(destination)
            stopAndReleaseRecording()
        }
    }

    private fun startPlayerFromUri(uri: Uri, audioAttributes: AudioAttributes): Boolean {
        return runCatching {
            val player = MediaPlayer().apply {
                setAudioAttributes(audioAttributes)
                setDataSource(context, uri)
                isLooping = true
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error what=$what extra=$extra for uri=$uri")
                    stopAndReleasePlayer()
                    true
                }
                prepare()
                start()
            }
            mediaPlayer = player
            true
        }.getOrElse {
            Log.e(TAG, "MediaPlayer setup failed for uri=$uri", it)
            false
        }
    }

    private fun buildInternalRecordingFile(filename: String): File {
        val recordingsDir = File(context.filesDir, "recordings").apply { mkdirs() }
        return File(recordingsDir, filename)
    }

    private fun buildTempRecordingFile(filename: String): File {
        val tempDir = File(context.cacheDir, "recordings_tmp").apply { mkdirs() }
        return File(tempDir, filename)
    }

    private fun createRecordingDestination(filename: String): RecordingDestination? {
        val resolver = context.contentResolver

        val selectedTreeUri = loadRecordingsTreeUri()
        if (selectedTreeUri != null) {
            val fileUri = runCatching {
                DocumentsContract.createDocument(
                    resolver,
                    selectedTreeUri,
                    "audio/mp4",
                    filename
                )
            }.getOrNull()

            if (fileUri != null) {
                return RecordingDestination(uri = fileUri, file = null)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + File.separator + "FakeCall"
                )
            }
            val mediaStoreUri = runCatching {
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            }.getOrNull()

            if (mediaStoreUri != null) {
                return RecordingDestination(uri = mediaStoreUri, file = null)
            }
        }

        return RecordingDestination(
            uri = null,
            file = buildInternalRecordingFile(filename)
        )
    }

    private fun isRecordingEnabled(): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_RECORDING_ENABLED, true)
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun stopAndReleaseRecording() {
        var stoppedCleanly = false
        mediaRecorder?.run {
            stoppedCleanly = runCatching { stop() }.isSuccess
            runCatching { reset() }
            runCatching { release() }
        }
        mediaRecorder = null
        finalizeRecordingDestination(stoppedCleanly)
        runCatching { CallRecordingForegroundService.stop(context) }
    }

    private fun loadSelectedAudioUri(): Uri? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val value = prefs.getString(KEY_AUDIO_URI, "").orEmpty()
        if (value.isBlank()) return null
        return runCatching { Uri.parse(value) }.getOrNull()
    }

    private fun loadRecordingsTreeUri(): Uri? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val value = prefs.getString(KEY_RECORDINGS_TREE_URI, "").orEmpty()
        if (value.isBlank()) return null
        return runCatching { Uri.parse(value) }.getOrNull()
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { }
                .build()
            audioFocusRequest = request
            runCatching { audioManager.requestAudioFocus(request) }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN
                )
            }
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    private fun stopAndReleasePlayer() {
        mediaPlayer?.run {
            runCatching {
                if (isPlaying) {
                    stop()
                }
            }
            reset()
            release()
        }
        mediaPlayer = null
        abandonAudioFocus()
    }

    companion object {
        private const val TAG = "FakeConnection"
        private const val PREFS_NAME = "fake_call_prefs"
        private const val KEY_AUDIO_URI = "audio_uri"
        private const val KEY_RECORDING_ENABLED = "recording_enabled"
        private const val KEY_RECORDINGS_TREE_URI = "recordings_tree_uri"
    }

    private fun finalizeRecordingDestination(stoppedCleanly: Boolean) {
        val tempFile = recordingTempFile
        val destination = recordingDestination

        recordingTempFile = null
        recordingDestination = null

        if (tempFile == null || destination == null) {
            runCatching { tempFile?.delete() }
            return
        }

        if (!stoppedCleanly || !tempFile.exists()) {
            runCatching { tempFile.delete() }
            cleanupRecordingDestination(destination)
            Log.w(TAG, "Recorder stop failed; discarded broken recording.")
            return
        }

        val moved = runCatching {
            when {
                destination.uri != null -> {
                    context.contentResolver.openOutputStream(destination.uri, "w")?.use { out ->
                        tempFile.inputStream().use { input ->
                            input.copyTo(out)
                        }
                    } ?: error("Could not open output stream for destination Uri.")
                }
                destination.file != null -> {
                    destination.file.parentFile?.mkdirs()
                    tempFile.copyTo(destination.file, overwrite = true)
                }
                else -> error("No recording destination.")
            }
            true
        }.getOrElse {
            Log.e(TAG, "Failed to export recording to destination.", it)
            false
        }

        runCatching { tempFile.delete() }

        if (!moved) {
            cleanupRecordingDestination(destination)
        }
    }

    private fun cleanupRecordingDestination(destination: RecordingDestination) {
        destination.uri?.let { uri ->
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
        destination.file?.let { file ->
            runCatching { file.delete() }
        }
    }
}

private data class RecordingDestination(
    val uri: Uri?,
    val file: File?
)

package com.upnp.fakeCall

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
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
    private val audioManager: AudioManager = context.getSystemService(AudioManager::class.java)

    init {
        val displayName = callerName.ifBlank { callerNumber }
        setAddress(Uri.fromParts(PhoneAccount.SCHEME_TEL, callerNumber, null), TelecomManager.PRESENTATION_ALLOWED)
        setCallerDisplayName(displayName, TelecomManager.PRESENTATION_ALLOWED)
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

    private fun disconnectWithCause(code: Int) {
        stopAndReleasePlayer()
        stopAndReleaseRecording()
        setDisconnected(DisconnectCause(code))
        destroy()
    }

    private fun startVoicePlayback() {
        stopAndReleasePlayer()

        if (!requestAudioFocus()) {
            return
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val selectedUri = loadSelectedAudioUri()
        val rawFallbackUri = Uri.parse("android.resource://${context.packageName}/${R.raw.fake_voice}")

        val started = runCatching {
            val player = MediaPlayer().apply {
                setAudioAttributes(audioAttributes)
                if (selectedUri != null) {
                    setDataSource(context, selectedUri)
                } else {
                    setDataSource(context, rawFallbackUri)
                }
                isLooping = false
                setOnCompletionListener {
                    stopAndReleasePlayer()
                }
                setOnErrorListener { _, _, _ ->
                    stopAndReleasePlayer()
                    true
                }
                prepare()
                start()
            }
            mediaPlayer = player
            true
        }.getOrElse { false }

        if (!started && selectedUri != null) {
            runCatching {
                val player = MediaPlayer().apply {
                    setAudioAttributes(audioAttributes)
                    setDataSource(context, rawFallbackUri)
                    setOnCompletionListener {
                        stopAndReleasePlayer()
                    }
                    setOnErrorListener { _, _, _ ->
                        stopAndReleasePlayer()
                        true
                    }
                    prepare()
                    start()
                }
                mediaPlayer = player
            }.onFailure {
                stopAndReleasePlayer()
            }
        } else if (!started) {
            stopAndReleasePlayer()
        }
    }

    private fun maybeStartMicRecording() {
        if (!isRecordingEnabled()) return

        val outputFile = buildRecordingFile()
        runCatching {
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(256_000)
                setAudioSamplingRate(48_000)
                setAudioChannels(1)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }

            mediaRecorder = recorder
        }.onFailure {
            stopAndReleaseRecording()
        }
    }

    private fun buildRecordingFile(): File {
        val recordingsDir = File(context.filesDir, "recordings").apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(recordingsDir, "fake_call_$timestamp.m4a")
    }

    private fun isRecordingEnabled(): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_RECORDING_ENABLED, true)
    }

    private fun stopAndReleaseRecording() {
        mediaRecorder?.run {
            runCatching { stop() }
            runCatching { reset() }
            runCatching { release() }
        }
        mediaRecorder = null
    }

    private fun loadSelectedAudioUri(): Uri? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val value = prefs.getString(KEY_AUDIO_URI, "").orEmpty()
        if (value.isBlank()) return null
        return runCatching { Uri.parse(value) }.getOrNull()
    }

    private fun requestAudioFocus(): Boolean {
        val focusResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener { }
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }

        return focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
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
        private const val PREFS_NAME = "fake_call_prefs"
        private const val KEY_AUDIO_URI = "audio_uri"
        private const val KEY_RECORDING_ENABLED = "recording_enabled"
    }
}

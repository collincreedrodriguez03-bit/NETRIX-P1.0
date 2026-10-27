package com.example.netrix.service.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/**
 * High-precision Proximity Beep Generator.
 * Synthesizes 880 Hz sine wave tone with 5 ms linear attack/decay ramp via AudioTrack PCM stream.
 *
 * Parameters:
 *  - strength = clamp((ema(rssi) + 90) / 60, 0, 1)
 *  - EMA alpha = 0.3
 *  - Beep length = lerp(40ms, 600ms, strength)
 *  - Volume = lerp(0.1, 1.0, strength)
 *  - Inter-beep gap = fixed ~300ms
 */
class ProximityBeepService(
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "ProximityBeep"
        private const val SAMPLE_RATE = 44100
        private const val FREQUENCY_HZ = 880.0 // A5 pitch
        private const val FADE_MS = 5L
        private const val INTER_BEEP_GAP_MS = 300L
        private const val ALPHA = 0.3f
    }

    private var audioTrack: AudioTrack? = null
    private var beepJob: Job? = null

    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _targetBssid = MutableStateFlow<String?>(null)
    val targetBssid: StateFlow<String?> = _targetBssid.asStateFlow()

    private val _targetSsid = MutableStateFlow<String?>(null)
    val targetSsid: StateFlow<String?> = _targetSsid.asStateFlow()

    private val _isTargetConnected = MutableStateFlow(true)
    val isTargetConnected: StateFlow<Boolean> = _isTargetConnected.asStateFlow()

    private val _currentStrength = MutableStateFlow(0f)
    val currentStrength: StateFlow<Float> = _currentStrength.asStateFlow()

    private val _currentRssi = MutableStateFlow<Int?>(null)
    val currentRssi: StateFlow<Int?> = _currentRssi.asStateFlow()

    private var smoothedEma: Float? = null

    init {
        initAudioTrack()
    }

    private fun initAudioTrack() {
        try {
            val rawMin = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            // Ensure buffer size is positive and strictly frame-aligned (even number of bytes for 16-bit PCM).
            // SAMPLE_RATE / 4 was 11025 (odd), which caused UnsupportedOperationException: Invalid audio buffer size.
            val baseSize = if (rawMin > 0) rawMin else 4096
            val minBufSize = ((baseSize.coerceAtLeast(4096) + 3) / 4) * 4

            audioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBufSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBufSize,
                    AudioTrack.MODE_STREAM
                )
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize AudioTrack: ${e.message}", e)
        }
    }

    fun setTarget(bssid: String?, ssid: String?, isConnected: Boolean) {
        _targetBssid.value = bssid
        _targetSsid.value = ssid
        _isTargetConnected.value = isConnected
        smoothedEma = null
    }

    fun updateRssi(rssi: Int?, bssid: String?, ssid: String? = null, isConnected: Boolean = true) {
        val target = _targetBssid.value
        // If no explicit target set, default to connected AP or first reporting AP
        if (target != null && bssid != null && !target.equals(bssid, ignoreCase = true)) {
            return
        }

        if (rssi == null) return

        _currentRssi.value = rssi
        if (target == null && bssid != null) {
            _targetBssid.value = bssid
            _targetSsid.value = ssid
            _isTargetConnected.value = isConnected
        }

        // EMA smoothing (alpha = 0.3)
        val prev = smoothedEma
        val ema = if (prev == null) {
            rssi.toFloat()
        } else {
            prev * (1f - ALPHA) + rssi.toFloat() * ALPHA
        }
        smoothedEma = ema

        // strength = clamp((ema(rssi) + 90) / 60, 0, 1)
        val strength = ((ema + 90f) / 60f).coerceIn(0f, 1f)
        _currentStrength.value = strength
    }

    fun toggleBeep(enabled: Boolean) {
        _isEnabled.value = enabled
        if (enabled) {
            startLoop()
        } else {
            stopLoop()
        }
    }

    fun onPause() {
        stopLoop()
    }

    fun onResume() {
        if (_isEnabled.value) {
            startLoop()
        }
    }

    fun release() {
        stopLoop()
        try {
            audioTrack?.let {
                if (it.state == AudioTrack.STATE_INITIALIZED) {
                    it.stop()
                    it.release()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error releasing AudioTrack: ${t.message}", t)
        }
        audioTrack = null
    }

    private fun startLoop() {
        beepJob?.cancel()
        beepJob = scope.launch(Dispatchers.Default) {
            try {
                if (audioTrack == null || audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                    initAudioTrack()
                }
                if (audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    audioTrack?.play()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error starting AudioTrack playback: ${t.message}", t)
            }

            while (isActive && _isEnabled.value) {
                val strength = _currentStrength.value
                // Beep length = lerp(40, 600 ms, strength)
                val durationMs = (40f + (600f - 40f) * strength).toLong()
                // Volume = lerp(0.1, 1.0, strength)
                val volume = (0.1f + (1.0f - 0.1f) * strength)

                playSineToneWithGap(durationMs, volume, INTER_BEEP_GAP_MS)
            }
        }
    }

    private fun stopLoop() {
        beepJob?.cancel()
        beepJob = null
        try {
            if (audioTrack?.state == AudioTrack.STATE_INITIALIZED && audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack?.pause()
                audioTrack?.flush()
            }
        } catch (_: Throwable) {}
    }

    private fun playSineToneWithGap(durationMs: Long, volume: Float, gapMs: Long) {
        val track = audioTrack ?: return
        if (track.state != AudioTrack.STATE_INITIALIZED) return

        val toneSamples = ((SAMPLE_RATE * durationMs) / 1000).toInt().coerceAtLeast(100)
        val fadeSamples = ((SAMPLE_RATE * FADE_MS) / 1000).toInt().coerceAtLeast(1)
        val gapSamples = ((SAMPLE_RATE * gapMs) / 1000).toInt().coerceAtLeast(100)

        val totalSamples = toneSamples + gapSamples
        val pcmData = ShortArray(totalSamples)

        val angleDelta = 2.0 * PI * FREQUENCY_HZ / SAMPLE_RATE

        // 1. Synthesize 880 Hz sine wave tone with 5 ms fade in/out
        for (i in 0 until toneSamples) {
            val raw = sin(angleDelta * i)
            val envelope = when {
                i < fadeSamples -> i.toFloat() / fadeSamples.toFloat()
                i >= toneSamples - fadeSamples -> (toneSamples - 1 - i).toFloat() / fadeSamples.toFloat()
                else -> 1.0f
            }
            val sampleVal = (raw * envelope * volume * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            pcmData[i] = sampleVal
        }

        // 2. Remaining gapSamples are already 0 (silence)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                track.write(pcmData, 0, pcmData.size, AudioTrack.WRITE_BLOCKING)
            } else {
                track.write(pcmData, 0, pcmData.size)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "AudioTrack write failed: ${t.message}")
        }
    }
}

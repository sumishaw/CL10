package com.example.nihongolens

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlin.math.*

/**
 * GenderAnalyzer v4 — dual-mode YIN pitch detection.
 *
 * MODE A — PCM-feed (Audio Capture mode):
 *   SpeechCaptureService feeds raw PCM via feedPcm(). No extra AudioRecord needed.
 *
 * MODE B — Mic fallback (Live Captions mode):
 *   When SpeechCaptureService is not running, startMic() opens a mic AudioRecord.
 *   Tablet mic picks up the video audio from the speaker.
 *   startMic() is called by LiveCaptionReader when it connects.
 *
 * Both modes feed the same YIN analyze() pipeline.
 */
object GenderAnalyzer {

    private const val TAG           = "GenderAnalyzer"
    private const val SR            = 16_000
    private const val WIN           = 2048
    private const val TAU_MIN       = (SR / 300.0).toInt()   // ~53
    private const val TAU_MAX       = (SR / 60.0).toInt()    // ~266
    private const val YIN_THRESHOLD = 0.35f
    private const val RMS_FLOOR     = 50f
    private const val HIST          = 3

    @Volatile var enabled  = false
    @Volatile var micMode  = false   // true = mic AudioRecord is running

    private val history   = ArrayDeque<HindiTtsService.Gender>()
    private val accum     = ShortArray(WIN)
    private var accumFill = 0
    private val re        = FloatArray(WIN)
    private val im        = FloatArray(WIN)

    // Mic mode resources
    private val scope      = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var micJob:    Job?         = null
    private var micRecord: AudioRecord? = null

    // Diagnostics
    private var feedCount    = 0
    private var analyzeCount = 0
    private var frameCount   = 0

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Called by SpeechCaptureService when it starts capture (PCM-feed mode). */
    fun start() {
        stopMic()          // stop mic if running
        enabled = true
        micMode = false
        history.clear()
        accumFill = 0
        feedCount = 0; analyzeCount = 0; frameCount = 0
        Log.d(TAG, "GenderAnalyzer started (PCM-feed mode)")
        CaptionLogger.log(TAG, "started PCM-feed mode")
    }

    /** Called by LiveCaptionReader when it connects (Live Captions mode).
     *  projection: if provided, uses AudioPlaybackCaptureConfiguration (works with headphones).
     *  If null, falls back to mic AudioRecord (only works with speakers). */
    fun startMic(projection: MediaProjection? = null) {
        if (SpeechCaptureService.isRunning) return  // PCM-feed mode active
        if (micMode) return
        enabled = true
        micMode = true
        history.clear()
        accumFill = 0
        feedCount = 0; analyzeCount = 0; frameCount = 0
        micJob = scope.launch { 
            if (projection != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                internalAudioLoop(projection)
            } else {
                micLoop()
            }
        }
        val mode = if (projection != null) "internal-audio" else "mic-fallback"
        Log.d(TAG, "GenderAnalyzer started ($mode)")
        CaptionLogger.log(TAG, "started $mode")
    }

    fun stop() {
        enabled = false
        stopMic()
        history.clear()
        Log.d(TAG, "GenderAnalyzer stopped")
    }

    private fun stopMic() {
        micMode = false
        micJob?.cancel(); micJob = null
        try { micRecord?.stop() }  catch (_: Exception) {}
        try { micRecord?.release() } catch (_: Exception) {}
        micRecord = null
    }

    // ── Mic capture loop ──────────────────────────────────────────────────────

    private suspend fun micLoop() = withContext(Dispatchers.IO) {
        val minBuf = AudioRecord.getMinBufferSize(
            SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(WIN * 2)

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SR, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, minBuf
            )
        } catch (e: Exception) {
            CaptionLogger.log(TAG, "Mic AudioRecord failed: ${e.message}")
            return@withContext
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            CaptionLogger.log(TAG, "Mic AudioRecord not initialized")
            rec.release(); return@withContext
        }

        micRecord = rec
        rec.startRecording()
        CaptionLogger.log(TAG, "Mic recording started SR=$SR")

        val buf = ByteArray(WIN * 2)   // WIN shorts = WIN*2 bytes
        try {
            while (currentCoroutineContext().isActive && micMode) {
                val read = rec.read(buf, 0, buf.size)
                if (read > 0) {
                    // In mic mode: skip analysis while TTS is playing.
                    // The mic hears both video audio AND Hindi TTS from the speaker,
                    // so we must not analyze during TTS to avoid self-detection.
                    // (InternalAudio mode doesn't need this — USAGE_ASSISTANT is excluded at OS level)
                    if (!HindiTtsService.isSuppressed()) feedPcm(buf, read)
                } else delay(10)
            }
        } finally {
            try { rec.stop(); rec.release() } catch (_: Exception) {}
            micRecord = null
            CaptionLogger.log(TAG, "Mic recording stopped")
        }
    }

    // ── Internal audio loop (AudioPlaybackCapture — works with headphones) ────

    private suspend fun internalAudioLoop(projection: MediaProjection) = withContext(Dispatchers.IO) {
        // USAGE_MEDIA only — this is the video/music player stream.
        // USAGE_ASSISTANT (our Hindi TTS) is NOT listed here, so it is automatically
        // excluded from capture. No TTS contamination possible in this path.
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(
            SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(WIN * 2)

        val rec = try {
            AudioRecord.Builder()
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SR)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build())
                .setBufferSizeInBytes(minBuf)
                .setAudioPlaybackCaptureConfig(config)
                .build()
        } catch (e: Exception) {
            CaptionLogger.log(TAG, "InternalAudio failed: ${e.message} — falling back to mic")
            micLoop(); return@withContext
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            CaptionLogger.log(TAG, "InternalAudio not initialized — falling back to mic")
            rec.release(); micLoop(); return@withContext
        }

        micRecord = rec
        rec.startRecording()
        CaptionLogger.log(TAG, "InternalAudio recording started (headphone-safe)")

        val buf = ByteArray(WIN * 2)
        try {
            while (currentCoroutineContext().isActive && micMode) {
                val read = rec.read(buf, 0, buf.size)
                if (read > 0) feedPcm(buf, read) else delay(10)
            }
        } finally {
            try { rec.stop(); rec.release() } catch (_: Exception) {}
            micRecord = null
        }
    }

    // ── PCM feed (called by SpeechCaptureService OR mic loop) ────────────────

    fun feedPcm(bytes: ByteArray, count: Int) {
        feedCount++
        if (feedCount % 100 == 0) {
            CaptionLogger.log(TAG, "feedPcm #$feedCount enabled=$enabled mic=$micMode accum=$accumFill")
        }
        if (!enabled) return

        var i = 0
        while (i + 1 < count) {
            if (accumFill < WIN) {
                val lo = bytes[i].toInt() and 0xFF
                val hi = bytes[i + 1].toInt() and 0xFF
                accum[accumFill++] = ((hi shl 8) or lo).toShort()
            }
            i += 2
            if (accumFill >= WIN) {
                analyze()
                accumFill = 0
            }
        }
    }

    // ── YIN pitch detection ───────────────────────────────────────────────────

    private fun analyze() {
        analyzeCount++
        var energy = 0.0
        for (s in accum) energy += s.toLong() * s
        val rms = sqrt(energy / WIN).toFloat()
        if (rms < RMS_FLOOR) {
            if (analyzeCount % 20 == 0) CaptionLogger.log(TAG, "analyze #$analyzeCount rms=${rms.toInt()} SILENT")
            return
        }

        for (i in 0 until WIN) {
            val w = 0.5f * (1f - cos(2.0 * PI * i / (WIN - 1)).toFloat())
            re[i] = accum[i] * w / 32768f
            im[i] = 0f
        }

        val halfWin = WIN / 2
        val d = FloatArray(TAU_MAX + 1)
        for (tau in 1..TAU_MAX) {
            var sum = 0.0f
            for (j in 0 until halfWin) {
                val diff = re[j] - (if (j + tau < WIN) accum[j + tau] * 0.5f / 32768f else 0f)
                sum += diff * diff
            }
            d[tau] = sum
        }

        val cmndf = FloatArray(TAU_MAX + 1)
        cmndf[0] = 1f
        var runSum = 0f
        for (tau in 1..TAU_MAX) {
            runSum += d[tau]
            cmndf[tau] = if (runSum > 0f) d[tau] * tau / runSum else 1f
        }

        var tau = TAU_MIN
        while (tau < TAU_MAX - 1) {
            if (cmndf[tau] < YIN_THRESHOLD) {
                val better = if (tau + 1 < TAU_MAX && cmndf[tau + 1] < cmndf[tau]) tau + 1 else tau
                classifyPitch(SR.toFloat() / better, rms)
                return
            }
            tau++
        }

        var minVal = 1f; var minTau = TAU_MIN
        for (t in TAU_MIN until TAU_MAX) { if (cmndf[t] < minVal) { minVal = cmndf[t]; minTau = t } }
        if (analyzeCount % 10 == 0) {
            CaptionLogger.log(TAG, "noPitch #$analyzeCount rms=${rms.toInt()} minCMNDF=${String.format("%.3f", minVal)} f0est=${(SR.toFloat()/minTau).toInt()}Hz")
        }
    }

    private fun classifyPitch(f0: Float, rms: Float) {
        frameCount++
        if (frameCount % 5 == 0) CaptionLogger.log(TAG, "PITCH F0=${f0.toInt()}Hz rms=${rms.toInt()} → ${if (f0 >= 165f) "FEMALE" else "MALE"}")

        val gender = if (f0 >= 165f) HindiTtsService.Gender.FEMALE else HindiTtsService.Gender.MALE
        history.addLast(gender)
        if (history.size > HIST) history.removeFirst()

        val fCount = history.count { it == HindiTtsService.Gender.FEMALE }
        val majority = if (fCount > history.size / 2) HindiTtsService.Gender.FEMALE else HindiTtsService.Gender.MALE

        if (majority != HindiTtsService.detectedGender) {
            HindiTtsService.detectedGender = majority
            HindiTtsService.spokenTokens.clear()
            Log.d(TAG, "Gender→$majority F0=${f0.toInt()}Hz")
            CaptionLogger.log(TAG, "Gender→$majority F0=${f0.toInt()}Hz rms=${rms.toInt()}")
        }
    }
}

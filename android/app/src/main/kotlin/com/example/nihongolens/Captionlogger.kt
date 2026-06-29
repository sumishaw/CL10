package com.example.nihongolens

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedList
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * CaptionLogger v2 — Advanced debugging logger for Caption Lens
 *
 * Features:
 *   - 2000-line ring buffer (was 500) — captures full sessions
 *   - Severity levels: DEBUG / INFO / WARN / ERROR
 *   - Category tagging for granular filtering
 *   - Downloadable to /sdcard/Download/captionlens_YYYYMMDD_HHMMSS.log
 *   - Reset (clear) from UI
 *   - Subtitle-gone event detection with full state snapshot
 *   - Performance counters per category
 */
object CaptionLogger {

    private const val LOGCAT_TAG = "CaptionLens"
    private const val MAX_LINES  = 2000

    enum class Level { DEBUG, INFO, WARN, ERROR }

    private val fmt     = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val dateFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val buffer  = LinkedList<String>()
    private val lock    = Any()
    private var sessionStart = System.currentTimeMillis()

    // ── Per-category counters for quick stats ─────────────────────────────────
    private val counters = mutableMapOf<String, AtomicLong>()
    private var subtitleGoneCount   = 0
    private var lastSubtitleText    = ""
    private var lastSubtitleTimeMs  = 0L
    private var lastOverlayAlpha    = 1f
    private var lastOverlayVisible  = true

    fun init(context: Context) {
        sessionStart = System.currentTimeMillis()
        log("Logger", "=== Caption Lens v2 Logger ready — buffer=$MAX_LINES lines ===")
        log("Logger", "Download: captionlens_YYYYMMDD_HHMMSS.log in Downloads folder")
    }

    // ── Core log ──────────────────────────────────────────────────────────────

    @JvmOverloads
    fun log(tag: String, msg: String, level: Level = Level.INFO) {
        val levelChar = when (level) {
            Level.DEBUG -> "D"
            Level.INFO  -> "I"
            Level.WARN  -> "W"
            Level.ERROR -> "E"
        }
        val elapsed = (System.currentTimeMillis() - sessionStart) / 1000.0
        val line = "${fmt.format(Date())} [${levelChar}][${tag}] $msg  (+${String.format("%.1f", elapsed)}s)"

        when (level) {
            Level.DEBUG -> android.util.Log.d(LOGCAT_TAG, "[$tag] $msg")
            Level.INFO  -> android.util.Log.i(LOGCAT_TAG, "[$tag] $msg")
            Level.WARN  -> android.util.Log.w(LOGCAT_TAG, "[$tag] $msg")
            Level.ERROR -> android.util.Log.e(LOGCAT_TAG, "[$tag] $msg")
        }

        synchronized(lock) {
            buffer.addLast(line)
            if (buffer.size > MAX_LINES) buffer.removeFirst()
            counters.getOrPut(tag) { AtomicLong(0) }.incrementAndGet()
        }
    }

    // ── Overlay state tracking ────────────────────────────────────────────────
    // Call these from OverlayService to detect unexpected disappearances

    fun onOverlayTextSet(text: String, alpha: Float, visible: Boolean) {
        if (text.isNotBlank()) {
            lastSubtitleText   = text
            lastSubtitleTimeMs = System.currentTimeMillis()
        }
        lastOverlayAlpha   = alpha
        lastOverlayVisible = visible
        log("Overlay", "setText='${text.take(40)}' alpha=${"%.2f".format(alpha)} vis=$visible", Level.DEBUG)
    }

    fun onOverlayFadeOut(reason: String) {
        val age = System.currentTimeMillis() - lastSubtitleTimeMs
        log("Overlay", "FADE-OUT reason=$reason lastText='${lastSubtitleText.take(30)}' age=${age}ms", Level.WARN)
    }

    fun onOverlayGone(reason: String) {
        subtitleGoneCount++
        val age = System.currentTimeMillis() - lastSubtitleTimeMs
        log("Overlay", "SUBTITLE-GONE #$subtitleGoneCount reason=$reason " +
            "lastText='${lastSubtitleText.take(30)}' age=${age}ms " +
            "alpha=${"%.2f".format(lastOverlayAlpha)}", Level.ERROR)
        // Dump recent state for diagnosis
        log("Overlay", "STATE-SNAPSHOT: TTS.enabled=${HindiTtsService.enabled} " +
            "TTS.ready=${HindiTtsService.ttsReady} " +
            "fetchQ=${HindiTtsService.fetchQueueSize()} " +
            "playQ=${HindiTtsService.playQueueSize()}", Level.ERROR)
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    fun getStats(): String {
        synchronized(lock) {
            val topTags = counters.entries
                .sortedByDescending { it.value.get() }
                .take(8)
                .joinToString(" | ") { "${it.key}:${it.value.get()}" }
            return "Lines:${buffer.size}/$MAX_LINES | Gone:$subtitleGoneCount | $topTags"
        }
    }

    // ── Retrieval ─────────────────────────────────────────────────────────────

    fun getRecentLines(n: Int = MAX_LINES): String {
        synchronized(lock) {
            return if (buffer.size <= n) buffer.joinToString("\n")
            else buffer.toList().takeLast(n).joinToString("\n")
        }
    }

    fun getFilteredLines(filter: String, n: Int = 500): String {
        synchronized(lock) {
            return buffer.filter { it.contains(filter, ignoreCase = true) }
                .takeLast(n).joinToString("\n")
        }
    }

    fun clearLines() {
        synchronized(lock) {
            buffer.clear()
            counters.clear()
            subtitleGoneCount = 0
        }
        sessionStart = System.currentTimeMillis()
        log("Logger", "=== Log buffer reset ===")
    }

    fun stop() = log("Logger", "=== Logger stopped === ${getStats()}")

    // ── Download ──────────────────────────────────────────────────────────────

    fun downloadLogs(context: Context): String {
        return try {
            val fname = "captionlens_${dateFmt.format(Date())}.log"
            val dir   = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val file  = File(dir, fname)
            val header = buildString {
                appendLine("=== Caption Lens Debug Log ===")
                appendLine("Date: ${Date()}")
                appendLine("Device: ${android.os.Build.MODEL} Android ${android.os.Build.VERSION.RELEASE}")
                appendLine("Stats: ${getStats()}")
                appendLine("=".repeat(60))
                appendLine()
            }
            file.writeText(header + getRecentLines())
            log("Logger", "Log saved: ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            log("Logger", "Download failed: ${e.message}", Level.ERROR)
            "Error: ${e.message}"
        }
    }

    fun getLogPath(): String = "Downloads/captionlens_*.log"
}

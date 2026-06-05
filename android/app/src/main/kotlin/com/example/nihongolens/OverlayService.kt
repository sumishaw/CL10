package com.example.nihongolens

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.*
import android.util.TypedValue
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicLong

/**
 * OverlayService — Progressive word-by-word Hindi subtitle overlay
 *
 * Single TextView, maxLines=2, words stream in at WORD_INTERVAL_MS pace.
 * When sentence complete or 2 lines full → hold HOLD_MS → clear → next sentence.
 * FIFO + token: no drops, no duplicates, no stale items after LC gone.
 */
class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "nihongo_overlay"
        const val NOTIF_ID   = 1

        @Volatile var latestOriginal = ""
        @Volatile var latestHindi    = ""
        @Volatile private var pushCallback:  ((String, String) -> Unit)? = null
        @Volatile private var clearCallback: (() -> Unit)?               = null

        fun updateText(original: String, hindi: String) {
            latestOriginal = original; latestHindi = hindi
            pushCallback?.invoke(original, hindi)
        }
        fun clearQueue() { clearCallback?.invoke() }
    }

    private val WORD_INTERVAL_MS = 300L   // ms between words
    private val HOLD_MS          = 2_000L // hold after sentence complete
    private val SILENCE_MS       = 8_000L // fade after no speech

    private val tokenCounter  = AtomicLong(0)
    private var expectedToken = 0L

    data class Item(val token: Long, val words: List<String>)
    private val queue = ArrayDeque<Item>()

    private var currentWords  = listOf<String>()
    private var wordIndex     = 0
    private var displayedText = ""
    private var isProgressing = false
    private var currentToken  = -1L

    private var wordRunnable:    Runnable? = null
    private var holdRunnable:    Runnable? = null
    private var silenceRunnable: Runnable? = null

    private var windowManager: WindowManager?              = null
    private var textView:      TextView?                   = null
    private var overlayView:   View?                       = null
    private var params:        WindowManager.LayoutParams? = null
    private val handler        = Handler(Looper.getMainLooper())

    @Volatile private var running   = true
    @Volatile private var viewAdded = false

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                startForeground(NOTIF_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else
                startForeground(NOTIF_ID, buildNotification())
        } catch (e: Exception) {
            startForeground(NOTIF_ID, buildNotification())
        }

        windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager
        handler.post { if (running) buildOverlay() }
        pushCallback  = { _, hindi -> handler.post { onPush(hindi) } }
        clearCallback = { handler.post { onClear() } }
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int) = START_STICKY
    override fun onBind(i: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        pushCallback = null; clearCallback = null
        handler.removeCallbacksAndMessages(null)
        queue.clear()
        if (viewAdded) {
            try { windowManager?.removeView(overlayView) } catch (_: Exception) {}
            viewAdded = false
        }
        super.onDestroy()
    }

    // ── Push / Clear ──────────────────────────────────────────────────────────

    private fun onPush(hindi: String) {
        if (hindi.isBlank()) return
        val words = hindi.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return

        val token = tokenCounter.incrementAndGet()
        queue.addLast(Item(token, words))
        reschedSilence()
        if (!isProgressing) startNext()
    }

    private fun onClear() {
        expectedToken = tokenCounter.get() + 1
        queue.clear()
        cancelAll()
        isProgressing = false
        currentToken  = -1L
        hideText()
    }

    // ── Progressive display ───────────────────────────────────────────────────

    private fun startNext() {
        cancelAll()
        while (queue.isNotEmpty() && queue.first().token < expectedToken)
            queue.removeFirst()
        if (queue.isEmpty()) { isProgressing = false; return }

        val item = queue.removeFirst()
        if (item.token < expectedToken) { startNext(); return }

        currentWords  = item.words
        currentToken  = item.token
        wordIndex     = 0
        isProgressing = true
        displayedText = ""
        hideText()
        scheduleNextWord()
    }

    private fun scheduleNextWord() {
        wordRunnable = Runnable {
            wordRunnable = null
            if (!running || currentToken < expectedToken) {
                isProgressing = false; hideText(); return@Runnable
            }
            if (wordIndex >= currentWords.size) {
                // Sentence done — hold then advance
                holdThenNext(); return@Runnable
            }

            displayedText = if (displayedText.isEmpty()) currentWords[wordIndex]
                            else "$displayedText ${currentWords[wordIndex]}"
            wordIndex++
            showText(displayedText)

            // If text exceeds ~2 lines (40 chars), hold then show remainder
            if (displayedText.length >= 40 && wordIndex < currentWords.size) {
                holdRunnable = Runnable {
                    holdRunnable = null
                    if (!running || currentToken < expectedToken) { startNext(); return@Runnable }
                    displayedText = ""
                    hideText()
                    scheduleNextWord()
                }
                handler.postDelayed(holdRunnable!!, HOLD_MS)
            } else {
                scheduleNextWord()
            }
        }
        handler.postDelayed(wordRunnable!!, WORD_INTERVAL_MS)
    }

    private fun holdThenNext() {
        val cap = currentToken
        holdRunnable = Runnable {
            holdRunnable = null
            if (!running) return@Runnable
            if (cap < expectedToken) { isProgressing = false; hideText(); startNext(); return@Runnable }
            hideText()
            startNext()
        }
        handler.postDelayed(holdRunnable!!, HOLD_MS)
    }

    // ── Display helpers ───────────────────────────────────────────────────────

    private fun showText(text: String) {
        val tv = textView ?: return
        tv.text  = text
        if (tv.alpha < 0.5f) {
            tv.animate().cancel()
            tv.animate().alpha(1f).setDuration(150).start()
        }
    }

    private fun hideText() {
        val tv = textView ?: return
        tv.animate().cancel()
        tv.alpha = 0f
        tv.text  = ""
        displayedText = ""
    }

    private fun cancelAll() {
        wordRunnable?.let { handler.removeCallbacks(it) }
        holdRunnable?.let { handler.removeCallbacks(it) }
        wordRunnable = null; holdRunnable = null
    }

    private fun reschedSilence() {
        silenceRunnable?.let { handler.removeCallbacks(it) }
        silenceRunnable = Runnable {
            if (!running || queue.isNotEmpty() || isProgressing) return@Runnable
            hideText()
        }
        handler.postDelayed(silenceRunnable!!, SILENCE_MS)
    }

    // ── Overlay window ────────────────────────────────────────────────────────

    private fun buildOverlay() {
        try {
            val sw = resources.displayMetrics.widthPixels
            val tv = TextView(this).apply {
                typeface  = Typeface.DEFAULT_BOLD
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(Color.WHITE)
                setShadowLayer(10f, 0f, 2f, Color.BLACK)
                maxLines  = 2
                setLineSpacing(dp(4).toFloat(), 1f)
                ellipsize = android.text.TextUtils.TruncateAt.END
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape        = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(10).toFloat()
                    setColor(Color.argb(190, 0, 0, 0))
                }
                setPadding(dp(14), dp(10), dp(14), dp(10))
                alpha = 0f
                text  = ""
            }
            textView    = tv
            overlayView = tv

            params = WindowManager.LayoutParams(
                (sw * 0.92).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(90) }

            var sx = 0f; var sy = 0f; var ix = 0; var iy = 0
            tv.setOnTouchListener { _, ev ->
                val p = params ?: return@setOnTouchListener false
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> { sx = ev.rawX; sy = ev.rawY; ix = p.x; iy = p.y }
                    MotionEvent.ACTION_MOVE -> {
                        p.x = ix + (ev.rawX - sx).toInt()
                        p.y = iy - (ev.rawY - sy).toInt()
                        if (viewAdded) try { windowManager?.updateViewLayout(overlayView, p) }
                        catch (_: Exception) {}
                    }
                }
                true
            }

            windowManager?.addView(tv, params)
            viewAdded = true
        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "buildOverlay failed: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            NotificationChannel(CHANNEL_ID, "Caption Lens Overlay",
                NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
                .also { getSystemService(NotificationManager::class.java)
                    .createNotificationChannel(it) }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Caption Lens Active")
            .setContentText("Hindi subtitle overlay running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true).setSilent(true).build()
}

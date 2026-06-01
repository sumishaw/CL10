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

/**
 * OverlayService — Hindi subtitle overlay
 *
 * - Single TextView, maxLines=2, wraps naturally
 * - FIFO queue: every translation shown in order, nothing dropped
 * - READ_MS: each subtitle stays 7s before advancing
 * - Backlog mode: if 3+ queued, advance every 3.5s to catch up
 * - SILENCE_MS: fade out after 10s with no new text
 * - One timer only: readRunnable always cancelled before creating new one
 */
class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "nihongo_overlay"
        const val NOTIF_ID   = 1

        @Volatile var latestOriginal = ""
        @Volatile var latestHindi    = ""
        @Volatile private var pushCallback: ((String, String) -> Unit)? = null

        fun updateText(original: String, hindi: String) {
            latestOriginal = original
            latestHindi    = hindi
            pushCallback?.invoke(original, hindi)
        }
    }

    private val READ_MS    = 7_000L
    private val SILENCE_MS = 10_000L

    // FIFO — never drops
    private val queue       = ArrayDeque<String>()
    private var currentText = ""
    private var showing     = false

    private var readRunnable:    Runnable? = null
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else
            startForeground(NOTIF_ID, buildNotification())

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        handler.post { if (running) buildOverlay() }
        pushCallback = { _, hindi -> handler.post { onNewHindi(hindi) } }
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int) = START_STICKY
    override fun onBind(i: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        pushCallback = null
        handler.removeCallbacksAndMessages(null)
        queue.clear()
        if (viewAdded) {
            try { windowManager?.removeView(overlayView) } catch (_: Exception) {}
            viewAdded = false
        }
        super.onDestroy()
    }

    // ── Core display logic ────────────────────────────────────────────────────

    private fun onNewHindi(hindi: String) {
        if (hindi.isBlank()) return
        val t = hindi.trim()

        // Skip exact duplicate of what's on screen if nothing else waiting
        if (t == currentText && queue.isEmpty()) {
            rescheduleSilence(); return
        }

        queue.addLast(t)          // FIFO add
        rescheduleSilence()

        // Kick display only if idle — if already showing, readRunnable will advance
        if (!showing) advance()
    }

    /**
     * Advance to the next queued item.
     * Always cancels existing readRunnable before doing anything.
     * This is the ONLY place readRunnable is created.
     */
    private fun advance() {
        // Cancel any existing timer — one timer at a time
        readRunnable?.let { handler.removeCallbacks(it) }
        readRunnable = null

        if (queue.isEmpty()) {
            // Nothing to show — stay on current text until silence
            return
        }

        val text   = queue.removeFirst()   // FIFO remove
        currentText = text
        showing     = true
        display(text)

        // Schedule next advance
        val waitMs = if (queue.size >= 3) READ_MS / 2 else READ_MS
        readRunnable = Runnable {
            readRunnable = null
            if (!running) return@Runnable
            if (queue.isNotEmpty()) advance()
            // else: stay on screen until silence or new text
        }
        handler.postDelayed(readRunnable!!, waitMs)
    }

    private fun display(text: String) {
        val tv = textView ?: return
        tv.animate().cancel()
        tv.alpha = 0f
        tv.text  = text
        tv.animate().alpha(1f).setDuration(180).start()
    }

    private fun rescheduleSilence() {
        silenceRunnable?.let { handler.removeCallbacks(it) }
        silenceRunnable = Runnable {
            if (!running) return@Runnable
            if (queue.isNotEmpty()) return@Runnable  // new content arrived — don't clear
            readRunnable?.let { handler.removeCallbacks(it) }
            readRunnable = null
            textView?.animate()?.alpha(0f)?.setDuration(400)?.withEndAction {
                currentText = ""; showing = false
            }?.start()
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
                ellipsize = android.text.TextUtils.TruncateAt.END
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape        = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(10).toFloat()
                    setColor(Color.argb(185, 0, 0, 0))
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
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = dp(90)
            }

            // Draggable
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

            windowManager?.addView(overlayView, params)
            viewAdded = true
        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "buildOverlay: ${e.message}")
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

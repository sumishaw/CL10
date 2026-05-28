package com.example.nihongolens

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LiveCaptionReader — Accessibility Service
 *
 * Monitors Android Live Captions (com.google.android.as) in real-time.
 * Reads caption text as it appears → sends to whisper_server for Hindi translation.
 *
 * Setup required:
 * 1. Enable Live Captions on device (Quick Settings or Settings > Accessibility)
 * 2. Enable this Accessibility Service in Settings > Accessibility > Installed Services
 * 3. Start Caption Lens — it will now use Live Captions as input instead of audio capture
 *
 * Package names for Live Captions on different Android versions:
 * - Android 10+:  com.google.android.as (Android System Intelligence)
 * - Some devices: com.google.android.accessibility.caption
 * - Pixel phones: com.google.android.as
 * - Samsung:      com.samsung.android.bixby.agent (may vary)
 */
class LiveCaptionReader : AccessibilityService() {

    companion object {
        private const val TAG = "LiveCaptionReader"

        // Package names that host Live Captions UI
        private val LIVE_CAPTION_PACKAGES = setOf(
            "com.google.android.as",
            "com.google.android.accessibility.caption",
            "com.google.android.accessibility.captions",
            "com.google.android.tts",
        )

        // Node descriptions/IDs used by Live Captions text view
        private val CAPTION_VIEW_IDS = setOf(
            "caption_text",
            "captionText",
            "live_caption_text",
            "transcript_text",
        )

        @Volatile var isRunning = false
        @Volatile var lastCaptionText = ""
        @Volatile var instance: LiveCaptionReader? = null
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isProcessing = AtomicBoolean(false)
    private var lastSentText = ""
    private var pendingText = ""
    private var pendingJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isRunning = true

        // Configure to monitor Live Captions packages
        serviceInfo = serviceInfo?.also { info ->
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.packageNames  = LIVE_CAPTION_PACKAGES.toTypedArray()
            info.notificationTimeout = 50  // 50ms polling
            info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }

        Log.i(TAG, "LiveCaptionReader connected — monitoring Live Captions")
        MainActivity.instance?.onLiveCaptionReaderConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (!isRunning) return

        val pkg = event.packageName?.toString() ?: return
        if (pkg !in LIVE_CAPTION_PACKAGES) return

        // Extract caption text from the event
        val text = extractCaptionText(event) ?: return
        if (text.isBlank() || text == lastCaptionText) return

        lastCaptionText = text
        scheduleSend(text)
    }

    private fun extractCaptionText(event: AccessibilityEvent): String? {
        // Method 1: Direct from event text
        val eventText = event.text?.joinToString(" ")?.trim()
        if (!eventText.isNullOrBlank() && eventText.length > 1) {
            return eventText
        }

        // Method 2: Walk accessibility tree to find caption node
        val root = rootInActiveWindow ?: return null
        return findCaptionNode(root)?.text?.toString()?.trim()
    }

    private fun findCaptionNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        node ?: return null

        // Check if this node is a caption text view
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val desc   = node.contentDescription?.toString()?.lowercase() ?: ""
        val cls    = node.className?.toString()?.lowercase() ?: ""

        if (CAPTION_VIEW_IDS.any { viewId.contains(it) || desc.contains(it) }) {
            if (!node.text.isNullOrBlank()) return node
        }

        // Check for large text views (Live Captions uses big text)
        if (cls.contains("textview") && !node.text.isNullOrBlank()) {
            val text = node.text.toString()
            if (text.length > 3 && text.length < 500) {
                return node
            }
        }

        // Recurse into children
        for (i in 0 until node.childCount) {
            val found = findCaptionNode(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    private fun scheduleSend(text: String) {
        // Debounce 300ms — Live Captions updates word by word
        // Wait for a natural pause before sending to CT2
        pendingText = text
        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(300)
            val toSend = pendingText
            if (toSend.isNotBlank() && toSend != lastSentText) {
                lastSentText = toSend
                sendToTranslation(toSend)
            }
        }
    }

    private fun sendToTranslation(englishText: String) {
        if (isProcessing.getAndSet(true)) return

        scope.launch {
            try {
                Log.d(TAG, "Caption: '$englishText'")

                // Send to whisper_server /translate endpoint
                val result = translateViaServer(englishText)

                if (result != null && result.isNotBlank()) {
                    Log.d(TAG, "Hindi: '$result'")
                    SpeechCaptureService.latestHindi   = result
                    SpeechCaptureService.latestEnglish = englishText

                    withContext(Dispatchers.Main) {
                        OverlayService.updateText(englishText, result)
                        MainActivity.instance?.onTranslation(englishText, result, result)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Translation error: ${e.message}")
            } finally {
                isProcessing.set(false)
            }
        }
    }

    private fun translateViaServer(text: String): String? {
        return try {
            val url  = java.net.URL("http://127.0.0.1:8765/translate_text")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput      = true
            conn.connectTimeout = 2_000
            conn.readTimeout    = 15_000

            val body = """{"text": ${org.json.JSONObject.quote(text)}, "src": "en", "tgt": "hi"}"""
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            if (conn.responseCode != 200) return null
            val resp = conn.inputStream.bufferedReader().readText()
            org.json.JSONObject(resp).optString("text", "").trim()
                .takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "Server error: ${e.message}")
            null
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Interrupted")
    }

    override fun onDestroy() {
        isRunning = false
        instance  = null
        scope.cancel()
        super.onDestroy()
    }
}

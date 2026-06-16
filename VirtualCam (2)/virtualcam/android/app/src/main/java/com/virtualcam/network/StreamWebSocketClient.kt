package com.virtualcam.network

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

private const val TAG = "WSClient"
private const val RECONNECT_DELAY_MS = 3_000L

/**
 * Manages a single WebSocket connection to the PC server.
 *
 * - Calls [onFrame] on every binary frame received.
 * - Calls [onStateChange] with connection state strings suitable for the UI.
 * - Auto-reconnects every [RECONNECT_DELAY_MS] ms when [connect] has been called
 *   and the socket drops unexpectedly.
 */
class StreamWebSocketClient(
    private val scope: CoroutineScope,
    private val onFrame: (ByteArray) -> Unit,
    private val onStateChange: (state: String, detail: String) -> Unit,
) {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)   // no read timeout – stream is continuous
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var running = false
    private var reconnectJob: Job? = null
    private var currentUrl: String = ""

    // ── Public API ────────────────────────────────────────────────────────────

    fun connect(ip: String, port: Int) {
        currentUrl = "ws://$ip:$port"
        running = true
        attemptConnect()
    }

    fun disconnect() {
        running = false
        reconnectJob?.cancel()
        reconnectJob = null
        socket?.close(1000, "User disconnected")
        socket = null
        onStateChange("DISCONNECTED", "")
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun attemptConnect() {
        if (!running) return
        onStateChange("CONNECTING", currentUrl)
        Log.d(TAG, "Connecting → $currentUrl")

        val request = Request.Builder().url(currentUrl).build()
        socket = http.newWebSocket(request, Listener())
    }

    private fun scheduleReconnect(reason: String) {
        if (!running) return
        Log.w(TAG, "Scheduling reconnect in ${RECONNECT_DELAY_MS}ms  reason=$reason")
        onStateChange("RECONNECTING", reason)
        reconnectJob?.cancel()
        reconnectJob = scope.launch(Dispatchers.IO) {
            delay(RECONNECT_DELAY_MS)
            if (isActive && running) attemptConnect()
        }
    }

    private inner class Listener : WebSocketListener() {

        override fun onOpen(ws: WebSocket, response: Response) {
            Log.i(TAG, "Connected to $currentUrl")
            onStateChange("CONNECTED", "0")
        }

        override fun onMessage(ws: WebSocket, bytes: ByteString) {
            onFrame(bytes.toByteArray())
        }

        override fun onMessage(ws: WebSocket, text: String) {
            // Server sends binary only; ignore text frames
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "Connection failure: ${t.message}")
            scheduleReconnect(t.message ?: "Unknown error")
        }

        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            ws.close(1000, null)
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "Socket closed  code=$code  reason=$reason")
            if (running && code != 1000) {
                scheduleReconnect("Socket closed: $reason")
            }
        }
    }
}

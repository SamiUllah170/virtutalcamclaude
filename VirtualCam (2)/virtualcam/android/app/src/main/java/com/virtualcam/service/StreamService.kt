package com.virtualcam.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.virtualcam.R
import com.virtualcam.StreamState
import com.virtualcam.camera.VirtualCameraService
import com.virtualcam.network.FrameDecoder
import com.virtualcam.network.StreamWebSocketClient
import com.virtualcam.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

private const val TAG = "StreamService"
private const val CHANNEL_ID = "virtualcam_stream"
private const val NOTIF_ID = 1001

class StreamService : LifecycleService() {

    // ── Binder for ViewModel ──────────────────────────────────────────────────

    inner class LocalBinder : Binder() {
        fun getService(): StreamService = this@StreamService
    }

    private val binder = LocalBinder()

    // ── Callbacks set by ViewModel ────────────────────────────────────────────

    var onStateChange: ((StreamState) -> Unit)? = null
    var onFrame: ((Bitmap) -> Unit)? = null

    // ── Internals ─────────────────────────────────────────────────────────────

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var wsClient: StreamWebSocketClient
    private var wakeLock: PowerManager.WakeLock? = null

    // Last known surface dimensions for letterboxing
    @Volatile private var surfaceWidth = 0
    @Volatile private var surfaceHeight = 0

    companion object {
        const val ACTION_CONNECT = "com.virtualcam.CONNECT"
        const val ACTION_DISCONNECT = "com.virtualcam.DISCONNECT"
        const val EXTRA_IP = "extra_ip"
        const val EXTRA_PORT = "extra_port"
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()

        wsClient = StreamWebSocketClient(
            scope = serviceScope,
            onFrame = ::handleFrame,
            onStateChange = ::handleStateChange,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_CONNECT -> {
                val ip = intent.getStringExtra(EXTRA_IP) ?: return START_NOT_STICKY
                val port = intent.getIntExtra(EXTRA_PORT, 8765)
                startForeground(NOTIF_ID, buildNotification("Connecting…"))
                wsClient.connect(ip, port)
            }
            ACTION_DISCONNECT -> {
                wsClient.disconnect()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onDestroy() {
        wsClient.disconnect()
        wakeLock?.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ── Surface dimensions (called from MainActivity when TextureView changes) ─

    fun setSurfaceDimensions(w: Int, h: Int) {
        surfaceWidth = w
        surfaceHeight = h
    }

    // ── Frame handling ────────────────────────────────────────────────────────

    private fun handleFrame(payload: ByteArray) {
        val decoded = FrameDecoder.decode(payload) ?: run {
            Log.w(TAG, "Malformed frame (${payload.size} bytes) – discarded")
            return
        }

        val latency = FrameDecoder.latencyMs(decoded.timestampMs)

        // Letterbox if we know the surface size
        val bmp = if (surfaceWidth > 0 && surfaceHeight > 0) {
            FrameDecoder.letterbox(decoded.bitmap, surfaceWidth, surfaceHeight)
        } else {
            decoded.bitmap
        }

        // Push to virtual camera (may be no-op on API < 33)
        VirtualCameraService.pushFrame(bmp)

        // Push to UI
        onFrame?.invoke(bmp)

        // Update latency in state
        onStateChange?.invoke(StreamState.Connected(latency))

        // Update notification periodically (every ~60 frames ≈ 2 s at 30 fps)
        if ((System.currentTimeMillis() / 2000L) % 2L == 0L) {
            updateNotification("Streaming  •  ${latency}ms")
        }
    }

    // ── WebSocket state → StreamState ─────────────────────────────────────────

    private fun handleStateChange(state: String, detail: String) {
        val streamState = when (state) {
            "CONNECTED"    -> StreamState.Connected(detail.toLongOrNull() ?: 0L)
            "CONNECTING"   -> StreamState.Connecting
            "RECONNECTING" -> StreamState.Connecting
            "DISCONNECTED" -> StreamState.Disconnected
            else           -> StreamState.Error(detail)
        }
        onStateChange?.invoke(streamState)

        if (state == "CONNECTED") updateNotification("Connected  •  0ms")
        if (state == "RECONNECTING") updateNotification("Reconnecting…")
    }

    // ── Notification helpers ──────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "VirtualCam Stream",
            NotificationManager.IMPORTANCE_LOW,    // silent
        ).apply {
            description = "PC screen streaming service"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VirtualCam")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(status))
    }

    // ── Wake lock ─────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "VirtualCam::StreamWakeLock",
        ).also { it.acquire(4 * 60 * 60 * 1000L /* 4 h max */) }
    }
}

package com.virtualcam.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.virtualcam.StreamState
import com.virtualcam.service.StreamService

class StreamViewModel(app: Application) : AndroidViewModel(app) {

    // ── Observables ───────────────────────────────────────────────────────────

    private val _streamState = MutableLiveData<StreamState>(StreamState.Disconnected)
    val streamState: LiveData<StreamState> = _streamState

    private val _latestFrame = MutableLiveData<Bitmap?>()
    val latestFrame: LiveData<Bitmap?> = _latestFrame

    // ── Prefs ─────────────────────────────────────────────────────────────────

    private val prefs = app.getSharedPreferences("virtualcam_prefs", Context.MODE_PRIVATE)

    var savedIp: String
        get() = prefs.getString("ip", "192.168.1.100") ?: "192.168.1.100"
        set(v) = prefs.edit().putString("ip", v).apply()

    var savedPort: Int
        get() = prefs.getInt("port", 8765)
        set(v) = prefs.edit().putInt("port", v).apply()

    // ── Service binding ───────────────────────────────────────────────────────

    private var service: StreamService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as StreamService.LocalBinder).getService()
            bound = true

            // Wire service callbacks → LiveData
            service?.onStateChange = { state ->
                _streamState.postValue(state)
            }
            service?.onFrame = { bmp ->
                _latestFrame.postValue(bmp)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            bound = false
            service = null
        }
    }

    fun bindService(context: Context) {
        val intent = Intent(context, StreamService::class.java)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun unbindService(context: Context) {
        if (bound) {
            context.unbindService(connection)
            bound = false
        }
    }

    // ── Commands from UI ──────────────────────────────────────────────────────

    fun connect(context: Context, ip: String, port: Int) {
        savedIp = ip
        savedPort = port
        val intent = Intent(context, StreamService::class.java).apply {
            action = StreamService.ACTION_CONNECT
            putExtra(StreamService.EXTRA_IP, ip)
            putExtra(StreamService.EXTRA_PORT, port)
        }
        context.startForegroundService(intent)
        _streamState.value = StreamState.Connecting
    }

    fun disconnect(context: Context) {
        val intent = Intent(context, StreamService::class.java).apply {
            action = StreamService.ACTION_DISCONNECT
        }
        context.startService(intent)
        _streamState.value = StreamState.Disconnected
    }

    /**
     * Forward the TextureView's pixel dimensions to the bound service so it can
     * letterbox incoming frames to match the exact preview surface size.
     */
    fun notifySurfaceSize(width: Int, height: Int) {
        service?.setSurfaceDimensions(width, height)
    }

    override fun onCleared() {
        super.onCleared()
        // ViewModel is cleared but service may still run in background
    }
}

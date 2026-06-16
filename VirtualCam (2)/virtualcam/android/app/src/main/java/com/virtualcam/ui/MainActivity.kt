package com.virtualcam.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.virtualcam.R
import com.virtualcam.StreamState
import com.virtualcam.camera.VirtualCameraService
import com.virtualcam.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: StreamViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val allGranted = results.values.all { it }
            if (!allGranted) {
                binding.statusText.text = getString(R.string.status_permission_denied)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestRequiredPermissions()
        VirtualCameraService.initialize(applicationContext)

        restoreSavedConnection()
        setupTextureView()
        setupConnectButton()
        observeViewModel()
    }

    override fun onStart() {
        super.onStart()
        viewModel.bindService(this)
    }

    override fun onStop() {
        super.onStop()
        viewModel.unbindService(this)
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private fun requestRequiredPermissions() {
        val needed = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.CAMERA

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.POST_NOTIFICATIONS

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun restoreSavedConnection() {
        binding.ipInput.setText(viewModel.savedIp)
        binding.portInput.setText(viewModel.savedPort.toString())
    }

    private fun setupTextureView() {
        binding.previewTexture.surfaceTextureListener = object :
            android.view.TextureView.SurfaceTextureListener {

            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                viewModel.notifySurfaceSize(width, height)
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                viewModel.notifySurfaceSize(width, height)
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
    }

    private fun setupConnectButton() {
        binding.connectButton.setOnClickListener {
            val currentState = viewModel.streamState.value
            val isConnected = currentState is StreamState.Connected ||
                    currentState is StreamState.Connecting

            if (isConnected) {
                viewModel.disconnect(this)
            } else {
                val ip = binding.ipInput.text.toString().trim()
                val port = binding.portInput.text.toString().trim().toIntOrNull()

                if (ip.isEmpty() || port == null) {
                    binding.statusText.text = getString(R.string.status_invalid_input)
                    return@setOnClickListener
                }
                viewModel.connect(this, ip, port)
            }
        }
    }

    // ── Observers ─────────────────────────────────────────────────────────────

    private fun observeViewModel() {
        viewModel.streamState.observe(this) { state ->
            renderState(state)
        }

        viewModel.latestFrame.observe(this) { bitmap ->
            bitmap?.let { drawFrameToTexture(it) }
        }
    }

    private fun renderState(state: StreamState) {
        when (state) {
            is StreamState.Disconnected -> {
                binding.statusText.text = getString(R.string.status_disconnected)
                binding.statusDot.setBackgroundResource(R.drawable.dot_red)
                binding.connectButton.text = getString(R.string.action_connect)
                setInputsEnabled(true)
            }
            is StreamState.Connecting -> {
                binding.statusText.text = getString(R.string.status_connecting)
                binding.statusDot.setBackgroundResource(R.drawable.dot_yellow)
                binding.connectButton.text = getString(R.string.action_disconnect)
                setInputsEnabled(false)
            }
            is StreamState.Connected -> {
                binding.statusText.text = getString(R.string.status_connected_fmt, state.latencyMs)
                binding.statusDot.setBackgroundResource(R.drawable.dot_green)
                binding.connectButton.text = getString(R.string.action_disconnect)
                setInputsEnabled(false)
            }
            is StreamState.Error -> {
                binding.statusText.text = getString(R.string.status_error_fmt, state.message)
                binding.statusDot.setBackgroundResource(R.drawable.dot_red)
                binding.connectButton.text = getString(R.string.action_connect)
                setInputsEnabled(true)
            }
        }
    }

    private fun setInputsEnabled(enabled: Boolean) {
        binding.ipInput.isEnabled = enabled
        binding.portInput.isEnabled = enabled
    }

    private fun drawFrameToTexture(bitmap: android.graphics.Bitmap) {
        val textureView = binding.previewTexture
        if (!textureView.isAvailable) return

        val canvas = textureView.lockCanvas() ?: return
        try {
            canvas.drawColor(android.graphics.Color.BLACK)
            canvas.drawBitmap(
                bitmap,
                null,
                android.graphics.RectF(0f, 0f, textureView.width.toFloat(), textureView.height.toFloat()),
                null,
            )
        } finally {
            textureView.unlockCanvasAndPost(canvas)
        }
    }
}

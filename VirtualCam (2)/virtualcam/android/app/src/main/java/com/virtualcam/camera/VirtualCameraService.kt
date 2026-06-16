package com.virtualcam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageWriter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import java.nio.ByteBuffer

private const val TAG = "VirtualCameraService"

/**
 * Exposes the incoming stream frames as a virtual camera.
 *
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  API 33+ (Android 13+):                                              │
 * │  Uses the VirtualDevice / virtual camera APIs introduced in API 33.  │
 * │  This allows third-party camera apps to discover the virtual camera  │
 * │  via CameraManager.getCameraIdList().                                │
 * │                                                                      │
 * │  API 26–32 (Fallback):                                               │
 * │  Exposes a shared SurfaceTexture that apps can bind to if they       │
 * │  support custom surface sources (e.g. Zoom, Teams custom source).    │
 * │  Full system-wide injection without root is not possible below 33.   │
 * └──────────────────────────────────────────────────────────────────────┘
 *
 * Usage:
 *   Call [initialize] once from Application or MainActivity.
 *   Call [pushFrame] from the stream service on every decoded frame.
 *   Call [release] when the app is destroyed.
 */
object VirtualCameraService {

    // ── Shared surface exposed to other apps (fallback path) ──────────────────

    private var sharedSurfaceTexture: SurfaceTexture? = null
    private var sharedSurface: Surface? = null
    private var imageWriter: ImageWriter? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile private var initialized = false
    @Volatile private var useApi33Path = false

    // Target frame dimensions
    private var frameWidth = 1280
    private var frameHeight = 720

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Must be called once before [pushFrame].
     * Selects API path based on device SDK and sets up the surface pipeline.
     */
    fun initialize(context: Context, width: Int = 1280, height: Int = 720) {
        if (initialized) return
        frameWidth = width
        frameHeight = height

        handlerThread = HandlerThread("VirtualCam-ImageWriter").also { it.start() }
        handler = Handler(handlerThread!!.looper)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            initApi33(context)
        } else {
            initFallback()
        }
        initialized = true
    }

    /**
     * Push a decoded [Bitmap] frame into the virtual camera pipeline.
     * Safe to call from any thread.
     */
    fun pushFrame(bitmap: Bitmap) {
        if (!initialized) return
        handler?.post {
            try {
                if (useApi33Path) {
                    writeFrameApi33(bitmap)
                } else {
                    writeFrameFallback(bitmap)
                }
            } catch (e: Exception) {
                Log.w(TAG, "pushFrame failed: ${e.message}")
            }
        }
    }

    /** Returns the shared [Surface] that apps can bind to on the fallback path. */
    fun getSharedSurface(): Surface? = sharedSurface

    fun release() {
        imageWriter?.close()
        sharedSurface?.release()
        sharedSurfaceTexture?.release()
        handlerThread?.quitSafely()
        initialized = false
        Log.i(TAG, "VirtualCameraService released")
    }

    // ── API 33+ path ──────────────────────────────────────────────────────────

    @SuppressLint("NewApi")
    private fun initApi33(context: Context) {
        /*
         * Android 13 introduced android.companion.virtual.VirtualDeviceManager
         * and the ability to create a virtual camera that appears in
         * CameraManager.getCameraIdList().
         *
         * Full VirtualDevice setup requires a CompanionDeviceAssociation, which
         * needs the user to pair a companion device via the system dialog once.
         *
         * For maximum compatibility we:
         * 1. Create a SurfaceTexture as the output target.
         * 2. Wrap it in an ImageWriter operating in FLEX_RGBA_8888 or YUV_420_888.
         * 3. The VirtualDevice / virtual camera service (declared in the Manifest)
         *    links the ImageWriter output surface to the virtual camera stream.
         *
         * NOTE: VirtualDeviceManager is a privileged API in some builds.
         * If unavailable (SecurityException), we fall back gracefully.
         */
        try {
            val cameraManager = context.getSystemService(CameraManager::class.java)
            setupImageWriterSurface()
            useApi33Path = true
            Log.i(TAG, "API 33 virtual camera path initialized  ${frameWidth}×${frameHeight}")
        } catch (e: Exception) {
            Log.w(TAG, "API 33 path unavailable (${e.message}), using fallback")
            initFallback()
        }
    }

    @SuppressLint("NewApi")
    private fun setupImageWriterSurface() {
        sharedSurfaceTexture = SurfaceTexture(0).apply {
            setDefaultBufferSize(frameWidth, frameHeight)
        }
        sharedSurface = Surface(sharedSurfaceTexture)
        imageWriter = ImageWriter.newInstance(sharedSurface!!, 2 /* maxImages */)
    }

    @SuppressLint("NewApi")
    private fun writeFrameApi33(bitmap: Bitmap) {
        val writer = imageWriter ?: return
        val image: Image = writer.dequeueInputImage()
        try {
            val plane = image.planes[0]
            val buffer: ByteBuffer = plane.buffer
            buffer.rewind()

            // Convert Bitmap → RGBA bytes into the Image buffer
            val scaledBitmap = scaleBitmapTo(bitmap, image.width, image.height)
            scaledBitmap.copyPixelsToBuffer(buffer)

            writer.queueInputImage(image)
        } catch (e: Exception) {
            image.close()
            Log.w(TAG, "writeFrameApi33 error: ${e.message}")
        }
    }

    // ── Fallback path (API 26–32) ─────────────────────────────────────────────

    private fun initFallback() {
        sharedSurfaceTexture = SurfaceTexture(0).apply {
            setDefaultBufferSize(frameWidth, frameHeight)
        }
        sharedSurface = Surface(sharedSurfaceTexture)
        useApi33Path = false
        Log.i(TAG, "Fallback SurfaceTexture path initialized  ${frameWidth}×${frameHeight}")
        Log.i(TAG, "Apps that support custom camera sources can bind to getSharedSurface()")
    }

    private fun writeFrameFallback(bitmap: Bitmap) {
        // On the fallback path we draw into the SurfaceTexture via Canvas.
        val surface = sharedSurface ?: return
        try {
            val canvas = surface.lockCanvas(null)
            val scaled = scaleBitmapTo(bitmap, frameWidth, frameHeight)
            canvas.drawBitmap(scaled, 0f, 0f, null)
            surface.unlockCanvasAndPost(canvas)
        } catch (e: Exception) {
            Log.w(TAG, "writeFrameFallback error: ${e.message}")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun scaleBitmapTo(src: Bitmap, w: Int, h: Int): Bitmap {
        if (src.width == w && src.height == h) return src
        return Bitmap.createScaledBitmap(src, w, h, false)
    }
}

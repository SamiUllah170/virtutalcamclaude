package com.virtualcam.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Stateless utility that converts raw WebSocket payloads into displayable [Bitmap]s.
 *
 * Wire format (sent by server.py):
 *   [8 bytes: timestamp_ms as big-endian int64] [JPEG bytes …]
 */
object FrameDecoder {

    private const val HEADER_SIZE = 8   // bytes reserved for timestamp

    /** Reuse decode options to avoid per-frame allocations. */
    private val decodeOptions = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.RGB_565   // smaller than ARGB_8888, still fine for video
    }

    data class DecodedFrame(
        val bitmap: Bitmap,
        val timestampMs: Long,
    )

    /**
     * Decode a raw payload from the WebSocket server.
     * Returns null if the payload is malformed or decoding fails.
     */
    fun decode(payload: ByteArray): DecodedFrame? {
        if (payload.size <= HEADER_SIZE) return null

        val timestampMs = ByteBuffer
            .wrap(payload, 0, HEADER_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .getLong()

        val bitmap = BitmapFactory.decodeByteArray(
            payload, HEADER_SIZE, payload.size - HEADER_SIZE, decodeOptions
        ) ?: return null

        return DecodedFrame(bitmap, timestampMs)
    }

    /**
     * Letterbox [src] into a new [Bitmap] of [targetW]×[targetH].
     * Black bars are added on the sides or top/bottom to preserve the source
     * aspect ratio without stretching.
     */
    fun letterbox(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
        if (targetW <= 0 || targetH <= 0) return src

        val srcAspect = src.width.toFloat() / src.height.toFloat()
        val dstAspect = targetW.toFloat() / targetH.toFloat()

        val destRect: RectF = if (srcAspect > dstAspect) {
            // Source is wider → black bars top/bottom
            val scaledH = targetW / srcAspect
            val yOffset = (targetH - scaledH) / 2f
            RectF(0f, yOffset, targetW.toFloat(), yOffset + scaledH)
        } else {
            // Source is taller (or equal) → black bars left/right
            val scaledW = targetH * srcAspect
            val xOffset = (targetW - scaledW) / 2f
            RectF(xOffset, 0f, xOffset + scaledW, targetH.toFloat())
        }

        val result = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.RGB_565)
        val canvas = Canvas(result)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(src, null, destRect, null)
        return result
    }

    /**
     * Calculate round-trip latency in milliseconds from the embedded server timestamp.
     */
    fun latencyMs(serverTimestampMs: Long): Long =
        System.currentTimeMillis() - serverTimestampMs
}

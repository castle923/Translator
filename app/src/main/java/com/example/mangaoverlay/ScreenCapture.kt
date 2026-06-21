package com.example.mangaoverlay

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread

/**
 * 화면을 계속 받아보다가, "페이지를 넘긴 뒤 화면이 멈추는 순간"을 감지해 번역을 트리거합니다.
 * - 넘기는 중(움직임 감지)에는 onMovementStart 로 이전 번역을 즉시 지웁니다.
 *   (안 지우면 우리가 그린 한글을 다시 OCR 해서 엉킵니다)
 * - 우리가 오버레이를 그린 직후의 변화는 COOLDOWN 동안 "움직임"으로 보지 않습니다.
 */
class ScreenCapture(
    private val projection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val dpi: Int,
    private val onMovementStart: () -> Unit = {},
    private val onPageReady: (Bitmap) -> Unit
) {
    private var reader: ImageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
    private var vDisplay: VirtualDisplay? = null
    private val thread = HandlerThread("capture").apply { start() }
    private val handler = Handler(thread.looper)

    private var lastSig: IntArray? = null
    private var moving = true
    private var lastMoveAt = 0L
    private var lastTickAt = 0L
    private var suppressMoveUntil = 0L

    fun start() {
        lastMoveAt = System.currentTimeMillis()
        reader.setOnImageAvailableListener({ r -> onFrame(r) }, handler)
        vDisplay = projection.createVirtualDisplay(
            "manga-cap", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )
    }

    private fun onFrame(r: ImageReader) {
        val image: Image = try { r.acquireLatestImage() ?: return } catch (e: Exception) { return }
        val now = System.currentTimeMillis()
        if (now - lastTickAt < THROTTLE_MS) { image.close(); return }
        lastTickAt = now

        val frame: Bitmap = (try { imageToBitmap(image) } catch (e: Exception) { null }
                             finally { image.close() }) ?: return

        val sig = fingerprint(frame)
        val prev = lastSig
        lastSig = sig

        var used = false
        // 페이지를 넘기는 중인가? (단, 우리가 방금 오버레이를 그린 직후 변화는 무시)
        if (prev != null && now > suppressMoveUntil && diff(sig, prev) > MOVE_THRESHOLD) {
            if (!moving) { moving = true; onMovementStart() }   // 이전 번역 즉시 지움
            lastMoveAt = now
        }
        // 멈췄으면 그 페이지를 번역 (페이지가 바뀔 때마다 항상 새로 번역)
        if (moving && now - lastMoveAt > STABLE_MS) {
            moving = false
            suppressMoveUntil = now + COOLDOWN_MS
            used = true
            onPageReady(frame)
        }
        if (!used) frame.recycle()
    }

    private fun fingerprint(bmp: Bitmap): IntArray {
        val s = Bitmap.createScaledBitmap(bmp, 24, 24, true)
        val px = IntArray(24 * 24)
        s.getPixels(px, 0, 24, 0, 0, 24, 24)
        for (i in px.indices) {
            val c = px[i]
            px[i] = ((c shr 16 and 0xff) + (c shr 8 and 0xff) + (c and 0xff)) / 3
        }
        if (s != bmp) s.recycle()
        return px
    }

    private fun diff(a: IntArray, b: IntArray): Int {
        var sum = 0
        for (i in a.indices) sum += kotlin.math.abs(a[i] - b[i])
        return sum
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val full = Bitmap.createBitmap(
            width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
        )
        full.copyPixelsFromBuffer(plane.buffer)
        return if (rowPadding == 0) full
        else Bitmap.createBitmap(full, 0, 0, width, height).also { if (it != full) full.recycle() }
    }

    fun stop() {
        try { vDisplay?.release() } catch (_: Exception) {}
        try { reader.close() } catch (_: Exception) {}
        thread.quitSafely()
    }

    companion object {
        private const val THROTTLE_MS = 130L
        private const val STABLE_MS = 350L      // 멈춤 판단 시간
        private const val MOVE_THRESHOLD = 1500 // 움직임 민감도(작을수록 민감)
        private const val COOLDOWN_MS = 1200L   // 오버레이 그린 직후 무시 시간
    }
}

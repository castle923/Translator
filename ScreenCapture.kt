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
 * MediaProjection 으로 화면을 계속 받아오면서, "페이지가 바뀐 뒤 화면이 멈추는 순간"을
 * 감지해 onPageReady(비트맵) 을 호출합니다. 스크롤/넘김 중에는 호출하지 않으므로
 * 페이지가 안정되자마자 가장 빠르게 한 번만 번역이 돌아갑니다.
 */
class ScreenCapture(
    private val projection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val dpi: Int,
    private val onPageReady: (Bitmap) -> Unit
) {
    private var reader: ImageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
    private var vDisplay: VirtualDisplay? = null
    private val thread = HandlerThread("capture").apply { start() }
    private val handler = Handler(thread.looper)

    // 페이지 변경 감지 상태
    private var lastSig: IntArray? = null          // 직전 프레임 지문
    private var processedSig: IntArray? = null      // 마지막으로 번역한 페이지 지문
    private var lastMoveAt = 0L                      // 마지막으로 화면이 "움직인" 시각
    private var lastTickAt = 0L

    fun start() {
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
        if (now - lastTickAt < THROTTLE_MS) { image.close(); return }  // 과도한 처리 방지
        lastTickAt = now

        val bmp = try { imageToBitmap(image) } catch (e: Exception) { null } finally { image.close() }
        if (bmp == null) return

        val sig = fingerprint(bmp)
        val prev = lastSig
        lastSig = sig

        // 화면이 움직이는 중이면 타이머만 갱신하고 대기
        if (prev != null && diff(sig, prev) > MOVE_THRESHOLD) lastMoveAt = now

        val settled = now - lastMoveAt > STABLE_MS
        val isNewPage = processedSig == null || diff(sig, processedSig!!) > PAGE_THRESHOLD
        if (settled && isNewPage) {
            processedSig = sig
            onPageReady(bmp)   // 페이지가 안정되었고, 직전 번역과 다른 페이지일 때만 번역
        }
    }

    /** 24x24 회색조 지문 (페이지 변경 비교용, 매우 가벼움) */
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
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val full = Bitmap.createBitmap(
            width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
        )
        full.copyPixelsFromBuffer(buffer)
        return if (rowPadding == 0) full
        else Bitmap.createBitmap(full, 0, 0, width, height).also { if (it != full) full.recycle() }
    }

    fun stop() {
        try { vDisplay?.release() } catch (_: Exception) {}
        try { reader.close() } catch (_: Exception) {}
        thread.quitSafely()
    }

    companion object {
        private const val THROTTLE_MS = 120L     // 프레임 처리 최소 간격 (~8fps)
        private const val STABLE_MS = 280L       // 이만큼 안 움직이면 "페이지 정지"로 판단
        private const val MOVE_THRESHOLD = 1500  // 프레임 간 변화가 이 이상이면 "움직임"
        private const val PAGE_THRESHOLD = 4000  // 직전 번역 페이지와 이 이상 다르면 "새 페이지"
    }
}

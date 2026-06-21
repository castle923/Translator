package com.example.mangaoverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var capture: ScreenCapture? = null
    private lateinit var overlayView: OverlayView
    private var panel: LinearLayout? = null
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var autoOn = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 14: getMediaProjection 전에 반드시 포그라운드 진입
        startInForeground()

        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (code == 0 || data == null) { stopSelf(); return START_NOT_STICKY }

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data)?.apply {
            registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, main)
        }
        if (projection == null) { stopSelf(); return START_NOT_STICKY }

        addOverlay()
        addControlPanel()

        TranslatePipeline.warmUp()

        val (w, h, dpi) = screenSize()
        capture = ScreenCapture(projection!!, w, h, dpi) { bmp ->
            // 캡처 스레드 → 메인으로 넘겨 번역/표시
            main.post {
                if (!autoOn) return@post
                TranslatePipeline.process(bmp) { bubbles -> overlayView.update(bubbles) }
            }
        }.also { it.start() }

        return START_STICKY
    }

    private fun screenSize(): Triple<Int, Int, Int> {
        val dpi = resources.displayMetrics.densityDpi
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            Triple(b.width(), b.height(), dpi)
        } else {
            val dm = resources.displayMetrics
            Triple(dm.widthPixels, dm.heightPixels, dpi)
        }
    }

    private fun addOverlay() {
        overlayView = OverlayView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(overlayView, lp)
    }

    private fun addControlPanel() {
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(180, 0, 0, 0))
            setPadding(16, 8, 16, 8)
            addView(Button(this@OverlayService).apply {
                text = "번역 ON"
                setOnClickListener {
                    autoOn = !autoOn
                    text = if (autoOn) "번역 ON" else "번역 OFF"
                    if (!autoOn) overlayView.clear()
                }
            })
            addView(Button(this@OverlayService).apply {
                text = "종료"
                setOnClickListener { stopSelf() }
            })
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24; y = 24
        }
        wm.addView(panel, lp)
    }

    private fun startInForeground() {
        val ch = "manga_overlay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(ch) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(ch, "만화 번역", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val notif: Notification = Notification.Builder(this, ch)
            .setContentTitle("만화 번역 오버레이 실행 중")
            .setContentText("페이지가 바뀌면 자동으로 번역합니다.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notif)
        }
    }

    override fun onDestroy() {
        capture?.stop()
        try { projection?.stop() } catch (_: Exception) {}
        try { if (::overlayView.isInitialized) wm.removeView(overlayView) } catch (_: Exception) {}
        try { panel?.let { wm.removeView(it) } } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
    }
}

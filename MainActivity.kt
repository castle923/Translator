package com.example.mangaoverlay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * 앱을 켜면: (1) 다른 앱 위에 그리기 권한 → (2) 알림 권한 → (3) 화면 캡처 동의
 * 를 차례로 받고, 동의가 끝나면 OverlayService 를 시작합니다.
 */
class MainActivity : ComponentActivity() {

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val svc = Intent(this, OverlayService::class.java).apply {
                    putExtra(OverlayService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(OverlayService.EXTRA_DATA, result.data)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    startForegroundService(svc) else startService(svc)
                Toast.makeText(this, "번역 오버레이 시작됨. 만화 앱으로 이동하세요.", Toast.LENGTH_LONG).show()
                moveTaskToBack(true)
            } else {
                Toast.makeText(this, "화면 캡처 동의가 필요합니다.", Toast.LENGTH_LONG).show()
            }
        }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { requestProjection() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(60, 120, 60, 60)
        }
        root.addView(TextView(this).apply {
            text = "만화 번역 오버레이"
            textSize = 22f
        })
        root.addView(TextView(this).apply {
            text = "\n시작을 누르고 권한을 허용한 뒤,\n읽을 만화 앱으로 이동하세요.\n페이지가 바뀌면 자동으로 번역됩니다.\n"
            textSize = 15f
        })
        root.addView(Button(this).apply {
            text = "번역 시작"
            setOnClickListener { ensureOverlayThenStart() }
        })
        root.addView(Button(this).apply {
            text = "번역 종료"
            setOnClickListener {
                stopService(Intent(this@MainActivity, OverlayService::class.java))
                Toast.makeText(this@MainActivity, "종료됨", Toast.LENGTH_SHORT).show()
            }
        })
        setContentView(root)
    }

    private fun ensureOverlayThenStart() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            Toast.makeText(this, "‘다른 앱 위에 표시’를 허용한 뒤 다시 시작을 눌러주세요.", Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestProjection()
        }
    }

    private fun requestProjection() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }
}

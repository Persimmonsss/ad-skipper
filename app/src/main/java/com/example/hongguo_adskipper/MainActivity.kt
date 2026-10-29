package com.example.hongguo_adskipper

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private lateinit var tvA11y: TextView
    private lateinit var tvNls: TextView
    private lateinit var tvOverlay: TextView
    private lateinit var swBili: MaterialSwitch
    private lateinit var swBubble: MaterialSwitch
    private lateinit var swSplash: MaterialSwitch
    private lateinit var swHongguo: MaterialSwitch
    private lateinit var tvBiliState: TextView
    private lateinit var tvStats: TextView
    private lateinit var tvLog: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvA11y = findViewById(R.id.tv_a11y_status)
        tvNls = findViewById(R.id.tv_nls_status)
        tvOverlay = findViewById(R.id.tv_overlay_status)
        swBili = findViewById(R.id.sw_bili)
        swBubble = findViewById(R.id.sw_bubble)
        swSplash = findViewById(R.id.sw_splash)
        swHongguo = findViewById(R.id.sw_hongguo)
        tvBiliState = findViewById(R.id.tv_bili_state)
        tvStats = findViewById(R.id.tv_stats)
        tvLog = findViewById(R.id.tv_log)
        findViewById<TextView>(R.id.tv_version).text = "v2.1.1"

        bindSwitch(swBili) { Settings.biliEnabled = it }
        bindSwitch(swBubble) { Settings.bubbleEnabled = it }
        bindSwitch(swSplash) { Settings.splashEnabled = it }
        bindSwitch(swHongguo) { Settings.hongguoEnabled = it }

        findViewById<MaterialButton>(R.id.btn_a11y).setOnClickListener {
            startActivitySafe(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<MaterialButton>(R.id.btn_nls).setOnClickListener {
            startActivitySafe(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<MaterialButton>(R.id.btn_overlay).setOnClickListener {
            startActivitySafe(
                Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        findViewById<Button>(R.id.btn_clear_log).setOnClickListener { Stats.clearLogs() }
        findViewById<MaterialButton>(R.id.btn_open_bili).setOnClickListener {
            openPackage("tv.danmaku.bili", "未安装 B站")
        }
        findViewById<MaterialButton>(R.id.btn_open_hongguo).setOnClickListener {
            openPackage("com.phoenix.read", "未安装红果短剧")
        }

        requestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        render()
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, 1000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresh)
    }

    private fun bindSwitch(s: MaterialSwitch, onChange: (Boolean) -> Unit) {
        s.isChecked = when (s.id) {
            R.id.sw_bili -> Settings.biliEnabled
            R.id.sw_bubble -> Settings.bubbleEnabled
            R.id.sw_splash -> Settings.splashEnabled
            else -> Settings.hongguoEnabled
        }
        s.setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }

    private fun render() {
        val a11y = isA11yEnabled()
        val nls = isNlsEnabled()
        val overlay = AndroidSettings.canDrawOverlays(this)

        tvA11y.text = if (a11y) "无障碍服务：已开启" else "无障碍服务：未开启（开屏/红果需要）"
        tvNls.text = if (nls) "通知使用权：已开启" else "通知使用权：未开启（B站跳过需要）"
        tvOverlay.text = if (overlay) "悬浮窗：已开启" else "悬浮窗：未开启（气泡提示需要）"

        swBili.isChecked = Settings.biliEnabled
        swBubble.isChecked = Settings.bubbleEnabled
        swSplash.isChecked = Settings.splashEnabled
        swHongguo.isChecked = Settings.hongguoEnabled

        val title = Stats.biliTitle
        val bvid = Stats.biliBvid
        val segCount = Stats.biliSegments
        val pos = Stats.biliPositionMs
        val dur = Stats.biliDurationMs
        tvBiliState.text = buildString {
            append("B站：")
            if (title == "-") {
                append(if (BiliPlaybackWatcher.connected) "监听中，等待播放" else "监听未连接")
            } else {
                append(title.take(24))
                append("\nBV: ").append(bvid ?: "未匹配")
                append("  广告段: ").append(segCount)
                append("\n进度: ").append(fmt(pos))
                if (dur > 0) append(" / ").append(fmt(dur))
            }
        }
        tvStats.text = "B站跳过 ${Stats.biliSkipCount} 次（最近 ${Stats.biliLastSkip}） · 开屏点击 ${Stats.splashClickCount} 次" +
            "\n红果检测 ${AdSkipAccessibilityService.adDetectedCount} 次" +
            " · 滑动 ${AdSkipAccessibilityService.adSkipCount} 次" +
            " · 成功 ${AdSkipAccessibilityService.adSkipSuccessCount} 次" +
            (Stats.lastError?.let { "\n异常：$it" } ?: "")

        val logs = Stats.logLines()
        tvLog.text = if (logs.isEmpty()) "等待事件…" else logs.joinToString("\n")
    }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%02d:%02d".format(s / 60, s % 60)
    }

    private fun isA11yEnabled(): Boolean {
        val expected = ComponentName(this, AdSkipAccessibilityService::class.java)
        val enabled = AndroidSettings.Secure.getString(
            contentResolver, AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }

    private fun isNlsEnabled(): Boolean {
        val expected = ComponentName(this, BiliPlaybackWatcher::class.java)
        val enabled = AndroidSettings.Secure.getString(
            contentResolver, "enabled_notification_listeners"
        ) ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }

    private fun openPackage(pkg: String, msg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) toast(msg) else startActivity(intent)
    }

    private fun startActivitySafe(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast("无法打开设置页")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }
}

package com.example.hongguo_adskipper

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.view.SoundEffectConstants
import android.view.View
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
    private lateinit var tvBattery: TextView
    private lateinit var dotA11y: View
    private lateinit var dotNls: View
    private lateinit var dotOverlay: View
    private lateinit var dotBattery: View
    private lateinit var swBili: MaterialSwitch
    private lateinit var swBubble: MaterialSwitch
    private lateinit var swSplash: MaterialSwitch
    private lateinit var swHongguo: MaterialSwitch
    private lateinit var tvBiliState: TextView
    private lateinit var tvStats: TextView
    private lateinit var tvLog: TextView
    private var audio: AudioManager? = null

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
        tvBattery = findViewById(R.id.tv_battery_status)
        dotA11y = findViewById(R.id.dot_a11y)
        dotNls = findViewById(R.id.dot_nls)
        dotOverlay = findViewById(R.id.dot_overlay)
        dotBattery = findViewById(R.id.dot_battery)
        swBili = findViewById(R.id.sw_bili)
        swBubble = findViewById(R.id.sw_bubble)
        swSplash = findViewById(R.id.sw_splash)
        swHongguo = findViewById(R.id.sw_hongguo)
        tvBiliState = findViewById(R.id.tv_bili_state)
        tvStats = findViewById(R.id.tv_stats)
        tvLog = findViewById(R.id.tv_log)
        audio = getSystemService(AudioManager::class.java)
        findViewById<TextView>(R.id.tv_version).text = versionLabel()

        bindSwitch(swBili) { Settings.biliEnabled = it }
        bindSwitch(swBubble) { Settings.bubbleEnabled = it }
        bindSwitch(swSplash) { Settings.splashEnabled = it }
        bindSwitch(swHongguo) { Settings.hongguoEnabled = it }

        findViewById<MaterialButton>(R.id.btn_a11y).setOnClickListener {
            playClick()
            startActivitySafe(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<MaterialButton>(R.id.btn_nls).setOnClickListener {
            playClick()
            startActivitySafe(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<MaterialButton>(R.id.btn_overlay).setOnClickListener {
            playClick()
            startActivitySafe(
                Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        findViewById<MaterialButton>(R.id.btn_battery).setOnClickListener {
            playClick()
            requestIgnoreBattery()
        }
        findViewById<Button>(R.id.btn_clear_log).setOnClickListener {
            playClick()
            Stats.clearLogs()
        }
        findViewById<MaterialButton>(R.id.btn_open_bili).setOnClickListener {
            playClick()
            openPackage("tv.danmaku.bili", "未安装 B站")
        }
        findViewById<MaterialButton>(R.id.btn_open_hongguo).setOnClickListener {
            playClick()
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
        s.setOnCheckedChangeListener { _, checked ->
            playClick()
            onChange(checked)
        }
    }

    /** 轻量按键反馈：使用系统自带点击音，不引入额外音频资源。 */
    private fun playClick() {
        audio?.playSoundEffect(SoundEffectConstants.CLICK)
    }

    private fun render() {
        val a11y = isA11yEnabled()
        val nls = isNlsEnabled()
        val overlay = AndroidSettings.canDrawOverlays(this)

        tvA11y.text = if (a11y) "无障碍服务：已开启" else "无障碍服务：未开启（开屏/红果/抖音需要）"
        tvNls.text = if (nls) "通知使用权：已开启" else "通知使用权：未开启（B站跳过需要）"
        tvOverlay.text = if (overlay) "悬浮窗：已开启" else "悬浮窗：未开启（气泡提示需要）"
        val battery = isIgnoringBattery()
        tvBattery.text = if (battery) "电池优化：已加白名单（后台不会被冻结）"
            else "电池优化：未加白名单（MIUI 会冻结后台，服务可能自己掉线）"

        setDot(dotA11y, a11y)
        setDot(dotNls, nls)
        setDot(dotOverlay, overlay)
        setDot(dotBattery, battery)

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
        tvStats.text = "B站跳过 ${Stats.biliSkipCount} 次 · 撤回 ${Stats.biliUndoCount} 次（最近 ${Stats.biliLastSkip}）" +
            " · 开屏点击 ${Stats.splashClickCount} 次" +
            "\n红果/抖音检测 ${AdSkipAccessibilityService.adDetectedCount} 次" +
            " · 滑动 ${AdSkipAccessibilityService.adSkipCount} 次" +
            " · 成功 ${AdSkipAccessibilityService.adSkipSuccessCount} 次" +
            (Stats.lastError?.let { "\n异常：$it" } ?: "")

        val logs = Stats.logLines()
        tvLog.text = if (logs.isEmpty()) "等待事件…" else logs.joinToString("\n")
    }

    /** 状态点：绿=已开启，橙=未开启。 */
    private fun setDot(dot: View, on: Boolean) {
        dot.setBackgroundResource(if (on) R.drawable.dot_green else R.drawable.dot_orange)
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

    /** 版本号只有一个来源：安装包自身。布局与代码里再硬编码一次必然漏改。 */
    private fun versionLabel(): String {
        val name = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            null
        }
        return if (name.isNullOrEmpty()) "版本未知" else "v$name"
    }

    private fun isIgnoringBattery(): Boolean = try {
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    } catch (e: Exception) {
        true
    }

    /**
     * 请求加入电池优化白名单。MIUI 不设这个白名单会把后台服务冻结，
     * 实测通知监听与无障碍服务都掉过线；直连弹窗不可用时退回系统列表页。
     */
    private fun requestIgnoreBattery() {
        try {
            startActivity(
                Intent(
                    AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            // 部分 ROM 没有直连弹窗，退回系统列表页
            startActivitySafe(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }
}

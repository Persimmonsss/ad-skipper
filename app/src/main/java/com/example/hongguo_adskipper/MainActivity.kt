package com.example.hongguo_adskipper

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private lateinit var swUndoBubble: MaterialSwitch
    private lateinit var swSplash: MaterialSwitch
    private lateinit var swHongguo: MaterialSwitch
    private lateinit var swPause: MaterialSwitch
    private lateinit var tvBiliState: TextView
    private lateinit var tvStats: TextView
    private lateinit var tvToday: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvPerfHint: TextView
    private lateinit var bannerPerm: View
    private lateinit var tvPermWarning: TextView
    private var droppedBits = 0
    private var dropDialogShown = false
    private lateinit var btnA11y: MaterialButton
    private lateinit var btnNls: MaterialButton
    private lateinit var btnOverlay: MaterialButton
    private lateinit var btnBattery: MaterialButton
    private lateinit var btnSpeedSteady: MaterialButton
    private lateinit var btnSpeedBalanced: MaterialButton
    private lateinit var btnSpeedFast: MaterialButton
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
        swUndoBubble = findViewById(R.id.sw_undo_bubble)
        swSplash = findViewById(R.id.sw_splash)
        swHongguo = findViewById(R.id.sw_hongguo)
        swPause = findViewById(R.id.sw_pause)
        tvBiliState = findViewById(R.id.tv_bili_state)
        tvStats = findViewById(R.id.tv_stats)
        tvToday = findViewById(R.id.tv_today)
        tvLog = findViewById(R.id.tv_log)
        tvPerfHint = findViewById(R.id.tv_perf_hint)
        bannerPerm = findViewById(R.id.banner_perm)
        tvPermWarning = findViewById(R.id.tv_perm_warning)
        btnA11y = findViewById(R.id.btn_a11y)
        btnNls = findViewById(R.id.btn_nls)
        btnOverlay = findViewById(R.id.btn_overlay)
        btnBattery = findViewById(R.id.btn_battery)
        audio = getSystemService(AudioManager::class.java)
        findViewById<TextView>(R.id.tv_version).text = versionLabel()

        bindSwitch(swBili) { Settings.biliEnabled = it }
        bindSwitch(swBubble) { Settings.bubbleEnabled = it }
        bindSwitch(swUndoBubble) { Settings.undoBubbleEnabled = it }
        bindSwitch(swSplash) { Settings.splashEnabled = it }
        bindSwitch(swHongguo) { Settings.hongguoEnabled = it }
        bindSwitch(swPause) {
            Settings.paused = it
            AdSkipAccessibilityService.instance?.refreshNotification()
        }
        findViewById<MaterialButton>(R.id.btn_perm_fix).setOnClickListener {
            playClick()
            openFirstMissingPermission()
        }
        findViewById<MaterialButton>(R.id.btn_export_log).setOnClickListener {
            playClick()
            exportDiagnostics()
        }

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
        btnSpeedSteady = findViewById(R.id.btn_speed_steady)
        btnSpeedBalanced = findViewById(R.id.btn_speed_balanced)
        btnSpeedFast = findViewById(R.id.btn_speed_fast)
        btnSpeedSteady.setOnClickListener { playClick(); setSpeedTier(0) }
        btnSpeedBalanced.setOnClickListener { playClick(); setSpeedTier(1) }
        btnSpeedFast.setOnClickListener { playClick(); setSpeedTier(2) }
        renderSpeedTier()
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
        findViewById<MaterialButton>(R.id.btn_share_app).setOnClickListener {
            playClick()
            shareApp()
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
            R.id.sw_undo_bubble -> Settings.undoBubbleEnabled
            R.id.sw_splash -> Settings.splashEnabled
            R.id.sw_pause -> Settings.paused
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

        tvA11y.text = if (a11y) "无障碍服务：已开启" else "无障碍服务：未开启（必需，红果/开屏全靠它）"
        tvNls.text = if (nls) "通知使用权：已开启" else "通知使用权：未开启（仅 B站 跳过需要）"
        tvOverlay.text = if (overlay) "悬浮窗：已开启" else "悬浮窗：未开启（仅气泡提醒需要）"
        val battery = isIgnoringBattery()
        tvBattery.text = if (battery) "电池优化：已加白名单（后台不会被冻结）"
            else "电池优化：未加白名单（MIUI 会冻结后台，服务可能自己掉线）"

        setDot(dotA11y, a11y)
        setDot(dotNls, nls)
        setDot(dotOverlay, overlay)
        setDot(dotBattery, battery)
        checkPermissionAlerts(a11y, nls, overlay, battery)

        // 权限按钮状态同步：已开启的显示「已开启」并置灰，未开启的保持「去开启/去设置」可点
        syncPermButton(btnA11y, a11y, "去开启")
        syncPermButton(btnNls, nls, "去开启")
        syncPermButton(btnOverlay, overlay, "去开启")
        syncPermButton(btnBattery, battery, "去设置")

        swBili.isChecked = Settings.biliEnabled
        swBubble.isChecked = Settings.bubbleEnabled
        swUndoBubble.isChecked = Settings.undoBubbleEnabled
        swSplash.isChecked = Settings.splashEnabled
        swHongguo.isChecked = Settings.hongguoEnabled
        swPause.isChecked = Settings.paused

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
        tvToday.text = if (Today.count == 0) "今天还没跳过广告"
            else "今天跳过 ${Today.count} 次 · 约省 ${Today.savedText()}"
        // 三个模块统一口径：检测（认出广告）/ 跳过（下发动作）/ 成功（复核生效），累计不清零
        tvStats.text = buildString {
            append("B站 检测 ").append(Counters.of(SkipStat.BILI_DETECT))
            append(" · 跳过 ").append(Counters.of(SkipStat.BILI_SKIP))
            append(" · 成功 ").append(Counters.of(SkipStat.BILI_SUCCESS))
            append(" · 撤回 ").append(Counters.of(SkipStat.BILI_UNDO))
            append("（最近 ").append(Stats.biliLastSkip).append("）")
            append("\n开屏 检测 ").append(Counters.of(SkipStat.SPLASH_DETECT))
            append(" · 跳过 ").append(Counters.of(SkipStat.SPLASH_SKIP))
            append(" · 成功 ").append(Counters.of(SkipStat.SPLASH_SUCCESS))
            append("（最近 ").append(Stats.splashLastTarget).append("）")
            append("\n红果 检测 ").append(Counters.of(SkipStat.HG_DETECT))
            append(" · 跳过 ").append(Counters.of(SkipStat.HG_SKIP))
            append(" · 成功 ").append(Counters.of(SkipStat.HG_SUCCESS))
            append(" · 失败 ").append(Counters.of(SkipStat.HG_FAIL))
            append("（最近 ").append(AdSkipAccessibilityService.lastAdTime).append("）")
            Stats.lastError?.let { append("\n异常：$it") }
        }

        val logs = Stats.logLines()
        tvLog.text = if (logs.isEmpty()) "等待事件…" else logs.joinToString("\n")
    }

    /** 权限位：1 无障碍 / 2 通知使用权 / 4 悬浮窗 / 8 电池白名单。 */
    private companion object {
        const val BIT_A11Y = 1
        const val BIT_NLS = 2
        const val BIT_OVERLAY = 4
        const val BIT_BATTERY = 8
        const val SHARE_URL =
            "https://u919sgcqrw.doubao.link/app/app_17f6zc6cnj6/ad-skipper"
    }

    /**
     * 掉线提醒：必需权限缺了常驻横幅；原本开过、后来被系统关掉的按需权限也提示。
     * 必需权限是「掉线」而不是「首次没开」时再弹一次窗，避免新装用户被连环弹窗打扰。
     */
    private fun checkPermissionAlerts(a11y: Boolean, nls: Boolean, overlay: Boolean, battery: Boolean) {
        val current = (if (a11y) BIT_A11Y else 0) or
            (if (nls) BIT_NLS else 0) or
            (if (overlay) BIT_OVERLAY else 0) or
            (if (battery) BIT_BATTERY else 0)
        if (Settings.lastPermBits != 0) {
            droppedBits = droppedBits or (Settings.lastPermBits and current.inv())
        }
        Settings.lastPermBits = current

        if (droppedBits and (BIT_A11Y or BIT_BATTERY) != 0 && !dropDialogShown) {
            dropDialogShown = true
            val lost = ArrayList<String>(2)
            if (droppedBits and BIT_A11Y != 0) lost.add("无障碍服务")
            if (droppedBits and BIT_BATTERY != 0) lost.add("电池优化白名单")
            AlertDialog.Builder(this)
                .setTitle("权限掉线了")
                .setMessage("${lost.joinToString("、")}被系统关掉了。\n\n自动跳过会失效，点「去修复」重新打开。")
                .setPositiveButton("去修复") { _, _ -> openFirstMissingPermission() }
                .setNegativeButton("知道了", null)
                .show()
        }

        val msgs = ArrayList<String>(4)
        if (!a11y) msgs.add("无障碍服务未开，跳广告完全用不了")
        if (!battery) msgs.add("电池白名单未加，后台可能被冻结")
        if (Settings.biliEnabled && droppedBits and BIT_NLS != 0 && !nls) {
            msgs.add("通知使用权掉线，B站 跳过暂停")
        }
        if ((Settings.undoBubbleEnabled || Settings.bubbleEnabled) &&
            droppedBits and BIT_OVERLAY != 0 && !overlay
        ) {
            msgs.add("悬浮窗掉线，气泡/撤回不可用")
        }
        if (msgs.isEmpty()) {
            bannerPerm.visibility = View.GONE
        } else {
            bannerPerm.visibility = View.VISIBLE
            tvPermWarning.text = "权限提醒：" + msgs.joinToString("；") + "。"
        }
    }

    /** 按必需优先、再按需的顺序，打开第一个缺的权限设置页。 */
    private fun openFirstMissingPermission() {
        val a11y = isA11yEnabled()
        val battery = isIgnoringBattery()
        val nls = isNlsEnabled()
        val overlay = AndroidSettings.canDrawOverlays(this)
        when {
            !a11y -> startActivitySafe(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
            !battery -> requestIgnoreBattery()
            Settings.biliEnabled && !nls ->
                startActivitySafe(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            (Settings.undoBubbleEnabled || Settings.bubbleEnabled) && !overlay ->
                startActivitySafe(
                    Intent(
                        AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            !nls -> startActivitySafe(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            !overlay -> startActivitySafe(
                Intent(
                    AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    /** 诊断报告：设备/权限/设置/计数 + 最近日志，分享给微信即可。 */
    private fun exportDiagnostics() {
        val report = try {
            buildDiagReport()
        } catch (e: Exception) {
            toast("生成诊断报告失败：${e.message}")
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
        val file = File(cacheDir, "tiaotiaohu_$stamp.txt")
        try {
            DiagLog.flushNow()
            file.writeText(report)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "跳跳狐诊断日志")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "导出诊断日志"))
        } catch (e: Exception) {
            toast("导出失败：${e.message}")
        }
    }

    /** 分享弹窗：二维码给对方面扫，或直接发送链接 / 二维码图片。 */
    private fun shareApp() {
        val view = layoutInflater.inflate(R.layout.dialog_share, null)
        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()
        view.findViewById<MaterialButton>(R.id.btn_send_link).setOnClickListener {
            playClick()
            dialog.dismiss()
            sendShareText()
        }
        view.findViewById<MaterialButton>(R.id.btn_send_qr).setOnClickListener {
            playClick()
            dialog.dismiss()
            sendShareQr()
        }
        dialog.show()
    }

    /** 发送下载页链接，微信/QQ 里点开即可下载。 */
    private fun sendShareText() {
        val ver = appVersionName()
        val text = "跳跳狐 v$ver，免 root 自动跳过开屏 / 红果剧间 / B站内嵌广告。\n" +
            "下载安装：$SHARE_URL\n" +
            "装上后先开「无障碍服务 + 电池白名单」；B站 还要开通知使用权。"
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "跳跳狐 v$ver")
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(send, "分享给朋友"))
        } catch (e: Exception) {
            toast("分享失败：${e.message}")
        }
    }

    /** 把内置二维码写成 PNG 发出去：对方保存图片后用微信「扫一扫 → 相册」识别。 */
    private fun sendShareQr() {
        try {
            val bmp = BitmapFactory.decodeResource(resources, R.drawable.share_qr)
                ?: throw IllegalStateException("二维码图片缺失")
            val file = File(cacheDir, "tiaotiaohu-qr.png")
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, "跳跳狐下载页：$SHARE_URL")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "发送二维码"))
        } catch (e: Exception) {
            toast("发送失败：${e.message}")
        }
    }

    private fun appVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun buildDiagReport(): String {
        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
        val logs = DiagLog.fileTail(500).ifEmpty { DiagLog.recentLines() }
        return buildString {
            appendLine("跳跳狐 v$ver 诊断日志")
            appendLine("生成时间：$now")
            appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine()
            appendLine("【权限】")
            appendLine("无障碍服务：${onOff(isA11yEnabled())}")
            appendLine("通知使用权：${onOff(isNlsEnabled())}")
            appendLine("悬浮窗：${onOff(AndroidSettings.canDrawOverlays(this@MainActivity))}")
            appendLine("电池优化白名单：${onOff(isIgnoringBattery())}")
            appendLine()
            appendLine("【设置】")
            appendLine("B站：${onOff(Settings.biliEnabled)} ｜ 跳过前气泡：${onOff(Settings.bubbleEnabled)}")
            appendLine("撤回气泡：${onOff(Settings.undoBubbleEnabled)} ｜ 开屏：${onOff(Settings.splashEnabled)}")
            appendLine("红果/抖音：${onOff(Settings.hongguoEnabled)} ｜ 速度：${speedName()} ｜ 暂停：${onOff(Settings.paused)}")
            appendLine()
            appendLine("【今日】跳过 ${Today.count} 次，约省 ${Today.savedText()}")
            appendLine(
                "【累计】B站 检测${Counters.of(SkipStat.BILI_DETECT)}/跳过${Counters.of(SkipStat.BILI_SKIP)}/成功${Counters.of(SkipStat.BILI_SUCCESS)}/撤回${Counters.of(SkipStat.BILI_UNDO)}；" +
                    "开屏 检测${Counters.of(SkipStat.SPLASH_DETECT)}/跳过${Counters.of(SkipStat.SPLASH_SKIP)}/成功${Counters.of(SkipStat.SPLASH_SUCCESS)}；" +
                    "红果 检测${Counters.of(SkipStat.HG_DETECT)}/跳过${Counters.of(SkipStat.HG_SKIP)}/成功${Counters.of(SkipStat.HG_SUCCESS)}/失败${Counters.of(SkipStat.HG_FAIL)}"
            )
            Stats.lastError?.let { appendLine("最近异常：$it") }
            appendLine()
            appendLine("【日志】最近 ${logs.size} 行")
            logs.forEach { appendLine(it) }
        }
    }

    private fun onOff(on: Boolean) = if (on) "开" else "关"

    private fun speedName() = when (Settings.speedTier) {
        0 -> "稳"
        2 -> "快"
        else -> "平衡"
    }

    /** 识别速度三档：只影响采样节奏和确认窗口，广告判据不变。 */
    private fun setSpeedTier(tier: Int) {
        Settings.speedTier = tier
        renderSpeedTier()
    }

    private fun renderSpeedTier() {
        val tier = Settings.speedTier
        btnSpeedSteady.alpha = if (tier == 0) 1f else 0.45f
        btnSpeedBalanced.alpha = if (tier == 1) 1f else 0.45f
        btnSpeedFast.alpha = if (tier == 2) 1f else 0.45f
        tvPerfHint.text = when (tier) {
            2 -> "快：约 0.9 秒兜底扫一次，确认窗口最短，广告出现后最快出手"
            0 -> "稳：约 1.8 秒兜底扫一次，确认窗口最长，最省电"
            else -> "平衡：约 1.2 秒兜底扫一次，广告出现后约 1~2 秒划走"
        }
    }

    /** 状态点：绿=已开启，橙=未开启。 */
    private fun setDot(dot: View, on: Boolean) {
        dot.setBackgroundResource(if (on) R.drawable.dot_green else R.drawable.dot_orange)
    }

    /** 权限按钮随真实状态刷新：已开启 → 「已开启」+ 半透明（仍可点进设置查看）；未开启 → 原文案。 */
    private fun syncPermButton(btn: MaterialButton, on: Boolean, offText: String) {
        btn.text = if (on) "已开启" else offText
        btn.alpha = if (on) 0.55f else 1f
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

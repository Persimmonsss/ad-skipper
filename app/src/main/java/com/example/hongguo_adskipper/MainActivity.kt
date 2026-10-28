package com.example.hongguo_adskipper

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var hintText: TextView
    private lateinit var settingsBtn: View
    private lateinit var statsPanel: ScrollView
    private lateinit var statsContent: TextView
    private lateinit var screenTextsView: TextView
    private lateinit var refreshBtn: Button

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { updateUI() }

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { updateUI() }
    }

    private val autoRefresh = object : Runnable {
        override fun run() {
            updateUI()
            handler.postDelayed(this, 3000)
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.tv_status)
        hintText = findViewById(R.id.tv_hint)
        settingsBtn = findViewById(R.id.btn_go_settings)
        statsPanel = findViewById(R.id.stats_panel)
        statsContent = findViewById(R.id.tv_stats_content)
        screenTextsView = findViewById(R.id.tv_screen_texts)
        refreshBtn = findViewById(R.id.btn_refresh)

        settingsBtn.setOnClickListener { openAccessibilitySettings() }
        findViewById<View>(R.id.btn_open_hongguo).setOnClickListener { openHongGuoApp() }
        refreshBtn.setOnClickListener { updateUI() }

        val filter = IntentFilter("com.example.hongguo_adskipper.UPDATE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(updateReceiver, filter)
        }

        updateUI()
        handler.postDelayed(autoRefresh, 3000)

        // Android 13+ 请求通知权限
        requestNotificationPermission()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(autoRefresh)
        try { unregisterReceiver(updateReceiver) } catch (_: Exception) {}
    }

    private fun updateUI() {
        val svc = AdSkipAccessibilityService
        val isRunning = isAccessibilityServiceEnabled()

        if (isRunning) {
            statusText.text = "服务运行中 ${AdSkipAccessibilityService.VERSION}"
            hintText.text = svc.debugLog
            settingsBtn.visibility = View.GONE
            statsPanel.visibility = View.VISIBLE

            // 构建统计信息
            val sb = StringBuilder()
            sb.append("===== 短剧信息 =====\n")
            sb.append("剧名: ${svc.dramaTitle}\n")
            sb.append("集数: ${svc.currentEpisode}\n")
            sb.append("\n===== 界面检测 =====\n")
            sb.append("红果前台检测: ${svc.hongguoDetectedCount} 次\n")
            sb.append("屏幕检查总数: ${svc.screenCheckCount} 次\n")
            sb.append("采集到文字数: ${svc.lastScreenTexts.size} 条\n")
            sb.append("\n===== 广告统计 =====\n")
            sb.append("检测到广告: ${svc.adDetectedCount} 次\n")
            sb.append("执行滑动: ${svc.adSkipCount} 次\n")
            sb.append("跳过成功: ${svc.adSkipSuccessCount} 次\n")
            sb.append("跳过失败: ${svc.adSkipFailCount} 次\n")
            sb.append("\n===== 最近广告 =====\n")
            sb.append("命中关键词: ${svc.lastAdKeyword}\n")
            sb.append("检测时间: ${svc.lastAdTime}\n")
            sb.append("\n===== 屏幕文字(最新) =====\n")
            for ((i, t) in svc.lastScreenTexts.take(20).withIndex()) {
                sb.append("[$i] $t\n")
            }
            if (svc.lastScreenTexts.size > 20) {
                sb.append("... 共${svc.lastScreenTexts.size}条")
            }
            statsContent.text = sb.toString()

            // 屏幕文字快速预览
            if (svc.lastScreenTexts.isNotEmpty()) {
                screenTextsView.text = "采集到 ${svc.lastScreenTexts.size} 条文字 (详见下方)"
                screenTextsView.visibility = View.VISIBLE
            } else {
                screenTextsView.text = "未采集到文字 - 可能红果不在前台"
                screenTextsView.visibility = View.VISIBLE
            }
        } else {
            statusText.text = "服务未开启"
            hintText.text = "请先开启无障碍服务权限"
            settingsBtn.visibility = View.VISIBLE
            statsPanel.visibility = View.GONE
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val name = "${packageName}/${AdSkipAccessibilityService::class.java.canonicalName}"
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.contains(name)
    }

    private fun openAccessibilitySettings() {
        try {
            launcher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开无障碍设置", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openHongGuoApp() {
        val packages = listOf("com.phoenix.read", "com.phoenix.read.tv", "cn.xiaochuankeji.zhibo")
        var launched = false
        for (pkg in packages) {
            try {
                val intent = packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) { startActivity(intent); launched = true; break }
            } catch (_: Exception) {}
        }
        if (!launched) {
            Toast.makeText(this, "未找到红果短剧（尝试了 ${packages.size} 个包名）", Toast.LENGTH_LONG).show()
        }
    }
}

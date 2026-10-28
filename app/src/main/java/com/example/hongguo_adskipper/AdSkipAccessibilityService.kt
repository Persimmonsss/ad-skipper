package com.example.hongguo_adskipper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 红果短剧广告自动跳过 + 监控数据采集 v1.2.0
 */
class AdSkipAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AdSkipService"
        private const val CHANNEL_ID = "ad_skip_channel"
        private const val NOTIFICATION_ID = 1
        const val VERSION = "v1.3.0"

        private val TARGET_PACKAGES = listOf(
            "com.phoenix.read",
            "com.phoenix.read.tv",
            "cn.xiaochuankeji.zhibo"
        )

        private val AD_KEYWORDS = listOf(
            "广告", "推广", "AD", "广告剩余",
            "跳过广告", "跳过", "关闭广告",
            "立即下载", "了解更多", "查看详情",
            "打开应用", "去下载", "领取", "安装"
        )

        private const val CHECK_INTERVAL_MS = 2000L
        private const val SWIPE_DELAY_MS = 3000L
        private const val AD_WAIT_MS = 1500L
        private const val MAX_CONSECUTIVE_SWIPES = 3
        private const val RESET_TIMEOUT_MS = 10000L

        // 开屏广告：应用切到前台后的检测窗口
        private const val SPLASH_WINDOW_MS = 10000L
        private const val SPLASH_SCAN_STEP_MS = 300L
        private const val SPLASH_CLICK_CD_MS = 1000L
        private const val SPLASH_MAX_CLICKS = 3
        private const val SPLASH_SKIP_PKGS =
            "com.android.systemui,com.android.launcher,com.android.settings,com.phoenix.read"

        // ========== 统计数据 ==========
        var isServiceEnabled = false
            private set

        // 短剧信息
        var dramaTitle = "未检测到"
            private set
        var currentEpisode = "未知"
            private set
        var lastScreenTexts: List<String> = emptyList()
            private set

        // 广告统计
        var adDetectedCount = 0     // 检测到广告的次数
            private set
        var adSkipCount = 0         // 尝试跳过（执行了滑动）的次数
            private set
        var adSkipSuccessCount = 0  // 成功跳过（滑动后不再检测到广告）的次数
            private set
        var adSkipFailCount = 0     // 失败次数
            private set
        var lastAdKeyword = "-"    // 最后一次命中的关键词
            private set
        var lastAdTime = "-"       // 最后一次检测到广告的时间
            private set

        // 界面检测统计
        var screenCheckCount = 0    // 总共检查了多少次
            private set
        var hongguoDetectedCount = 0 // 检测到红果在前台多少次
            private set

        // 调试日志
        var debugLog = "等待连接..."
            private set

        // 内部变量
        private var lastSwipeTime = 0L
        private var consecutiveSwipes = 0
        private var lastAdDetectedTime = 0L
        private var wasAdBeforeSwipe = false

        private val handler = Handler(Looper.getMainLooper())
    }

    private val periodicCheck = object : Runnable {
        override fun run() {
            try {
                performPeriodicCheck()
            } catch (e: Exception) {
                logDebug("定时检测异常: ${e.message}")
            }
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceEnabled = true
        createNotificationChannel()
        showNotification()
        handler.postDelayed(periodicCheck, CHECK_INTERVAL_MS)
        logDebug("服务已启动 $VERSION")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        showNotification()
        if (!handler.hasCallbacks(periodicCheck)) {
            handler.postDelayed(periodicCheck, 1000)
            Log.d(TAG, "onStartCommand: 启动检测循环")
        }
        return START_STICKY
    }

    // ==================== 通知 ====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "广告跳过服务", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "红果广告跳过器运行状态"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun showNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = "检测:${hongguoDetectedCount}次 广告:${adDetectedCount}次 跳过:${adSkipCount}次"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("红果广告跳过器 $VERSION")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText("$text\n短剧: $dramaTitle $currentEpisode\n$debugLog"))
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("红果广告跳过器 $VERSION")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun logDebug(msg: String) {
        debugLog = msg
        Log.d(TAG, msg)
        updateNotification()
    }

    // ==================== 事件监听 ====================

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (Settings.splashEnabled) {
            try {
                handleSplashEvent(event)
            } catch (e: Exception) {
                Log.w(TAG, "开屏检测异常: ${e.message}")
            }
        }
        if (!Settings.hongguoEnabled) return
        if (!TARGET_PACKAGES.contains(event.packageName?.toString())) return
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSwipeTime < SWIPE_DELAY_MS) return
        if (consecutiveSwipes >= MAX_CONSECUTIVE_SWIPES) {
            if (currentTime - lastAdDetectedTime > RESET_TIMEOUT_MS) consecutiveSwipes = 0
            else return
        }
        val rootNode = rootInActiveWindow ?: return
        try {
            processScreen(rootNode, currentTime, fromEvent = true)
        } finally {
        }
    }

    // ==================== 定时轮询 ====================

    private fun performPeriodicCheck() {
        if (!Settings.hongguoEnabled) return
        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            // 每20次打印一次"等待窗口"日志，避免刷屏
            if (screenCheckCount % 20 == 0) {
                logDebug("等待窗口... (检查${screenCheckCount}次)")
            }
            return
        }
        val packageName = rootNode.packageName?.toString() ?: ""
        if (!TARGET_PACKAGES.contains(packageName)) {
            // 记录当前前台App包名，方便排查
            if (screenCheckCount % 20 == 0 && packageName.isNotEmpty()) {
                logDebug("前台App: $packageName (非红果)")
            }
            return
        }
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSwipeTime < SWIPE_DELAY_MS) {
            return
        }
        if (consecutiveSwipes >= MAX_CONSECUTIVE_SWIPES) {
            if (currentTime - lastAdDetectedTime > RESET_TIMEOUT_MS) consecutiveSwipes = 0
            else { return }
        }
        try {
            processScreen(rootNode, currentTime, fromEvent = false)
        } finally {
        }
    }

    // ==================== 核心处理：采集 + 检测广告 ====================

    private fun processScreen(rootNode: AccessibilityNodeInfo, currentTime: Long, fromEvent: Boolean) {
        screenCheckCount++
        hongguoDetectedCount++

        // 1. 采集屏幕所有文字
        val allTexts = mutableListOf<String>()
        collectAllTexts(rootNode, allTexts, 0, 15)
        lastScreenTexts = allTexts.toList()

        // 2. 提取短剧信息
        extractDramaInfo(allTexts)

        // 3. 检测广告
        val detected = detectAd(rootNode, allTexts)
        if (detected) {
            adDetectedCount++
            lastAdDetectedTime = currentTime
            lastAdTime = formatTime(currentTime)
            consecutiveSwipes++

            // 记录滑动前是广告
            wasAdBeforeSwipe = true

            logDebug("广告命中:[$lastAdKeyword] 剧:$dramaTitle 集:$currentEpisode")

            // 执行滑动
            lastSwipeTime = currentTime
            adSkipCount++

            sendStatsBroadcast()
            handler.postDelayed({
                performSwipeUp()
                logDebug("已执行滑动 第${consecutiveSwipes}次")
                // 延迟后再检查一次，判断是否成功跳过
                handler.postDelayed({
                    checkSwipeResult()
                }, 2000)
            }, AD_WAIT_MS)
        } else {
            // 当前不是广告，检查上一次滑动是否成功
            if (wasAdBeforeSwipe && (currentTime - lastSwipeTime) > SWIPE_DELAY_MS) {
                // 之前是广告，现在不是了 → 滑动成功
                // （只在第一次检测到非广告时计数，避免重复）
                wasAdBeforeSwipe = false
            }
        }

        // 定期更新通知（每10次检查更新一次，避免太频繁）
        if (screenCheckCount % 10 == 0) {
            updateNotification()
        }
    }

    /**
     * 检查滑动结果
     */
    private fun checkSwipeResult() {
        val rootNode = rootInActiveWindow ?: return
        val packageName = rootNode.packageName?.toString() ?: return
        if (!TARGET_PACKAGES.contains(packageName)) return

        val allTexts = mutableListOf<String>()
        collectAllTexts(rootNode, allTexts, 0, 15)

        val stillAd = detectAd(rootNode, allTexts)
        if (stillAd) {
            adSkipFailCount++
            logDebug("滑动可能失败，仍在广告页面")
        } else {
            adSkipSuccessCount++
            logDebug("滑动成功，已离开广告")
        }
        sendStatsBroadcast()
    }

    // ==================== 短剧信息提取 ====================

    private fun extractDramaInfo(texts: List<String>) {
        for (text in texts) {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) continue

            // 检测集数（如 "第3集", "第03集", "Episode 5", "更新至第12集"）
            val episodePatterns = listOf(
                Regex("""第\s*(\d+)\s*集"""),
                Regex("""[Ee]pisode\s*(\d+)"""),
                Regex("""^\s*(\d+)\s*/\s*\d+\s*$""")  // "3/24" 格式
            )
            for (pattern in episodePatterns) {
                val match = pattern.find(trimmed)
                if (match != null) {
                    currentEpisode = "第${match.groupValues[1]}集"
                }
            }

            // 检测短剧标题（通常较长，包含短剧名称关键词）
            // 排除按钮文字和广告文字
            if (trimmed.length >= 4 && trimmed.length <= 30 &&
                !isAdKeyword(trimmed) &&
                !trimmed.matches(Regex("""\d+""")) &&
                !trimmed.contains("免费") &&
                !trimmed.contains("下载") &&
                !trimmed.contains("打开") &&
                !trimmed.matches(Regex("""\d{1,2}:\d{2}""")) &&  // 时间
                !trimmed.matches(Regex("""\d{1,2}:\d{2}:\d{2}"""))  // 时间
            ) {
                // 可能是短剧标题
                if (dramaTitle == "未检测到" || trimmed.length > dramaTitle.length) {
                    // 优先取较长的文字作为标题（标题通常比集数长）
                    dramaTitle = trimmed
                }
            }
        }
    }

    private fun isAdKeyword(text: String): Boolean {
        for (keyword in AD_KEYWORDS) {
            if (text.contains(keyword, ignoreCase = true)) return true
        }
        return false
    }

    // ==================== 广告检测 ====================

    private fun detectAd(node: AccessibilityNodeInfo, allTexts: List<String>): Boolean {
        if (allTexts.isEmpty()) return false

        // 策略1：文字关键词
        for (text in allTexts) {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) continue
            for (keyword in AD_KEYWORDS) {
                if (trimmed.contains(keyword, ignoreCase = true)) {
                    if (isValidAdText(trimmed, keyword)) {
                        lastAdKeyword = "\"$trimmed\"→$keyword"
                        return true
                    }
                }
            }
        }

        // 策略2：倒计时
        for (text in allTexts) {
            val trimmed = text.trim()
            if (matchesAdCountdown(trimmed)) {
                lastAdKeyword = "倒计时:\"$trimmed\""
                return true
            }
        }

        // 策略3：广告SDK控件
        if (hasAdSdkViews(node)) {
            lastAdKeyword = "SDK控件"
            return true
        }

        return false
    }

    private fun collectAllTexts(node: AccessibilityNodeInfo, result: MutableList<String>, depth: Int, maxDepth: Int) {
        if (depth > maxDepth) return
        node.text?.let { val s = it.toString().trim(); if (s.isNotEmpty()) result.add(s) }
        node.contentDescription?.let { val s = it.toString().trim(); if (s.isNotEmpty()) result.add(s) }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllTexts(child, result, depth + 1, maxDepth)
        }
    }

    private fun isValidAdText(text: String, keyword: String): Boolean {
        if (keyword in listOf("广告", "推广", "跳过广告", "关闭广告")) return true
        if (keyword == "跳过") return text.length <= 8 && !text.contains("集") && !text.contains("话")
        if (keyword in listOf("立即下载", "去下载", "打开应用", "查看详情", "了解更多", "安装")) return true
        if (keyword == "AD") return text.length <= 10
        if (keyword == "广告剩余") return true
        if (keyword == "领取") return text.length <= 6 || text.contains("奖励") || text.contains("福利")
        return false
    }

    private fun matchesAdCountdown(text: String): Boolean {
        val patterns = listOf(
            Regex("""\d{1,2}s"""), Regex("""\d{1,2}s后"""),
            Regex("""\d{1,2}秒后"""), Regex("""^\d{1,2}$""")
        )
        for (p in patterns) {
            if (p.containsMatchIn(text) && text.length <= 10) {
                if (text.length == 2 && text.all { it.isDigit() }) continue
                return true
            }
        }
        return false
    }

    private fun hasAdSdkViews(node: AccessibilityNodeInfo) = checkNodeForAdSdk(node, 0, 10)

    private fun checkNodeForAdSdk(node: AccessibilityNodeInfo, depth: Int, maxDepth: Int): Boolean {
        if (depth > maxDepth) return false
        node.viewIdResourceName?.let { id ->
            val lower = id.lowercase()
            if (lower.matches(Regex(".*\\bad\\b.*")) && !lower.contains("download") && !lower.contains("update")) return true
            if (lower.contains("splash_ad") || lower.contains("banner_ad") || lower.contains("ad_container") || lower.contains("ad_layout") || lower.contains("skip_ad")) return true
        }
        node.className?.let { cls ->
            if (cls.toString().lowercase().contains("webview")) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.top < resources.displayMetrics.heightPixels * 0.5f && bounds.height() > 100) return true
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (checkNodeForAdSdk(child, depth + 1, maxDepth)) return true
        }
        return false
    }

    // ==================== 滑动 ====================

    private fun performSwipeUp() {
        val metrics = resources.displayMetrics
        val path = Path().apply {
            moveTo(metrics.widthPixels / 2f, metrics.heightPixels * 0.7f)
            lineTo(metrics.widthPixels / 2f, metrics.heightPixels * 0.3f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300L))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gd: GestureDescription?) { Log.d(TAG, "滑动完成") }
            override fun onCancelled(gd: GestureDescription?) { Log.w(TAG, "滑动取消") }
        }, null)
    }

    // ==================== 广播 ====================

    private fun sendStatsBroadcast() {
        sendBroadcast(Intent("com.example.hongguo_adskipper.UPDATE"))
    }

    private fun formatTime(ms: Long): String {
        val m = (ms / 60000) % 60
        val s = (ms / 1000) % 60
        return String.format("%02d:%02d", m, s)
    }

    // ==================== 开屏广告跳过 ====================

    private var splashPkg: String? = null
    private var splashWindowStart = 0L
    private var splashClickCount = 0
    private var lastSplashClick = 0L
    private var lastSplashScan = 0L

    private fun handleSplashEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (SPLASH_SKIP_PKGS.split(",").any { pkg == it || pkg.startsWith("$it.") }) return
        val now = SystemClock.elapsedRealtime()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != splashPkg) {
            splashPkg = pkg
            splashWindowStart = now
            splashClickCount = 0
        }
        if (pkg != splashPkg) return
        if (now - splashWindowStart > SPLASH_WINDOW_MS) return
        if (splashClickCount >= SPLASH_MAX_CLICKS) return
        if (now - lastSplashClick < SPLASH_CLICK_CD_MS) return
        if (now - lastSplashScan < SPLASH_SCAN_STEP_MS) return
        lastSplashScan = now

        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return
        val node = findSplashSkipNode(root, 0, 14) ?: return
        if (clickNode(node)) {
            splashClickCount++
            lastSplashClick = SystemClock.elapsedRealtime()
            Stats.splashClickCount++
            val label = node.text?.toString()?.take(8)
                ?: node.contentDescription?.toString()?.take(8)
                ?: node.viewIdResourceName?.substringAfterLast('/')
                ?: "?"
            Stats.splashLastTarget = "${pkg.substringAfterLast('.')}:$label"
            Stats.log("开屏跳过 ${Stats.splashLastTarget}")
        }
    }

    private fun findSplashSkipNode(
        node: AccessibilityNodeInfo, depth: Int, maxDepth: Int
    ): AccessibilityNodeInfo? {
        if (depth > maxDepth) return null
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val id = node.viewIdResourceName?.lowercase().orEmpty()
        if (isSkipLabel(text) || isSkipLabel(desc) || isSkipViewId(id)) {
            clickableTarget(node)?.let { return it }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findSplashSkipNode(child, depth + 1, maxDepth)?.let { return it }
        }
        return null
    }

    private fun isSkipLabel(t: String): Boolean {
        if (t.isEmpty() || t.length > 10) return false
        val s = t.lowercase()
        if (s == "跳过" || s == "跳過" || s == "关闭" || s == "關閉" ||
            s == "skip" || s == "close" || s == "跳过广告" || s == "关闭广告"
        ) return true
        if (s.startsWith("跳") && t.length <= 4) return true
        if (s.startsWith("跳过") && t.length <= 8) return true
        if (s.startsWith("关闭") && t.length <= 8) return true
        if (s.contains("跳过广告")) return true
        return false
    }

    private fun isSkipViewId(id: String): Boolean {
        if (id.isEmpty()) return false
        if (id.contains("download") || id.contains("update")) return false
        return id.contains("skip_ad") || id.contains("ad_skip") || id.contains("count_down") ||
                id.contains("splash_ad") || id.contains("skip_view") || id.contains("close_ad")
    }

    private fun clickableTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var guard = 0
        while (cur != null && guard < 5) {
            if (cur.isClickable && cur.isVisibleToUser) return cur
            cur = cur.parent
            guard++
        }
        return null
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.width() <= 0 || r.height() <= 0) return false
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60L))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    override fun onInterrupt() { logDebug("服务被中断") }

    override fun onDestroy() {
        super.onDestroy()
        isServiceEnabled = false
        handler.removeCallbacks(periodicCheck)
        Log.d(TAG, "服务已销毁")
    }
}

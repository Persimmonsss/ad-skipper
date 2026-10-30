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
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 红果短剧广告自动跳过 + 监控数据采集 v1.2.0
 */
class AdSkipAccessibilityService : AccessibilityService() {

    // 版本号只从 build.gradle.kts 取，避免第二个手写常量跟不上发版
    private val appVersion: String by lazy {
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull().orEmpty()
    }

    private val notificationTitle: String
        get() = if (appVersion.isEmpty()) "跳跳狐" else "跳跳狐 v$appVersion"

    companion object {
        private const val TAG = "AdSkipService"
        private const val CHANNEL_ID = "ad_skip_channel"
        private const val NOTIFICATION_ID = 1

        private val HONGGUO_PACKAGES = listOf(
            "com.phoenix.read",
            "com.phoenix.read.tv",
            "cn.xiaochuankeji.zhibo"
        )
        private const val DOUYIN_PKG = "com.ss.android.ugc.aweme"
        private val TARGET_PACKAGES = HONGGUO_PACKAGES + DOUYIN_PKG

        // 红包浮层的「立即领取」在红果每集都有，不能算广告线索
        private val AD_KEYWORDS = listOf(
            "广告", "推广", "广告剩余",
            "跳过广告", "跳过", "关闭广告",
            "立即下载", "了解更多", "查看详情",
            "打开应用", "去下载"
        )
        // 英文 AD 必须独立成词：download 里的 "ad" 不是广告
        private val RE_AD_WORD = Regex("""(?i)(^|[^a-z])ad([^a-z]|$)""")
        // 抖音信息流只在明确的「广告」角标上出手，CTA 单独出现不算
        private val DOUYIN_AD_BADGE = Regex("""^(广告|廣告|赞助|贊助|推广|推廣)(\s*[·|｜].*)?$""")

        private const val CHECK_INTERVAL_MS = 2000L

        // 轮询里反复用到，预编译避免每次检测都新建正则
        private val RE_BARE_DIGIT = Regex("""^\d{1,2}$""")
        private val RE_COUNTDOWN = listOf(
            Regex("""\d{1,2}s"""), Regex("""\d{1,2}s后"""),
            Regex("""\d{1,2}秒后"""), RE_BARE_DIGIT
        )
        private val RE_EPISODE = listOf(
            Regex("""第\s*(\d+)\s*集"""),
            Regex("""[Ee]pisode\s*(\d+)"""),
            Regex("""^\s*(\d+)\s*/\s*\d+\s*$""")
        )
        private val RE_INT = Regex("""\d+""")
        private val RE_CLOCK = Regex("""\d{1,2}:\d{2}""")
        private val RE_CLOCK3 = Regex("""\d{1,2}:\d{2}:\d{2}""")
        private val RE_AD_ID = Regex(""".*\bad\b.*""")
        private const val SWIPE_DELAY_MS = 3000L
        private const val AD_WAIT_MS = 1500L
        private const val MAX_CONSECUTIVE_SWIPES = 3
        private const val RESET_TIMEOUT_MS = 10000L

        // 开屏广告：应用切到前台后的检测窗口
        private const val SPLASH_WINDOW_MS = 10000L
        // 扫到广告特征时续期：不少 App 先播自家启动页，第三方广告 8 秒后才加载
        private const val SPLASH_RENEW_MS = 8000L
        private const val SPLASH_SCAN_STEP_MS = 300L
        private const val SPLASH_CLICK_CD_MS = 1000L
        // 节点点击与盲点各记各的预算：前两次空扫不能把额度花光，真广告常常 8 秒后才加载
        private const val SPLASH_NODE_MAX = 2
        private val SKIP_LABEL = Regex("""(?i)^(跳过|跳過|关闭|關閉|skip|close)(广告|廣告)?(\s*\d+\s*[s秒]?)?$""")
        private val SKIP_CONTAINS = listOf("跳过", "跳過", "关闭", "關閉", "skip", "close")
        // 「关闭」类文案太常见：B站视频页的「关闭弹幕」就被当成开屏按钮点过
        private val SKIP_CONTAINS_EXCLUDE = listOf(
            "弹窗", "提示", "引导", "通知", "弹幕", "倍速", "投屏", "清晰度",
            "全屏", "连播", "收藏", "分享", "下载", "评论", "客服", "字幕", "悬浮"
        )
        // 只有出现在右上/右下角的纯数字才算倒计时按钮（美团类开屏常见）
        private val SKIP_COUNTDOWN = Regex("""^(跳过\s*)?\d{1,2}\s*[sS秒]?$""")
        private val SPLASH_AD_WORDS = listOf("广告", "廣告", "赞助", "投诉", "摇一摇", "摇动手机", "跳转第三方")
        private val SPLASH_SKIP_PKGS = listOf(
            "com.android.systemui", "com.android.launcher", "com.android.settings",
            "com.phoenix.read", "com.miui.home", "com.miui.personalassistant"
        )
        // isValidAdText 每次命中都要查，别在检测热路径里现建集合
        private val AD_STRONG_WORDS = setOf("广告", "推广", "跳过广告", "关闭广告", "广告剩余")
        private val AD_CTA_WORDS = setOf("立即下载", "去下载", "打开应用", "查看详情", "了解更多")

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

    private val biliBubble by lazy { SkipBubble(this) }
    private val biliUi by lazy { BiliUiSkip(this, biliBubble) }

    // 前台包名：红果不在前台时不做轮询（省电）
    private var foregroundPkg = ""
    private var foregroundAt = 0L

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

    /** B站前台时才跑；离开 B站即停，避免常驻 1s 唤醒。 */
    private val biliTicker = object : Runnable {
        override fun run() {
            if (!screenOn()) return
            if (foregroundPkg != BiliUiSkip.BILI_PKG) {
                // 断链前先核对一次活动窗口：前台包名可能被别的窗口事件顶掉，而 B站详情页
                // 播放期间几乎没有无障碍事件，一断链就要等 periodicCheck 的 30 秒兜底才复活
                // （实测倒计时气泡出现那一瞬被本 App 的窗口事件顶掉，跳过晚了 31 秒，广告都播半段了）
                if (activeWindowPackage() != BiliUiSkip.BILI_PKG) return
                foregroundPkg = BiliUiSkip.BILI_PKG
                foregroundAt = SystemClock.elapsedRealtime()
            }
            try {
                biliUi.tick()
            } catch (e: Exception) {
                Log.w(TAG, "B站定时检查异常: ${e.message}")
            }
            handler.postDelayed(this, 1000L)
        }
    }

    private fun ensureBiliTicker() {
        if (!handler.hasCallbacks(biliTicker)) handler.postDelayed(biliTicker, 1000L)
    }

    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceEnabled = true
        foregroundPkg = activeWindowPackage()
        foregroundAt = SystemClock.elapsedRealtime()
        createNotificationChannel()
        showNotification()
        handler.removeCallbacks(periodicCheck)
        handler.removeCallbacks(biliTicker)
        handler.postDelayed(periodicCheck, CHECK_INTERVAL_MS)
        if (foregroundPkg == BiliUiSkip.BILI_PKG) ensureBiliTicker()
        logDebug("服务已启动 $notificationTitle")
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
                CHANNEL_ID, "跳跳狐服务", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "跳跳狐运行状态"
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
                .setContentTitle(notificationTitle)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText("$text\n短剧: $dramaTitle $currentEpisode\n$debugLog"))
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(notificationTitle)
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
        val pkg = event.packageName?.toString()
        val appSwitched = pkg != null && pkg != foregroundPkg
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != null) {
            // 自己的倒计时/撤回气泡也是本包的窗口事件，不能当成「切到了本 App」：
            // 气泡正好在广告前 3 秒出现，一顶掉前台包名就会打断 B站扫描
            foregroundPkg = if (pkg == packageName) activeWindowPackage().ifEmpty { pkg } else pkg
            foregroundAt = SystemClock.elapsedRealtime()
        }
        if (Settings.splashEnabled) {
            try {
                handleSplashEvent(event, appSwitched)
            } catch (e: Exception) {
                Log.w(TAG, "开屏检测异常: ${e.message}")
            }
        }
        if (pkg == BiliUiSkip.BILI_PKG) {
            if (Settings.biliEnabled) {
                try {
                    biliUi.onEvent { rootInActiveWindow }
                    ensureBiliTicker()
                } catch (e: Exception) {
                    Log.w(TAG, "B站跳转异常: ${e.message}")
                }
            }
            return
        }
        if (!Settings.hongguoEnabled) return
        if (!TARGET_PACKAGES.contains(pkg)) return
        // 刚切走 App 的残留事件不能算数，否则别的 App 启动瞬间会误滑
        if (pkg != foregroundPkg) return
        val currentTime = System.currentTimeMillis()
        if (!canSwipeNow(currentTime)) return
        val rootNode = activeWindowRoot() ?: return
        // 事件可能来自刚切走的 App，活动窗口必须仍是事件来源
        if (rootNode.packageName?.toString() != pkg) return
        processScreen(rootNode, currentTime)
    }

    /** 滑动节流 + 连续滑动预算：事件入口与轮询入口共用，避免两处口径漂移。 */
    private fun canSwipeNow(now: Long): Boolean {
        if (now - lastSwipeTime < SWIPE_DELAY_MS) return false
        if (consecutiveSwipes >= MAX_CONSECUTIVE_SWIPES) {
            if (now - lastAdDetectedTime <= RESET_TIMEOUT_MS) return false
            consecutiveSwipes = 0
        }
        return true
    }

    // ==================== 定时轮询 ====================

    private fun performPeriodicCheck() {
        if (!screenOn()) return
        // 兜底刷新前台包名（事件丢失时靠它恢复）
        if (SystemClock.elapsedRealtime() - foregroundAt > 30_000) {
            foregroundPkg = activeWindowPackage()
            foregroundAt = SystemClock.elapsedRealtime()
        }
        if (foregroundPkg == BiliUiSkip.BILI_PKG) ensureBiliTicker()
        if (!Settings.hongguoEnabled) return
        // 按需唤醒：只有红果在前台才扫描界面
        if (!TARGET_PACKAGES.contains(foregroundPkg)) return
        // 启动切换瞬间 rootInActiveWindow 可能仍是上一个 App，以活动窗口为准
        if (!TARGET_PACKAGES.contains(activeWindowPackage())) return
        val rootNode = activeWindowRoot()
        if (rootNode == null) {
            // 每20次打印一次"等待窗口"日志，避免刷屏
            if (screenCheckCount % 20 == 0) {
                logDebug("等待窗口... (检查${screenCheckCount}次)")
            }
            return
        }
        val packageName = rootNode.packageName?.toString() ?: ""
        if (!TARGET_PACKAGES.contains(packageName)) {
            foregroundPkg = packageName
            return
        }
        // 启动切换瞬间根窗口可能还是上一个 App，必须与前台包名一致才处理
        if (packageName != foregroundPkg) return
        val currentTime = System.currentTimeMillis()
        if (!canSwipeNow(currentTime)) return
        processScreen(rootNode, currentTime)
    }

    /** 息屏后不再读取界面节点，省电。 */
    private fun screenOn(): Boolean =
        try {
            getSystemService(PowerManager::class.java).isInteractive
        } catch (e: Exception) {
            true
        }

    /** 活动窗口的包名比 rootInActiveWindow 更早跟上 App 切换。 */
    private fun activeWindowPackage(): String {
        val fromWindows = try {
            windows.firstOrNull { it.isActive }?.root?.packageName?.toString()
        } catch (e: Exception) {
            null
        }
        return fromWindows ?: rootInActiveWindow?.packageName?.toString().orEmpty()
    }

    /** 读界面文本时优先用活动窗口，避免读到下层残留页面的内容。 */
    private fun activeWindowRoot(): AccessibilityNodeInfo? {
        val root = try {
            windows.firstOrNull { it.isActive }?.root
        } catch (e: Exception) {
            null
        }
        return root ?: rootInActiveWindow
    }

    // ==================== 核心处理：采集 + 检测广告 ====================

    private fun processScreen(rootNode: AccessibilityNodeInfo, currentTime: Long) {
        screenCheckCount++
        hongguoDetectedCount++

        // 1. 采集屏幕所有文字
        val allTexts = mutableListOf<String>()
        collectAllTexts(rootNode, allTexts, 0, 15)
        lastScreenTexts = allTexts

        // 2. 提取短剧信息
        extractDramaInfo(allTexts)

        // 3. 检测广告
        val pkg = rootNode.packageName?.toString().orEmpty()
        val detected = detectAdFor(pkg, rootNode, allTexts)
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
                if (performSwipeUp()) {
                    logDebug("已执行滑动 第${consecutiveSwipes}次")
                    // 延迟后再检查一次，判断是否成功跳过
                    handler.postDelayed({
                        checkSwipeResult()
                    }, 2000)
                } else {
                    logDebug("滑动未执行：红果已不在前台")
                }
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
        // 滑动 + 复核一共延了 3.5 秒，用户可能已经按了电源；息屏时读节点既没意义也费电
        if (!screenOn()) return
        val rootNode = activeWindowRoot() ?: return
        val packageName = rootNode.packageName?.toString() ?: return
        if (!TARGET_PACKAGES.contains(packageName)) return

        val allTexts = mutableListOf<String>()
        collectAllTexts(rootNode, allTexts, 0, 15)

        // 复核必须和检测用同一套口径，否则抖音跳完会被判成「仍在广告页面」
        val stillAd = detectAdFor(packageName, rootNode, allTexts)
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
            for (pattern in RE_EPISODE) {
                val match = pattern.find(trimmed)
                if (match != null) {
                    currentEpisode = "第${match.groupValues[1]}集"
                }
            }

            // 检测短剧标题（通常较长，包含短剧名称关键词）
            // 排除按钮文字和广告文字
            if (trimmed.length >= 4 && trimmed.length <= 30 &&
                !isAdKeyword(trimmed) &&
                !RE_INT.matches(trimmed) &&
                !trimmed.contains("免费") &&
                !trimmed.contains("下载") &&
                !trimmed.contains("打开") &&
                !RE_CLOCK.matches(trimmed) &&
                !RE_CLOCK3.matches(trimmed)
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

    /** 红果用宽启发式；抖音只认明确的「广告」角标，宽关键词表会误判普通视频。 */
    private fun detectAdFor(pkg: String, node: AccessibilityNodeInfo, allTexts: List<String>): Boolean =
        if (pkg == DOUYIN_PKG) detectDouyinAd(allTexts) else detectAd(node, allTexts)

    private fun detectAd(node: AccessibilityNodeInfo, allTexts: List<String>): Boolean {
        if (allTexts.isEmpty()) return false

        // 策略1：文字关键词
        for (text in allTexts) {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.length <= 10 && RE_AD_WORD.containsMatchIn(trimmed)) {
                lastAdKeyword = "\"$trimmed\"→AD"
                return true
            }
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
        // 选集面板等信息流页面会有一堆纯数字节点，不能当倒计时
        val bareDigits = allTexts.count { RE_BARE_DIGIT.matches(it.trim()) }
        for (text in allTexts) {
            val trimmed = text.trim()
            if (!matchesAdCountdown(trimmed)) continue
            if (bareDigits >= 3 && RE_BARE_DIGIT.matches(trimmed)) continue
            lastAdKeyword = "倒计时:\"$trimmed\""
            return true
        }

        // 策略3：广告SDK控件
        if (hasAdSdkViews(node)) {
            lastAdKeyword = "SDK控件"
            return true
        }

        return false
    }

    private fun detectDouyinAd(allTexts: List<String>): Boolean {
        for (text in allTexts) {
            val trimmed = text.trim()
            if (trimmed.length > 8) continue
            if (DOUYIN_AD_BADGE.matches(trimmed)) {
                lastAdKeyword = "角标\"$trimmed\""
                return true
            }
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
        if (keyword in AD_STRONG_WORDS) return true
        // 「跳过」单独出现太宽：短剧标题里的「跳过这集」不是按钮
        if (keyword == "跳过") return text.length <= 8 && !text.contains("集") && !text.contains("话")
        return keyword in AD_CTA_WORDS
    }

    private fun matchesAdCountdown(text: String): Boolean {
        for (p in RE_COUNTDOWN) {
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
            if (RE_AD_ID.matches(lower) && !lower.contains("download") && !lower.contains("update")) return true
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

    /** 手势是全局的，落点取决于执行那一刻的前台 App，所以出手前必须再核对一次。 */
    private fun performSwipeUp(): Boolean {
        if (!screenOn()) return false
        if (!TARGET_PACKAGES.contains(activeWindowPackage())) return false
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
        return true
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
    private var splashUntil = 0L
    private var splashAdStreak = 0
    private var splashNodeClicks = 0
    private var lastSplashClick = 0L
    private var lastSplashScan = 0L
    private var splashTreeLogs = 0
    private var lastSplashDigest = ""
    private var splashMatchInfo = ""
    private var lastSplashClickKey = ""

    /** 开屏窗口出现后独立轮询若干秒：广告窗口常常不产生无障碍事件。 */
    private val splashTicker = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (now > splashUntil || !screenOn()) return
            try {
                scanSplashOnce(now)
            } catch (e: Exception) {
                Log.w(TAG, "开屏扫描异常: ${e.message}")
            }
            handler.postDelayed(this, 250L)
        }
    }

    private fun handleSplashEvent(event: AccessibilityEvent, appSwitched: Boolean) {
        if (!Settings.splashEnabled) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (SPLASH_SKIP_PKGS.any { pkg == it || pkg.startsWith("$it.") }) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        // 只认 App 切换后的首个窗口，避免在同一个 App 内反复扫描误点普通"关闭"
        if (!appSwitched) return
        val now = SystemClock.elapsedRealtime()
        // 同一 App 从启动页切到广告页还会来窗口事件：只续期，不重置预算，
        // 否则盲点额度被刷回来，会在广告上反复点
        if (pkg == splashPkg && now < splashUntil) {
            splashUntil = maxOf(splashUntil, now + SPLASH_WINDOW_MS)
            return
        }
        splashPkg = pkg
        splashWindowStart = now
        splashUntil = now + SPLASH_WINDOW_MS
        splashAdStreak = 0
        splashNodeClicks = 0
        lastSplashClick = 0
        lastSplashScan = 0
        splashTreeLogs = 0
        lastSplashDigest = ""
        lastSplashClickKey = ""
        handler.removeCallbacks(splashTicker)
        handler.post(splashTicker)
        logDebug("开屏窗口 $pkg")
    }

    private fun scanSplashOnce(now: Long) {
        val pkg = splashPkg ?: return
        // 输入法、小窗弹出也会算“应用切换”，此时开屏 App 已不在前台，
        // 继续按坐标盲点会点到当前页面（实测点掉过 B站 的“取消”）
        val active = activeWindowPackage().takeIf { it.isNotBlank() }
        if (active != pkg) {
            if (active != null && splashNodeClicks > 0) {
                logDebug("开屏点完已切到 $active，停止扫描")
                handler.removeCallbacks(splashTicker)
            }
            return
        }
        if (splashNodeClicks >= SPLASH_NODE_MAX) return
        if (now - lastSplashClick < SPLASH_CLICK_CD_MS) return
        if (now - lastSplashScan < SPLASH_SCAN_STEP_MS) return
        lastSplashScan = now

        val roots = splashRoots(pkg)
        if (roots.isEmpty()) return
        val node = roots.firstNotNullOfOrNull { findSplashSkipNode(it, 0, 16) }
        if (node != null) {
            // 同一个键点过一次没反应就不再点：实测连点两下会把用户带进广告落地页
            val key = "${node.viewIdResourceName}|${node.text}|${node.contentDescription}|${node.className}"
            if (key == lastSplashClickKey) return
            if (clickNode(node)) {
                splashNodeClicks++
                lastSplashClickKey = key
                lastSplashClick = SystemClock.elapsedRealtime()
                Stats.splashClickCount++
                val label = node.text?.toString()?.take(8)
                    ?: node.contentDescription?.toString()?.take(8)
                    ?: node.viewIdResourceName?.substringAfterLast('/')
                    ?: "?"
                Stats.splashLastTarget = "${pkg.substringAfterLast('.')}:$label"
                Stats.log("开屏跳过 ${Stats.splashLastTarget}")
                logDebug("开屏命中 $splashMatchInfo → 点击 ${node.viewIdResourceName ?: node.className}")
            }
            return
        }
        val samples = roots.flatMap { sampleList(it, 8) }
        val adSurface = looksLikeSplashAd(roots, samples)
        splashAdStreak = if (adSurface) splashAdStreak + 1 else 0
        // 广告特征连续两拍才认，避免普通页面里一两个“广告”字样就续期
        val confirmedAd = splashAdStreak >= 2
        if (confirmedAd) splashUntil = maxOf(splashUntil, now + SPLASH_RENEW_MS)
        val digest = samples.joinToString("/")
        if (digest != lastSplashDigest && splashTreeLogs < 4) {
            lastSplashDigest = digest
            splashTreeLogs++
            logDebug("开屏树：${digest.take(120)}")
            logDebug("开屏窗口数 ${windows.size}：${windowBrief()}")
        }
        // 不再按坐标盲点右上角：实测盲点一次没跳成，反而把用户送进过京东/美团，
        // 还可能在微信首页点到「+」。纯图片广告（树里连一个节点都没有）只能放弃。
    }

    private fun looksLikeSplashAd(roots: List<AccessibilityNodeInfo>, samples: List<String>): Boolean {
        if (samples.any { s -> SPLASH_AD_WORDS.any { s.contains(it) } }) return true
        return roots.any { hasAdSdkViews(it) }
    }

    /** 广告可能挂在独立窗口上，rootInActiveWindow 里看不到。 */
    private fun splashRoots(pkg: String): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        try {
            for (w in windows) {
                val root = w.root ?: continue
                if (root.packageName?.toString() != pkg) continue
                if (!out.contains(root)) out.add(root)
            }
        } catch (e: Exception) {
            Log.w(TAG, "枚举窗口失败: ${e.message}")
        }
        if (out.isEmpty()) {
            rootInActiveWindow?.let { if (it.packageName?.toString() == pkg) out.add(it) }
        }
        return out
    }

    private fun windowBrief(): String {
        val parts = ArrayList<String>()
        try {
            for (w in windows) {
                val root = w.root
                parts.add("${w.type}:${root?.packageName?.toString()?.substringAfterLast('.') ?: "?"}")
            }
        } catch (e: Exception) {
            parts.add("err")
        }
        return parts.joinToString(",")
    }


    private fun findSplashSkipNode(
        node: AccessibilityNodeInfo, depth: Int, maxDepth: Int
    ): AccessibilityNodeInfo? {
        if (depth > maxDepth) return null
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val id = node.viewIdResourceName?.lowercase().orEmpty()
        if (isCornerCountdown(node)) {
            splashMatchInfo = "倒计时数字「${node.text ?: node.contentDescription}」"
            return node // 倒计时数字只认它自己可点，往上爬会点到广告或角标容器
        }
        if ((isSkipLabel(text) || isSkipLabel(desc)) && isButtonSized(node)) {
            splashMatchInfo = "text=$text desc=$desc id=$id"
            return node // 点「跳过」这两个字本身，不去点可点的祖先（实测祖先常是广告容器）
        }
        if (isSkipViewId(id) && isButtonSized(node)) {
            clickableTarget(node)?.let { splashMatchInfo = "id=$id"; return it }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findSplashSkipNode(child, depth + 1, maxDepth)?.let { return it }
        }
        return null
    }

    private fun isSkipLabel(t: String): Boolean {
        val s = t.trim()
        if (s.isEmpty() || s.length > 10) return false
        if (SKIP_LABEL.matches(s)) return true
        // 「5秒后可跳过」「跳过 >」「| 跳过」这类变体整体正则匹配不到，按包含判断补一次
        if (s.length > 8) return false
        if (SKIP_CONTAINS_EXCLUDE.any { s.contains(it) }) return false
        return SKIP_CONTAINS.any { s.contains(it, ignoreCase = true) }
    }

    /** 美团类开屏的跳过键常常只有一个倒计时数字「2」「2s」，靠位置限定在右上/右下角。 */
    private fun isCornerCountdown(node: AccessibilityNodeInfo): Boolean {
        // 必须是按钮本身可点：首页角标（消息未读数）也是右上角的纯数字，但不可点，
        // 之前按祖先可点往上爬，实测一次点进了京东首页的「消息」「更多菜单」
        if (!node.isClickable) return false
        val label = node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return false
        if (!SKIP_COUNTDOWN.matches(label)) return false
        val b = Rect()
        node.getBoundsInScreen(b)
        val dm = resources.displayMetrics
        if (b.height() > dm.heightPixels * 0.12f) return false
        val rightSide = b.left > dm.widthPixels * 0.55f
        val topBand = b.top < dm.heightPixels * 0.22f
        val bottomBand = b.top > dm.heightPixels * 0.7f
        return rightSide && (topBand || bottomBand)
    }

    private fun isSkipViewId(id: String): Boolean {
        if (id.isEmpty()) return false
        if (id.contains("download") || id.contains("update")) return false
        // splash_ad / ad_container 是广告容器本身，点它等于点了广告（实测点进美团），不能当跳过键
        return id.contains("skip_ad") || id.contains("ad_skip") || id.contains("skip_view") ||
                id.contains("close_ad") || id.contains("skip")
    }

    /** 广告容器往往占满整屏，只有按钮尺寸的节点才允许按 id 盲点。 */
    private fun isButtonSized(node: AccessibilityNodeInfo): Boolean {
        val b = Rect()
        node.getBoundsInScreen(b)
        val dm = resources.displayMetrics
        return b.width() < dm.widthPixels * 0.5f && b.height() < dm.heightPixels * 0.25f
    }

    private fun sampleList(root: AccessibilityNodeInfo, limit: Int = 12): List<String> {
        val out = ArrayList<String>()
        fun walk(n: AccessibilityNodeInfo, d: Int) {
            if (d > 14 || out.size >= limit) return
            val t = n.text?.toString()?.takeIf { it.isNotBlank() }
                ?: n.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            if (t != null) out.add(t.take(12))
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, d + 1) }
        }
        walk(root, 0)
        return out
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
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        // 不可点的文字节点直接按它自己的中心点落指，比点祖先容器稳
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
        handler.removeCallbacks(biliTicker)
        Log.d(TAG, "服务已销毁")
    }
}

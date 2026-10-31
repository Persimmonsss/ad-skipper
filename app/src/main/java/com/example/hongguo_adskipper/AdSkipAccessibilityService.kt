package com.example.hongguo_adskipper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
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
        // 抖音信息流只在明确的「广告」角标上出手，CTA 单独出现不算
        private val DOUYIN_AD_BADGE = Regex("""^(广告|廣告|赞助|贊助|推广|推廣)(\s*[·|｜].*)?$""")

        // 轮询间隔随档位动态算（checkIntervalActive/Idle），目标 App 前台高频兜底、其他前台低频省电

        // 轮询里反复用到，预编译避免每次检测都新建正则
        private val RE_BARE_DIGIT = Regex("""^\d{1,2}$""")
        private val RE_EPISODE = listOf(
            Regex("""第\s*(\d+)\s*集"""),
            Regex("""[Ee]pisode\s*(\d+)"""),
            Regex("""^\s*(\d+)\s*/\s*\d+\s*$""")
        )
        // 剧名提取（T14 待办 2：热评/展开/声明/[ 一律不算剧名）
        private val RE_TITLE_PIPE = Regex("""^.{0,20}?第[一二三四五六七八九十\d]+集\s*[|｜]\s*(.+)$""")
        private val RE_TITLE_SEASON = Regex("""^.{2,40}第[一二三四五六七八九十\d]+季$""")
        private val RE_FINISHED = Regex("""已完结|全\s*\d+\s*集""")
        private val RE_INT = Regex("""\d+""")
        private val RE_CLOCK = Regex("""\d{1,2}:\d{2}""")
        private val RE_CLOCK3 = Regex("""\d{1,2}:\d{2}:\d{2}""")
        private val RE_AD_ID = Regex(""".*\bad\b.*""")
        private const val SWIPE_DELAY_MS = 3000L
        private const val AD_WAIT_MS = 1200L
        private const val MAX_CONSECUTIVE_SWIPES = 3
        private const val RESET_TIMEOUT_MS = 10000L
        // 红果广告层底部「上滑继续看短剧」提示条的位置（1080×2340 基准）与匹配阈值
        // 提示条模板匹配：多模板池（assets/ad_hint*.png），底部大范围搜索（matchHint 内实现），
        // 不再用固定坐标/固定尺寸（红果提示条排版与位置随广告渲染浮动）
        private const val HINT_CELL = 4
        private const val HINT_SCORE = 0.85f
        // 模板素材采集机：Mi 10 1080×2340、440dpi、density=2.75。别的机型按密度比缩放模板
        private const val HINT_REF_DENSITY = 2.75f
        // 采样/轮询间隔随 Settings.perfLevel 动态变化（0=省电 100=最快），见下方 hintSampleCool() 等
        private const val HINT_STREAK_NEED = 2
        // 连续 N 拍毫无广告迹象后从高频回到低频
        private const val HINT_COOL_STREAK_RESET = 3
        // 广告迹象确认期 0.8s（命中后的关键路径）；系统截图最小间隔 333ms，700ms 留足余量
        private const val HINT_SAMPLE_HOT_MS = 800L
        private const val SHOT_MIN_GAP_MS = 700L
        // 红果跳过后的撤回气泡停留时长
        private const val UNDO_BUBBLE_MS = 5000L
        // 倒计时锁定广告：复核失败后的快速重试间隔与上限（前 3 次 0.8s，后 2 次 2.4s，覆盖 10s+ 倒计时）
        private const val RETRY_DELAY_MS = 800L
        private const val LOCK_RETRY_MAX = 5

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
            "全屏", "连播", "收藏", "分享", "下载", "评论", "客服", "字幕", "悬浮",
            // 系统控制中心/通知栏的开关描述（如「飞行模式,关闭」）绝不能当跳过键点
            "飞行", "模式", "蓝牙", "Wi-Fi", "wifi", "WLAN", "热点", "静音",
            "震动", "勿扰", "护眼", "深色", "亮度", "旋转", "数据", "流量",
            "同步", "省电", "电池", "自动"
        )
        // 只有出现在右上/右下角的纯数字才算倒计时按钮（美团类开屏常见）
        private val SKIP_COUNTDOWN = Regex("""^(跳过\s*)?\d{1,2}\s*[sS秒]?$""")
        private val SPLASH_AD_WORDS = listOf("广告", "廣告", "赞助", "投诉", "摇一摇", "摇动手机", "跳转第三方")
        private val SPLASH_SKIP_PKGS = listOf(
            "com.android.systemui", "com.android.launcher", "com.android.settings",
            "com.phoenix.read", "com.miui.home", "com.miui.personalassistant",
            // 系统 UI/安全中心：通知栏、控制中心、系统弹窗不是开屏广告，绝不能扫更不能点
            "miui.systemui.plugin", "com.miui.systemui", "com.miui.securitycenter",
            "com.miui.rom", "com.android.providers.media"
        )

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
        private var hintStreak = 0
        private var lastHintSampleAt = 0L
        private var lastShotAt = 0L
        private var darkSampleInFlight = false
        // 广告深链兜底：滑动后 5 秒内前台被广告顶到别的 App，立刻返回红果
        private var deepLinkGuardUntil = 0L
        private var deepLinkBacks = 0

        private val handler = Handler(Looper.getMainLooper())
    }

    private val biliBubble by lazy { SkipBubble(this) }
    // 红果剧间广告「跳过」后的撤回气泡（复用 SkipBubble，悬浮窗权限即可）
    private val undoBubble by lazy { SkipBubble(this) }
    private var undoUuid = 0L
    private var undoUntil = 0L
    // 倒计时锁定型广告的快速重试状态（复核失败时进入，成功或放弃后退出）
    private var retryingAd = false
    private var lockRetries = 0
    // 红果采样两档：有广告迹象时高频，正常播放回低频省电
    private var hintHot = false
    private var hintCoolStreak = 0
    // 上一次采样的屏幕方向：旋转后搜索带/手势坐标整体换向，连续匹配不能跨方向累计
    private var lastLandscape: Boolean? = null
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
            handler.postDelayed(this, if (foregroundPkg in TARGET_PACKAGES) checkIntervalActive() else checkIntervalIdle())
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
        hintStreak = 0
        createNotificationChannel()
        showNotification()
        handler.removeCallbacks(periodicCheck)
        handler.removeCallbacks(biliTicker)
        handler.postDelayed(periodicCheck, if (foregroundPkg in TARGET_PACKAGES) checkIntervalActive() else checkIntervalIdle())
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
            try {
                maybeBlockAdDeepLink(pkg)
            } catch (e: Exception) {
                Log.w(TAG, "深链兜底异常: ${e.message}")
            }
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
        val rootNode = activeWindowRoot() ?: return
        // 事件可能来自刚切走的 App，活动窗口必须仍是事件来源
        if (rootNode.packageName?.toString() != pkg) return
        processScreen(rootNode, currentTime)
    }

    /**
     * 广告深链兜底：全屏广告是可交互创意，滑动可能被广告 SDK 当成「上滑打开」，
     * 把用户带进淘宝/支付宝/应用商店。滑动后 5 秒内一旦前台不是红果/桌面/本 App，
     * 立刻 BACK 回来（最多 2 次）。宁可这一条广告没跳过，也绝不把用户送进落地页。
     */
    private fun maybeBlockAdDeepLink(pkg: String?) {
        if (pkg.isNullOrEmpty() || SystemClock.elapsedRealtime() > deepLinkGuardUntil) return
        if (pkg in TARGET_PACKAGES || pkg == packageName) return
        if (pkg == "com.miui.home" || pkg == "com.android.systemui") {
            deepLinkGuardUntil = 0
            return
        }
        if (deepLinkBacks >= 2) {
            deepLinkGuardUntil = 0
            return
        }
        deepLinkBacks++
        logDebug("拦截广告跳转：$pkg → 返回红果(${deepLinkBacks})")
        handler.postDelayed({
            val top = activeWindowPackage()
            if (top !in TARGET_PACKAGES && top != "com.miui.home") {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }, 350)
    }

    /** 滑动节流 + 连续滑动预算：命中后统一在 onAdDetected 里检查，避免事件驱动下重复滑动。 */
    private fun canSwipeNow(now: Long): Boolean {        if (now - lastSwipeTime < SWIPE_DELAY_MS) return false
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
        processScreen(rootNode, currentTime)
    }

    /** 息屏后不再读取界面节点，省电。 */
    private fun screenOn(): Boolean =
        try {
            getSystemService(PowerManager::class.java).isInteractive
        } catch (e: Exception) {
            true
        }

    /**
     * 当前屏幕尺寸（随旋转更新）。无障碍服务的 resources.displayMetrics 属于非可视上下文，
     * 旋转后可能仍是旧方向；手势坐标按旧尺寸算会直接落到屏外（横屏实测滑动无反应）。
     * 这里以 DisplayManager 的实时尺寸为准，取不到才退回 displayMetrics。
     */
    fun currentScreenBounds(): Rect {
        val dm = resources.displayMetrics
        val point = Point()
        val display = try {
            getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        } catch (e: Exception) {
            null
        }
        val ok = display != null && try {
            display.getRealSize(point)
            point.x > 0 && point.y > 0
        } catch (e: Exception) {
            false
        }
        return if (ok) Rect(0, 0, point.x, point.y)
        else Rect(0, 0, dm.widthPixels, dm.heightPixels)
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

    // ==================== 省电/速度档位 ====================
    // 用户在主界面用进度条在「跳广告速度 ↔ 省电」之间选择（Settings.perfLevel 0~100）。
    // 档位只影响「无广告迹象时的采样频率」与「轮询兜底频率」；
    // 广告一旦确认（提示条 ≥0.85），确认与滑动链路固定 0.8s+1.2s 不随档位变慢。

    /** 正常播放（无广告迹象）时的红果采样间隔：速度端 0.9s，省电端 2.6s。 */
    private fun hintSampleCool(): Long =
        (2600 - Settings.perfLevel * 24).coerceIn(900, 2600).toLong()

    /** 目标 App 前台轮询兜底间隔：速度端 0.9s，省电端 1.8s。 */
    private fun checkIntervalActive(): Long =
        (1800 - Settings.perfLevel * 10).coerceIn(900, 1800).toLong()

    /** 非目标 App 前台轮询间隔（只刷新前台包名，不读界面）：速度端 5s，省电端 10s。 */
    private fun checkIntervalIdle(): Long =
        (10000 - Settings.perfLevel * 50).coerceIn(5000, 10000).toLong()

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
        if (pkg !in HONGGUO_PACKAGES) {
            // 红果不在前台时采样链停摆，残留的「连续匹配」必须清掉：实测切出 27 秒再切回，
            // 旧 streak 会让回来后的第一帧直接凑成第 2 拍（单帧确认，遇过渡画面就可能误滑）
            hintStreak = 0
            hintHot = false
            hintCoolStreak = 0
        } else {
            // 全屏时用户可能旋转屏幕：方向一变搜索带整块换位（竖屏底部 ↔ 横屏右上），
            // 旧方向的匹配不能算数；同时立刻重采样，不等低频档位。
            val bounds = currentScreenBounds()
            val landscapeNow = bounds.width() > bounds.height()
            if (lastLandscape != null && lastLandscape != landscapeNow) {
                hintStreak = 0
                hintHot = false
                hintCoolStreak = 0
                lastHintSampleAt = 0
                logDebug("方向切换 ${if (landscapeNow) "横屏" else "竖屏"}，重新采样")
            }
            lastLandscape = landscapeNow
        }
        // 倒计时重试循环进行中：红果还在同一广告页，轮询不再重复触发（避免耗尽连续滑动预算）
        if (retryingAd) return
        if (pkg in HONGGUO_PACKAGES && currentTime - lastHintSampleAt >= if (hintHot) HINT_SAMPLE_HOT_MS else hintSampleCool()) {
            // 红果剧间广告画在自家窗口里，无障碍树原样留着上一集的 60 多条文案
            //（实测：「第12集/选集/评论」全在，广告自身一个字都不进树），文字判定必然误伤。
            // 唯一稳定的画面标记是广告层底部的「上滑继续看短剧」提示，按模板匹配认它。
            lastHintSampleAt = currentTime
            sampleScreenMatch { score ->
                if (score < 0f) return@sampleScreenMatch
                if (score >= HINT_SCORE) {
                    hintHot = true
                    hintStreak++
                    logDebug("提示条匹配 ${(score * 100).toInt()}% 第${hintStreak}拍")
                    if (hintStreak >= HINT_STREAK_NEED) {
                        hintStreak = 0
                        onAdDetected("上滑继续看短剧", System.currentTimeMillis())
                    }
                } else if (score >= 0.6f) {
                    // 有广告迹象但未达阈值：进入高频，等它变清晰
                    hintHot = true
                    hintCoolStreak = 0
                    hintStreak = 0
                    logDebug("提示条近似 ${(score * 100).toInt()}%")
                } else {
                    hintStreak = 0
                    hintCoolStreak++
                    if (hintCoolStreak >= HINT_COOL_STREAK_RESET) {
                        hintCoolStreak = 0
                        hintHot = false
                    }
                }
            }
        }
        val detected = detectAdFor(pkg, allTexts)
        if (detected) {
            onAdDetected(lastAdKeyword, currentTime)
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

    /** 命中广告后的统一动作：计数、记日志、延迟上滑、再复核。 */
    private fun onAdDetected(keyword: String, currentTime: Long) {
        // 滑动节流 + 连续预算统一在这里拦截（事件驱动下入口不再前置，防止同一广告反复滑动）
        if (!canSwipeNow(currentTime)) {
            logDebug("广告命中但滑动被节流拦截")
            return
        }
        lastAdKeyword = keyword
        adDetectedCount++
        hintStreak = 0
        lastAdDetectedTime = currentTime
        lastAdTime = formatTime(currentTime)
        consecutiveSwipes++
        wasAdBeforeSwipe = true

        logDebug("广告命中:[$keyword] 剧:$dramaTitle 集:$currentEpisode")

        lastSwipeTime = currentTime
        adSkipCount++
        sendStatsBroadcast()
        handler.postDelayed({
            if (performSwipeUp()) {
                logDebug("已执行滑动 第${consecutiveSwipes}次")
                showUndoBubble(consecutiveSwipes.toLong())
                // 延迟后再检查一次，判断是否成功跳过
                handler.postDelayed({
                    checkSwipeResult()
                }, 2000)
            } else {
                logDebug("滑动未执行：红果已不在前台")
            }
        }, AD_WAIT_MS)
    }

    /** 跳过成功后显示撤回气泡（可关）：跳错了点「撤回」反向下滑回到上一状态。 */
    private fun showUndoBubble(swipeNo: Long) {
        if (!Settings.bubbleEnabled) return
        undoUuid = swipeNo
        undoUntil = SystemClock.elapsedRealtime() + UNDO_BUBBLE_MS
        undoBubble.show("已跳过广告", "撤回") { undoLastSwipe(swipeNo) }
        handler.postDelayed({
            if (undoUuid == swipeNo && SystemClock.elapsedRealtime() >= undoUntil) {
                undoUuid = 0
                undoUntil = 0
                undoBubble.hide()
            }
        }, UNDO_BUBBLE_MS + 60)
    }

    /** 撤回：反向下滑（红果「上滑跳广告」，反向即回上一状态）。出手前核对前台仍是红果。 */
    private fun undoLastSwipe(swipeNo: Long) {
        if (undoUuid != swipeNo || SystemClock.elapsedRealtime() > undoUntil) {
            undoBubble.hide()
            return
        }
        undoUuid = 0
        undoUntil = 0
        undoBubble.hide()
        if (!screenOn()) return
        if (!TARGET_PACKAGES.contains(activeWindowPackage())) return
        val bounds = currentScreenBounds()
        val path = Path().apply {
            moveTo(bounds.width() / 2f, bounds.height() * 0.3f)
            lineTo(bounds.width() / 2f, bounds.height() * 0.7f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300L))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gd: GestureDescription?) { Log.d(TAG, "撤回滑动完成") }
            override fun onCancelled(gd: GestureDescription?) { Log.w(TAG, "撤回滑动取消") }
        }, null)
        logDebug("已执行撤回滑动")
    }

    // ==================== 红果剧间广告：提示条模板匹配 ====================

    /** 提示条模板池：红果不同广告渲染的提示条排版不同（「上滑继续看短剧」/「上滑继续观看短剧」，
     *  字号与位置都浮动），多模板 + 底部大范围搜索兜底。 */
    private class HintTemplate(val cells: FloatArray, val cols: Int, val rows: Int, val w: Int, val h: Int)

    private val hintTemplates by lazy { loadHintTemplates() }

    private fun loadHintTemplates(): List<HintTemplate> {
        val factors = templateScales()
        val out = ArrayList<HintTemplate>()
        for (name in listOf("ad_hint.png", "ad_hint2.png", "ad_hint_land.png")) {
            try {
                val src = assets.open(name).use { BitmapFactory.decodeStream(it) } ?: continue
                for (f in factors) {
                    val w = maxOf(8, Math.round(src.width * f))
                    val h = maxOf(8, Math.round(src.height * f))
                    val bmp = if (f == 1f) src else Bitmap.createScaledBitmap(src, w, h, true)
                    val cols = maxOf(1, w / HINT_CELL)
                    val rows = maxOf(1, h / HINT_CELL)
                    val cells = cellsOf(w, h, HINT_CELL) { x, y ->
                        luminance(bmp.getPixel(x, y)) > 150
                    }
                    out.add(HintTemplate(cells, cols, rows, w, h))
                }
                Log.i(TAG, "提示条模板加载: $name ${src.width}x${src.height} ×${factors.size}档")
            } catch (e: Exception) {
                Log.w(TAG, "提示条模板 $name 读取失败: ${e.message}")
            }
        }
        return out
    }

    /**
     * 模板按屏幕密度相对素材机（Mi 10 / 440dpi / 2.75x）的比值缩放，兼容不同分辨率机型。
     * 同密度机型只用原尺寸；密度差得多的机型加 ±6% 兜取整误差。
     */
    private fun templateScales(): List<Float> {
        val ratio = resources.displayMetrics.density / HINT_REF_DENSITY
        return if (kotlin.math.abs(ratio - 1f) < 0.05f) listOf(1f)
        else listOf(ratio * 0.94f, ratio, ratio * 1.06f)
    }

    private fun luminance(c: Int): Int =
        (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000

    /** 把区域按 cell×cell 方格二值降采样，抵消抗锯齿和 1-2 像素抖动。 */
    private fun cellsOf(
        w: Int, h: Int, cell: Int, at: (Int, Int) -> Boolean
    ): FloatArray {
        val cols = maxOf(1, w / cell)
        val rows = maxOf(1, h / cell)
        val out = FloatArray(rows * cols)
        for (r in 0 until rows) {
            val y0 = r * h / rows
            val y1 = maxOf(y0 + 1, (r + 1) * h / rows)
            for (c in 0 until cols) {
                val x0 = c * w / cols
                val x1 = maxOf(x0 + 1, (c + 1) * w / cols)
                var hit = 0
                for (y in y0 until y1) for (x in x0 until x1) if (at(x, y)) hit++
                out[r * cols + c] = hit.toFloat() / ((y1 - y0) * (x1 - x0))
            }
        }
        return out
    }

    /** 整块区域一次 getPixels 再按 cell 降采样：逐像素 getPixel 走 JNI，横屏一拍要 800ms。 */
    private fun regionCells(bmp: Bitmap, x0: Int, y0: Int, w: Int, h: Int, cell: Int): FloatArray {
        val cols = maxOf(1, w / cell)
        val rows = maxOf(1, h / cell)
        val wpx = cols * cell
        val out = FloatArray(rows * cols)
        val rowPx = IntArray(wpx * cell)
        val denom = (cell * cell).toFloat()
        for (r in 0 until rows) {
            bmp.getPixels(rowPx, 0, wpx, x0, y0 + r * cell, wpx, cell)
            for (c in 0 until cols) {
                var hit = 0
                for (y in 0 until cell) {
                    val base = y * wpx + c * cell
                    for (x in 0 until cell) if (luminance(rowPx[base + x]) > 150) hit++
                }
                out[r * cols + c] = hit / denom
            }
        }
        return out
    }

    /**
     * 多模板 NCC（归一化互相关）搜索，返回 0-1 相似度。
     * 竖屏提示条锚定在「屏幕水平中心、距屏底固定高度」：实测 x 346-756（中心 551、半宽 205）、
     * 距底 130-340px。
     * 横屏（红果 ShortSeriesLandActivity 2340×1080）提示条改画在**右上角**，文案也不同
     * （「上滑继续观看剧集」）；实测 pill 位于 x 1564-1992、y 64-160，搜索带取右上区域。
     * 尺寸一律按密度比缩放（提示条是 dp 尺寸，像素大小只跟 density 走）。
     */
    private fun matchHint(bmp: Bitmap): Float {
        val templates = hintTemplates
        if (templates.isEmpty()) return 0f
        val sw = bmp.width
        val sh = bmp.height
        val u = resources.displayMetrics.density / HINT_REF_DENSITY
        return if (sw > sh) {
            // 右上角主搜索带（实测 pill x 0.67-0.85w、y 0.06-0.15h，留足余量）
            val top = matchHintInBand(
                bmp, (sw * 0.58f).toInt(), 0, sw, (sh * 0.20f).toInt(), templates
            )
            // 主带已过阈值就不扫底部兜底；只有没认出来才花这份算力
            if (top >= HINT_SCORE) top else maxOf(
                top,
                matchHintInBand(
                    bmp,
                    (sw / 2f - 320f * u).toInt().coerceAtLeast(0),
                    (sh - 420f * u).toInt().coerceAtLeast(0),
                    (sw / 2f + 320f * u).toInt().coerceAtMost(sw),
                    (sh - 70f * u).toInt().coerceAtMost(sh),
                    templates
                )
            )
        } else {
            matchHintInBand(
                bmp,
                (sw / 2f - 210f * u).toInt().coerceAtLeast(0),
                (sh - 340f * u).toInt().coerceAtLeast(0),
                (sw / 2f + 210f * u).toInt().coerceAtMost(sw),
                (sh - 130f * u).toInt().coerceAtMost(sh),
                templates
            )
        }
    }

    private fun matchHintInBand(
        bmp: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int, templates: List<HintTemplate>
    ): Float {
        val sw = bmp.width
        val sh = bmp.height
        if (x1 <= x0 || y1 <= y0) return 0f
        val cell = HINT_CELL
        // 区域宽高圆整到 cell 整数倍，保证与模板网格同为 cell 像素
        val rw = (x1 - x0) / cell * cell
        val rh = (y1 - y0) / cell * cell
        if (rw <= 0 || rh <= 0) return 0f
        val gcols = rw / cell
        val grows = rh / cell
        val region = regionCells(bmp, x0, y0, rw, rh, cell)
        var best = -1f
        for (t in templates) {
            if (grows - t.rows < 0 || gcols - t.cols < 0) continue
            // 粗搜：cell 网格滑动（复用数组避免反复分配）
            var tBest = -1f
            var bestCx = 0
            var bestCy = 0
            val a = FloatArray(t.cells.size)
            for (gy in 0..(grows - t.rows)) {
                val rowBase = gy * gcols
                for (gx in 0..(gcols - t.cols)) {
                    var i = 0
                    for (r in 0 until t.rows) {
                        val base = rowBase + r * gcols + gx
                        for (c in 0 until t.cols) a[i++] = region[base + c]
                    }
                    val s = ncc(a, t.cells)
                    if (s > tBest) {
                        tBest = s
                        bestCx = gx
                        bestCy = gy
                    }
                }
            }
            // 精搜：像素 ±cell（NCC 对 1-2px 位移敏感，粗搜网格可能错过精确位置）。
            // 性能关键：扩展区域（模板+2*cell 边距）只 getPixel 一次，81 个候选从内存数组滑窗取，
            // 避免每个候选重读模板区域（否则每拍 400 万次 getPixel，真机 2-3s）。
            val px0 = x0 + bestCx * cell
            val py0 = y0 + bestCy * cell
            val ex0 = maxOf(0, px0 - cell)
            val ey0 = maxOf(0, py0 - cell)
            val ex1 = minOf(sw, px0 + cell + t.w)
            val ey1 = minOf(sh, py0 + cell + t.h)
            val ew = ex1 - ex0
            val eh = ey1 - ey0
            if (ew <= 0 || eh <= 0) continue
            val px = IntArray(ew * eh)
            bmp.getPixels(px, 0, ew, ex0, ey0, ew, eh)
            val bits = BooleanArray(px.size)
            for (i in px.indices) bits[i] = luminance(px[i]) > 150
            val cells = FloatArray(t.rows * t.cols)
            for (dy in -cell..cell) {
                for (dx in -cell..cell) {
                    val cx = px0 + dx
                    val cy = py0 + dy
                    if (cx < ex0 || cy < ey0 || cx + t.w > ex1 || cy + t.h > ey1) continue
                    for (r in 0 until t.rows) {
                        val yy0 = r * t.h / t.rows
                        val yy1 = maxOf(yy0 + 1, (r + 1) * t.h / t.rows)
                        for (c in 0 until t.cols) {
                            val xx0 = c * t.w / t.cols
                            val xx1 = maxOf(xx0 + 1, (c + 1) * t.w / t.cols)
                            var hit = 0
                            for (y in yy0 until yy1) for (x in xx0 until xx1) {
                                if (bits[(y + cy - ey0) * ew + (x + cx - ex0)]) hit++
                            }
                            cells[r * t.cols + c] =
                                hit.toFloat() / ((yy1 - yy0) * (xx1 - xx0))
                        }
                    }
                    val s = ncc(cells, t.cells)
                    if (s > best) best = s
                }
            }
        }
        return maxOf(0f, best)
    }

    private fun ncc(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        if (n == 0) return 0f
        var ma = 0f
        var mb = 0f
        for (i in 0 until n) { ma += a[i]; mb += b[i] }
        ma /= n; mb /= n
        var num = 0f
        var da = 0f
        var db = 0f
        for (i in 0 until n) {
            val x = a[i] - ma
            val y = b[i] - mb
            num += x * y
            da += x * x
            db += y * y
        }
        val den = kotlin.math.sqrt(da * db)
        return if (den < 1e-6f) 0f else num / den
    }

    /** 截图算提示条相似度；失败返回 -1 表示「没取到画面」，调用方不能把它当成功。 */
    private fun sampleScreenMatch(retried: Boolean = false, busyRetries: Int = 0, onResult: (Float) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (darkSampleInFlight) {
            // 复核和轮询可能撞在同一帧：等这次采完再来，别把结果丢掉
            if (busyRetries < 5) handler.postDelayed({ sampleScreenMatch(retried, busyRetries + 1, onResult) }, 200L)
            else onResult(-1f)
            return
        }
        // 系统对 takeScreenshot 有最小间隔（连拍会报 error 3），只补足剩余时间
        val now = SystemClock.elapsedRealtime()
        val wait = SHOT_MIN_GAP_MS - (now - lastShotAt)
        if (wait > 0) {
            if (!retried) handler.postDelayed({ sampleScreenMatch(true, busyRetries, onResult) }, wait + 30)
            else onResult(-1f)
            return
        }
        darkSampleInFlight = true
        lastShotAt = now
        val shotAt = now
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    darkSampleInFlight = false
                    var score = 0f
                    try {
                        val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        val sw = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        if (sw != null) {
                            val t0 = SystemClock.elapsedRealtime()
                            score = matchHint(sw)
                            val cost = SystemClock.elapsedRealtime() - t0
                            // 只在有广告迹象时记耗时：正常播放每拍都打日志既费电又淹没关键行
                            if (score >= 0.6f) {
                                logDebug("采样 截图${SystemClock.elapsedRealtime() - shotAt}ms 匹配${cost}ms 分数 ${(score * 100).toInt()}%")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "截图分析失败: ${e.message}")
                    } finally {
                        result.hardwareBuffer.close()
                    }
                    onResult(score)
                }

                override fun onFailure(errorCode: Int) {
                    darkSampleInFlight = false
                    Log.w(TAG, "截图失败: $errorCode")
                    onResult(-1f)
                }
            })
        } catch (e: Exception) {
            darkSampleInFlight = false
            Log.w(TAG, "截图异常: ${e.message}")
            onResult(-1f)
        }
    }

    /**
     * 检查滑动结果：广告页是无障碍树里看不出来的覆盖层，复核同样按画面判。
     */
    private fun checkSwipeResult(attempt: Int = 0) {
        // 滑动 + 复核一共延了 3.2 秒，用户可能已经按了电源；息屏时截图既没意义也费电
        if (!screenOn()) { logDebug("复核跳过：已息屏"); return }
        val rootNode = activeWindowRoot()
        if (rootNode == null) { logDebug("复核跳过：取不到活动窗口"); return }
        val packageName = rootNode.packageName?.toString()
        if (packageName == null) { logDebug("复核跳过：窗口无包名"); return }
        if (!TARGET_PACKAGES.contains(packageName)) {
            logDebug("复核跳过：前台是 $packageName")
            return
        }

        sampleScreenMatch { score ->
            // 没取到画面（撞帧/系统限流）：重试，不记成功也不记失败
            if (score < 0f) {
                if (attempt < 2) {
                    logDebug("复核取样失败，500ms 后重试")
                    handler.postDelayed({ checkSwipeResult(attempt + 1) }, 500)
                } else {
                    logDebug("复核取样连续失败，交给轮询")
                }
                return@sampleScreenMatch
            }
            val stillAd = score >= HINT_SCORE
            if (stillAd) {
                adSkipFailCount++
                // 倒计时锁定型广告：红果开头锁定上滑（x 秒后可划），首滑无效是正常的。
                // 复核失败进入快速重试循环（绕过 3 秒节流），倒计时一结束立即划走。
                if (!retryingAd) {
                    retryingAd = true
                    lockRetries = 0
                    logDebug("滑动可能失败，进入倒计时重试（提示条 ${(score * 100).toInt()}%）")
                }
                if (lockRetries < LOCK_RETRY_MAX) {
                    lockRetries++
                    val delay = if (lockRetries <= 3) RETRY_DELAY_MS else RETRY_DELAY_MS * 3
                    logDebug("锁定重试 第${lockRetries}次（${delay}ms 后）")
                    handler.postDelayed({
                        if (screenOn() && TARGET_PACKAGES.contains(activeWindowPackage())) {
                            if (performSwipeUp()) {
                                showUndoBubble(lockRetries.toLong())
                                handler.postDelayed({ checkSwipeResult() }, 2000)
                            } else {
                                retryingAd = false
                            }
                        } else {
                            retryingAd = false
                        }
                    }, delay)
                } else {
                    retryingAd = false
                    lockRetries = 0
                    logDebug("倒计时重试 ${LOCK_RETRY_MAX} 次仍失败，放弃，交给轮询")
                }
            } else {
                adSkipSuccessCount++
                retryingAd = false
                lockRetries = 0
                logDebug("滑动成功，已离开广告（残留 ${(score * 100).toInt()}%）")
            }
            sendStatsBroadcast()
        }
    }

    // ==================== 短剧信息提取 ====================

    private fun extractDramaInfo(texts: List<String>) {
        var bestTitle: String? = null
        var bestRank = -1
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

            // 检测短剧标题（rank 制：结构命中的候选优先于普通文本）
            // 1) 用户内容/平台装饰一律不算剧名（热评、展开、声明、[ 表情、回复、点赞、已完结/全N集）
            if (trimmed.contains("[") || trimmed.contains("热评") || trimmed.contains("展开") ||
                trimmed.contains("声明") || trimmed.contains("回复") || trimmed.contains("点赞") ||
                RE_FINISHED.containsMatchIn(trimmed)
            ) continue
            if (isAdKeyword(trimmed)) continue
            if (RE_INT.matches(trimmed) || RE_CLOCK.matches(trimmed) || RE_CLOCK3.matches(trimmed)) continue

            // 2) 「第4集 | 闺蜜沈妩和许茵…」卡片：取竖线后的部分作为标题
            RE_TITLE_PIPE.find(trimmed)?.let { m ->
                val candidate = m.groupValues[1].trim()
                if (candidate.length in 4..40 &&
                    (bestRank < 3 || (bestRank == 3 && candidate.length > (bestTitle?.length ?: 0)))) {
                    bestTitle = candidate
                    bestRank = 3
                }
                return@let
            }
            // 3) 含「第X季」的完整剧名（班般的甜蜜爱情第一季）
            if (trimmed.length in 4..40 && RE_TITLE_SEASON.matches(trimmed)) {
                if (bestRank < 3 || (bestRank == 3 && trimmed.length > (bestTitle?.length ?: 0))) {
                    bestTitle = trimmed
                    bestRank = 3
                }
                continue
            }
            // 4) 普通正文候选（排除按钮文字和广告文字）
            if (trimmed.length in 4..30 &&
                !trimmed.contains("免费") &&
                !trimmed.contains("下载") &&
                !trimmed.contains("打开") &&
                // 红果的「（番茄）微短剧备字（2026）第072837号」备案号比标题长，会把标题顶掉
                !trimmed.contains("备字")
            ) {
                if (bestRank < 1 || (bestRank == 1 && trimmed.length > (bestTitle?.length ?: 0))) {
                    bestTitle = trimmed
                    bestRank = 1
                }
            }
        }
        bestTitle?.let { dramaTitle = it }
    }

    private fun isAdKeyword(text: String): Boolean {
        for (keyword in AD_KEYWORDS) {
            if (text.contains(keyword, ignoreCase = true)) return true
        }
        return false
    }

    // ==================== 广告检测 ====================

    /**
     * 只有抖音按文字判定。红果的无障碍树是整叠页面的并集（实测广告页仍带着上一集
     * 62 条剧集文案，连「第12集/选集」都在），文字判定必然误伤，改由画面黑屏判定。
     */
    private fun detectAdFor(pkg: String, allTexts: List<String>): Boolean =
        pkg == DOUYIN_PKG && detectDouyinAd(allTexts)

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
                if (bounds.top < currentScreenBounds().height() * 0.5f && bounds.height() > 100) return true
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
        val bounds = currentScreenBounds()
        // 慢速拖动而不是甩动：广告 SDK 常把「快速上滑」当打开落地页，慢拖只触发翻页
        // 500ms 是速度与安全的折中：再短会被广告当成深链，再长则用户嫌慢
        val sx = bounds.width() / 2f
        Log.d(TAG, "滑动坐标 x=${sx.toInt()} y=${(bounds.height() * 0.66f).toInt()}->${(bounds.height() * 0.36f).toInt()} 屏幕 ${bounds.width()}x${bounds.height()}")
        val path = Path().apply {
            moveTo(sx, bounds.height() * 0.66f)
            lineTo(sx, bounds.height() * 0.36f)
        }
        // 滑动可能被广告创意吃成深链：开 5 秒守护，跳到别的 App 立刻 BACK 回来
        deepLinkGuardUntil = SystemClock.elapsedRealtime() + 5000
        deepLinkBacks = 0
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 500L))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gd: GestureDescription?) {
                Log.d(TAG, "滑动完成")
                // 广告吞手势跳深链时系统事件可能延迟，主动补两次前台检查
                handler.postDelayed({ maybeBlockAdDeepLink(activeWindowPackage()) }, 800)
                handler.postDelayed({ maybeBlockAdDeepLink(activeWindowPackage()) }, 2200)
            }
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
            // 红果等目标 App 已在前台时无条件停止开屏扫描：红果剧间广告的
            // 截图匹配/滑动调度与开屏轮询共用主线程，开屏轮询不退出会
            // 阻塞「命中后延迟滑动」的执行（实测 21:12 命中后滑动未执行）。
            if (active != null && (splashNodeClicks > 0 || TARGET_PACKAGES.contains(active))) {
                logDebug("开屏扫描停止(active=$active)")
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
                val n = ArrayList<String>()
                if (root != null) collectAllTexts(root, n, 0, 15)
                parts.add(
                    "${w.type}:${root?.packageName?.toString()?.substringAfterLast('.') ?: "?"}" +
                        "${if (w.isActive) "A" else ""}${if (w.isFocused) "F" else ""}(${n.size})"
                )
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
        val screen = currentScreenBounds()
        if (b.height() > screen.height() * 0.12f) return false
        val rightSide = b.left > screen.width() * 0.55f
        val topBand = b.top < screen.height() * 0.22f
        val bottomBand = b.top > screen.height() * 0.7f
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
        val screen = currentScreenBounds()
        return b.width() < screen.width() * 0.5f && b.height() < screen.height() * 0.25f
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

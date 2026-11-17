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
import android.graphics.drawable.Icon
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
import android.widget.RemoteViews
import java.util.Random
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

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
        // 常驻通知的最小重发间隔：通知内容只有计数和暂停态，秒级刷新肉眼无差，
        // 省下的是广告动画期主线程上的 binder + RemoteViews 重建
        private const val NOTIF_MIN_GAP_MS = 1200L

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
        private const val MAX_CONSECUTIVE_SWIPES = 3
        private const val RESET_TIMEOUT_MS = 10000L
        // 手势从命中到真正派发正常只有 0.2~0.55 秒；主线程被事件洪压住或进程被系统冻结时
        // 会拖到几十秒（Mi 10 实测 12:05:53 排的手势 12:07:31 才跑），这时屏幕早就不是那条广告，
        // 照着划就是在正片上乱划。超过这个时长直接丢弃，交给轮询重新确认。
        private const val STALE_SWIPE_MS = 2500L
        // 红果广告层底部「上滑继续看短剧」提示条的位置（1080×2340 基准）与匹配阈值
        // 提示条模板匹配：多模板池（assets/ad_hint*.png），底部大范围搜索（matchHint 内实现），
        // 不再用固定坐标/固定尺寸（红果提示条排版与位置随广告渲染浮动）
        private const val HINT_CELL = 4
        private const val HINT_SCORE = 0.85f
        // 模板素材采集机：Mi 10 1080×2340、440dpi、density=2.75。别的机型按密度比缩放模板
        private const val HINT_REF_DENSITY = 2.75f
        // 结构/文案判据都要两拍，防住切页瞬间的过渡布局刚好凑齐特征
        private const val STRUCT_STREAK_NEED = 2
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
        // 窗口刚开始 0.5 秒内不点：厂商 ROM 的启动快照期会画着上一次退出时的界面，
        // 这时扫到的「跳过」可能是已经不存在的广告，点下去打在刚显示出来的首页上（业界同参数）
        private const val SPLASH_FIRST_CLICK_MS = 500L
        // 节点点击与盲点各记各的预算：前两次空扫不能把额度花光，真广告常常 8 秒后才加载
        private const val SPLASH_NODE_MAX = 2
        // 点过跳过按钮后，最多再等 6 秒确认开屏真的消失（超过就当这一条没数到）
        private const val SPLASH_CLOSE_CONFIRM_MS = 6000L
        private val SKIP_LABEL = Regex("""(?i)^(跳过|跳過|关闭|關閉|skip|close)(广告|廣告)?(\s*\d+\s*[s秒]?)?$""")
        private val SKIP_CONTAINS = listOf("跳过", "跳過", "关闭", "關閉", "skip", "close")
        // 「关闭」类文案太常见：B站视频页的「关闭弹幕」就被当成开屏按钮点过
        private val SKIP_CONTAINS_EXCLUDE = listOf(
            "弹窗", "提示", "引导", "通知", "弹幕", "倍速", "投屏", "清晰度",
            "全屏", "连播", "收藏", "分享", "下载", "评论", "客服", "字幕", "悬浮",
            // 系统控制中心/通知栏的开关描述（如「飞行模式,关闭」）绝不能当跳过键点
            "飞行", "模式", "蓝牙", "Wi-Fi", "wifi", "WLAN", "热点", "静音",
            "震动", "勿扰", "护眼", "深色", "亮度", "旋转", "数据", "流量",
            "同步", "省电", "电池", "自动",
            // GKD 全局开屏规则的 excludeMatches 原文里排掉的：播放器设置项
            // （跳过片头/片尾）、弹窗动词、引导流程按钮，这些出现在「跳过」旁边时一律不点
            "视频", "片头", "片尾", "帮助", "取消", "退出", "下一步", "完成",
            "设置", "兴趣", "偏好", "同意", "历史", "书签"
        )
        // 只有出现在右上/右下角的纯数字才算倒计时按钮（美团类开屏常见）
        private val SKIP_COUNTDOWN = Regex("""^(跳过\s*)?\d{1,2}\s*[sS秒]?$""")
        private val SPLASH_AD_WORDS = listOf("广告", "廣告", "赞助", "投诉", "摇一摇", "摇动手机", "跳转第三方")
        // 社区订阅（GKD 默认/AIsouler/Lin-arm、SKIP 规则库）里反复出现的开屏跳过件短名。
        // 具名 id 比裸文案可信：文案可能被云控成广告入口，具名跳过件不会。
        private val SPLASH_SKIP_IDS = listOf(
            "tt_splash_skip_btn",                 // 穿山甲 Pangle
            "tt_reward_full_count_down_after",
            "adsdk__ids_component_close",
            "ksad_splash_circle_skip_view",       // 快手联盟
            "ksad_splash_circle_skip_left_view",
            "ksad_splash_skip_view",
            "ksad_auto_close_btn",
            "tobid_splash_skip_ll",               // TopOn 聚合（v2.4.5 实测命中 121x54）
            "tobid_splash_skip_text",
            "anythink_myoffer_btn_close_id",
            "hiad_skip_text",                     // 华为广告
            "hiad_banner_close_button",
            "gdt_count_down_view",                // 腾讯优量汇
            "gdt_v2_banner_ad_close_btn",
            "ms_skipview",
            "adgain_splash_skip_ll",
            "splash_count_down_view",
            "welcome_ad_view_skip",
            "ad_skip_text",
            "only_skip_rl",
            "beizi_interstitial_ad_close",        // 倍孜
            "splash_ad_close",                    // 闲鱼（实测命中）
            "tv_skip",                            // 小蚕（实测命中）
            "btn_skip",
            // 以下补自 GKD 两份官方订阅 + SKIP 规则库（社区跨订阅重复出现，非我们自创）
            "jump_over",                          // vivo 系
            "vlion_ad_closed",                    // CSDN 穿山甲封装
            "homesplash_close_fullscreen",        // 菜鸟
            // v2.7.4 补自 GKD 五份社区订阅 195 组开屏规则的跨订阅高频具名件。
            // 只补下面这两条「语义正则看不出来」的：广告词和关闭词被 item 隔开了。
            // 其余社区短名（csj_splash_skip、buuh_splash_skip、adCloseButton、skipAd、
            // splash_close_layout、navi_ad_close、cd_appstore_splash_skip…）由 AD_SKIP_ID 统一收下。
            // 明确不补的：ksad_ / csj_ / gdt_ / anythink_ 这类前缀族——社区自己注明它们是
            // 「父级容器锚点」，规则安全靠父级契约撑着，我们照抄就等于去点整块广告容器。
            "msa_splash_item_close_tv",           // 美数（美团系）开屏关闭
            "jl_splash_close"                     // 三家订阅重复出现的开屏关闭件
            // 同时撤掉上一轮只凭「社区重复出现」收下的 mjumpbtn / iv_jump / adview_jump：
            // 这轮把 195 组原文扒开看，jump 系短名在订阅里的原文注释是
            // 「广告跳转按钮，父级需为 ad/splash/close 语义」——它既可能是跳过键，
            // 也可能是广告入口本身。我们没有父级契约，这三条又从来没有现场命中记录，按底线不赌。
        )
        /**
         * 语义复合：短名里「广告 / 开屏 / 插屏」和「跳过 / 关闭」要挨着出现才算跳过件。
         * 社区规则表里出现次数最高的其实是 `x`、`close`、`iv_close`、`timer` 这类裸短名，
         * 它们靠「规则锚定到某个 App 的某个页面 + 父容器还要匹配 ad/splash」才安全，
         * 我们没有父容器契约，按 contains 收下就等于让「关闭弹幕」「点击下载」也能命中
         * ——正是用户划红线的地方，所以一律不收。
         * 反过来 `ad_sensor_view`、`iv_adsensor`（摇一摇）、`feedAdItem2`（信息流广告本体）
         * 这几个社区确实在点的短名更明确排除：点它们就是点进广告。
         * 词首约束（^ 或分隔符之后）是为了挡住 thread_close、upload_skip 这种
         * 字母凑巧连成 "ad_close"、"ad_skip" 的无关控件。
         */
        private val AD_SKIP_ID = Regex(
            """(^|[_-])(ad|ads|splash|interstitial|fullad)[_-]?(skip|close)""" +
                """|(^|[_-])(skip|close)[_-]?(ad|ads)([_-]|$)"""
        )
        private val SPLASH_SKIP_PKGS = listOf(
            // pkg=android 是系统框架窗口（音量条、权限弹窗、Toast 容器），实测红果播放时
            // 每分钟都会来一次，被当成「应用切换」后会起一轮开屏扫描并顶掉前台包名
            "android",
            "com.android.systemui", "com.android.launcher", "com.android.settings",
            "com.phoenix.read", "com.miui.home", "com.miui.personalassistant",
            // 系统 UI/安全中心：通知栏、控制中心、系统弹窗不是开屏广告，绝不能扫更不能点
            "miui.systemui.plugin", "com.miui.systemui", "com.miui.securitycenter",
            "com.miui.rom", "com.android.providers.media",
            // 聊天类界面里「关闭/跳过」字样到处都是，误点代价最高（GKD 开屏组同样把它们整组关掉）
            "com.tencent.mm", "com.tencent.mobileqq",
            // ===== 以下取自 GKD 五份订阅的「不参与自动化」清单，但只挑误触代价不可承受的类别 =====
            // 那份清单共 668 个包，整表照抄会把斗鱼（我们的开屏正样本）、快手、微博、小红书一起关掉：
            // 它记的是「这个订阅没给这个 App 写规则」，不是「这个 App 不能点」。
            // 钱与账号：点错一下是转账、绑卡、买基金，不是一条广告
            "com.eg.android.AlipayGphone", "com.unionpay", "com.chinatelecom.bestpayclient",
            "com.lakala.sh", "com.shhk.change.wallet", "cmb.pb", "cn.com.spdb.mobilebank.per",
            "com.bankcomm.Bankcomm", "com.chinamworld.main", "com.chinamworld.bocmbci",
            "com.yitong.mbank.psbc", "com.ecitic.bank.mobile", "com.cebbank.mobile.cemb",
            "com.bank.gz.cmbc.b2c.mobilebank", "com.szsmk.lenying", "com.hexin.plat.android",
            "com.eastmoney.android.fund", "com.pywm.fund", "com.xueqiu.fund",
            "com.sinosoft.lifechaiyangwang", "com.zy.fosbx",
            // 政务与医疗：办理的是证件、挂号，页面上的「关闭」是流程按钮不是广告
            "com.example.servyouappzhuhai", "com.tmri.app.main", "com.hanweb.android.zhejiang.activity",
            "me.chunyu.ChunyuDoctor", "com.haoshilei.doctorweb", "com.zhf.helse",
            // 企业数据：WPS / 企业微信里一个「关闭」可能是关掉别人发的文档
            "com.tencent.wework", "com.kingsoft", "cn.wjxcloud.wjx",
            // 渠道游戏开屏是误触下载的重灾区（用户原话：待会给我装别的软件就完了）
            "com.mihoyo.bh3", "com.netease.mrzhna",
            // 厂商系统控制面与输入法：MIUI 分屏、负一屏之外，这些页面同样不归我们点
            "com.miui.hybrid", "com.baidu.input",
            "com.huawei.systemmanager", "com.huawei.numberidentity", "com.huawei.android.internal.app",
            "com.hihonor.id", "com.hihonor.health", "com.hihonor.mms", "com.hihonor.camera",
            "com.hihonor.android.totemweather",
            "com.heytap.health", "com.heytap.openid", "com.coloros.healthcheck", "com.coloros.note",
            "com.oneplus.note", "com.oplus.camera", "com.oplus.nas",
            "com.vivo.gallery", "com.vivo.deviceinfo", "com.vivo.doclist", "com.vivo.dream.music",
            "com.vivo.localmusic", "com.android.vivo.tws.vivotws", "com.android.BBKClock"
        )

        // ========== 统计数据 ==========
        @Volatile var instance: AdSkipAccessibilityService? = null
            private set

        var isServiceEnabled = false
            private set

        // 短剧信息
        var dramaTitle = "未检测到"
            private set
        var currentEpisode = "未知"
            private set
        var lastScreenTexts: List<String> = emptyList()
            private set

        // 广告统计（累计值在 Counters 里持久化，这里只留最近一次的信息）
        var lastAdKeyword = "-"    // 最后一次命中的关键词
            private set
        var lastAdTime = "-"       // 最后一次检测到广告的时间
            private set

        // 界面检测统计
        var screenCheckCount = 0    // 总共检查了多少次
            private set

        // 调试日志
        var debugLog = "等待连接..."
            private set

        // 内部变量
        private var lastSwipeTime = 0L
        private var consecutiveSwipes = 0
        private var lastAdDetectedTime = 0L
        private var wasAdBeforeSwipe = false
        private var lastShotAt = 0L
        private var darkSampleInFlight = false
        // 广告深链兜底：滑动后 5 秒内前台被广告顶到别的 App，立刻返回红果
        private var deepLinkGuardUntil = 0L
        private var deepLinkBacks = 0
        // 开屏点「跳过」同样可能被广告热区吃成深链，守护期内期望的前台是这个包
        private var splashGuardPkg = ""

        // ===== 红果取证探针（只打日志，不参与任何判定与手势）=====
        // 事件位直方图：TYPE_WINDOW_CONTENT_CHANGED 的 contentChangeTypes 各位计数
        private const val PROBE_FLUSH_MS = 5000L
        private val probeContentBits = HashMap<Int, Int>()
        private var probeScrolled = 0
        private var probeFlushAt = 0L
        private var probeBeatAt = 0L
        // 分区签名心跳：4 秒一行，用来在真机上复核判据没漂
        private var zoneLogAt = 0L
        private var zoneLastSign = -1

        // 识别速度三档：只动采样节奏和确认窗口，判据本身不变。
        // 快档贴近安全下限（切集瞬态实测约 1 秒），稳档拉长窗口换省电。
        // 采样间隔：红果广告动画能推到 150ms 一个事件，不节流就是每秒四五次全树遍历，
        // 主线程被压死后「已执行滑动」会拖到十几秒后才跑（实测广告出现到划走隔了 36 秒）。
        private val structSampleMs: Long
            get() = when (Settings.speedTier) { 2 -> 180L; 0 -> 350L; else -> 220L }
        // 单次遍历的节点上限，挡住异常深的树
        private const val ZONE_NODE_CAP = 900
        // 广告签名要连续挂够这么久才算广告：切集动画的瞬态只活 ~1 秒，广告一挂几十秒
        private val structHoldMs: Long
            get() = when (Settings.speedTier) { 2 -> 1400L; 0 -> 2500L; else -> 1500L }
        // 竖屏广告自带「上滑继续观看短剧」文案，是广告层自己的话，不用等那么久
        private val hintHoldMs: Long
            get() = when (Settings.speedTier) { 2 -> 600L; 0 -> 1200L; else -> 700L }
        // 倒计时型提示条（「3s后可继续上滑观看短剧」）在倒计时结束前滑动会被广告层吃掉，
        // 读到 x 秒就把出手时刻钉在 x 秒后（+300ms 余量），最多认 20 秒，防误读把等待拉太长
        private const val COUNTDOWN_CAP_SEC = 20
        private const val COUNTDOWN_MARGIN_MS = 300L
        // 广告层差分：广告盖住播放器时原生壳只剩广告自己的控件，可见节点数会大跌。
        // 正片基线每台设备自己学（EMA 0.8/0.2），落到基线 70% 以下且连挂够时长才算广告 ——
        // 切集黑屏同理但只活一两秒，被时长门槛挡在外面。用比例而非绝对个数，换机型不用改常数。
        private val layerHoldMs: Long
            get() = when (Settings.speedTier) { 2 -> 3200L; 0 -> 5500L; else -> 4000L }
        private const val LAYER_RATIO = 0.70f
        private const val PLAYER_BASE_MIN = 60f
        // 候选未出手落行的门槛：挂够这么久、同一种挡法隔这么久再记，免得加载态刷满台账
        private const val CAND_HOLD_MS = 1500L
        private const val CAND_LOG_GAP_MS = 30000L
        // 静默样本的采样间隔：判据一条都没擦边时，每这么久留一行树形快照。
        // 10 分钟一行 = 每小时最多 6 行，台账留 200 行够回看三十多个小时的屏幕时间，
        // 再密就要开始挤掉真正的广告行了。
        private const val SAMPLE_GAP_MS = 600_000L
        private const val PLAYER_WINDOW_MS = 30_000L
        // 红果已知放短剧的两个界面。差分/直播面这些判据只在播放器上成立，台账里凡是别的
        // 界面名都单独记一行：那是「判据出手位置不对」这类事故的唯一现场线索。
        private val PLAYER_ACTIVITIES = setOf("ShortSeriesActivity", "ShortSeriesLandActivity")
        // 追拍上限：12 × 220ms ≈ 2.6 秒，够确认一次，又不会变成常驻轮询
        private const val STRUCT_NUDGE_MAX = 12

        private val handler = Handler(Looper.getMainLooper())
        // 手势随机化只在本进程内用，不需要可复现序列
        private val gestureRandom = Random()
    }

    private val biliBubble by lazy { SkipBubble(this) }
    // 红果剧间广告「跳过」后的撤回气泡（复用 SkipBubble，悬浮窗权限即可）
    private val undoBubble by lazy { SkipBubble(this) }
    private var undoUuid = 0L
    private var undoUntil = 0L
    // 倒计时锁定型广告的快速重试状态（复核失败时进入，成功或放弃后退出）
    private var retryingAd = false
    private var lockRetries = 0
    // 上一次采样的屏幕方向：旋转后搜索带/手势坐标整体换向，连续匹配不能跨方向累计
    private var lastLandscape: Boolean? = null
    // 结构判据连续拍数（零截图主链路）
    private var structStreak = 0
    // 上一次做全树采样的时刻（事件比采样密的时候丢掉多余遍历，保住主线程）
    private var structSampleAt = 0L
    // 本轮广告签名第一次出现的时刻（0 = 当前没有签名），用来量「挂了多久」
    private var structAdSince = 0L
    // 最近一帧仍判为广告的时刻：手势出手前用它复核「广告还在屏上」，
    // 排在队列里太久的手势（实测晚 98 秒）不能照着划正片。
    private var lastAdFrameAt = 0L
    // 待出手手势的排队时刻（elapsedRealtime），超过 STALE_SWIPE_MS 就丢弃
    private var swipeQueuedAt = 0L
    // 倒计时型广告：第一次读到「x秒后」的时刻与最大秒数，出手时刻 = 起点 + x 秒
    private var countdownAdSince = 0L
    private var countdownSecs = 0
    // 正片可见节点数基线：每台设备自己学，空树广告靠它做差分判定（绝对阈值跨机型必失效）
    private var playerVisBase = 0f
    private var lastPlayerFrameAt = 0L
    private var layerAdSince = 0L
    // 台账用的三个时刻：命中（elapsedRealtime）、差分候选起点、候选上次落行时刻
    private var adHitAt = 0L
    private var layerCandSince = 0L
    private var layerCandLogAt = 0L
    private var sampleLogAt = 0L
    // 台账用的界面名片：红果当前是哪个 Activity，只在窗口事件里拿得到（无障碍没有查别的应用
    // 前台 Activity 的接口）。判据只按包名收口，一旦在商城/听书这类页面上划一下就是事故，
    // 所以每条广告行都要带上当时站在哪个界面；服务比红果晚绑上时会是"未知"，直到下一次窗口事件。
    private var hongguoActivity = "未知"
    private val seenHongguoActivities = mutableSetOf<String>()
    // 红果在前台却连续取不到树：判据全盲，和「这段就没广告」在记录上长得一模一样，得单独落一行
    private var nullRootStreak = 0
    private var nullRootLogAt = 0L
    // 上一次落进台账的闸门状态（null = 本进程还没落过），用来只在真的变化时补一行
    private var gateNoted: String? = null
    // 签名挂上后自己追拍的次数（静态广告不再推 CONTENT_CHANGED，只等事件确认要 2 秒）
    private var structNudges = 0
    private val structNudge = Runnable {
        val root = activeWindowRoot()
        if (root != null && root.packageName?.toString() in HONGGUO_PACKAGES) {
            processScreen(root, System.currentTimeMillis())
        } else {
            structNudges = 0
        }
    }
    // 本轮是否已记过成功（每次新命中重置）
    private var successCounted = false
    // 截图分析专用单线程：全屏位图转换 + NCC 匹配实测 150~335ms，压在无障碍主回调线程上
    // 会同时堵住事件处理和手势派发，改成串行工作线程后主线程只负责收 buffer 和判定
    private val shotExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "hg-shot")
    }
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
            if (!screenOn() || Settings.paused) {
                biliBubble.hide()
                return
            }
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

    private var lastNotifAt = 0L

    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        instance = this
    }

    /** 通知栏暂停/恢复后立刻刷新常驻通知；暂停时顺手收掉气泡。 */
    fun refreshNotification() {
        if (Settings.paused) {
            biliBubble.hide()
            undoBubble.hide()
        }
        updateNotification(force = true)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceEnabled = true
        foregroundPkg = activeWindowPackage()
        foregroundAt = SystemClock.elapsedRealtime()
        structStreak = 0
        structAdSince = 0L
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
        val paused = Settings.paused
        val title = if (paused) "$notificationTitle · 已暂停" else notificationTitle
        val text = if (paused) "已暂停，点「恢复」继续自动跳过"
            else "今日 ${Today.count} 次 · B站 ${Counters.of(SkipStat.BILI_SUCCESS)}" +
                " · 开屏 ${Counters.of(SkipStat.SPLASH_SUCCESS)}" +
                " · 红果 ${Counters.of(SkipStat.HG_SUCCESS)}"
        // MIUI 自定义主题会吃掉标准 action 按钮，改用 RemoteViews 把按钮画进内容区
        val actionPi = PendingIntent.getBroadcast(
            this, 1,
            Intent(this, NotifActionReceiver::class.java).setAction(NotifActionReceiver.ACTION_TOGGLE_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val custom = RemoteViews(packageName, R.layout.notif_control).apply {
            setTextViewText(R.id.notif_title, title)
            setTextViewText(R.id.notif_text, text)
            setTextViewText(R.id.notif_action, if (paused) "恢复" else "暂停")
            setOnClickPendingIntent(R.id.notif_action, actionPi)
            setOnClickPendingIntent(R.id.notif_root, pendingIntent)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pendingIntent)
                .setCustomContentView(custom)
                .setStyle(Notification.DecoratedCustomViewStyle())
                .addAction(pauseAction(paused))
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pendingIntent)
                .setCustomContentView(custom)
                .setStyle(Notification.DecoratedCustomViewStyle())
                .addAction(pauseAction(paused))
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build()
        }
    }

    /** 通知栏的一键暂停/恢复：广播回来翻转全局开关，再刷新这条通知。 */
    private fun pauseAction(paused: Boolean): Notification.Action {
        val intent = Intent(this, NotifActionReceiver::class.java)
            .setAction(NotifActionReceiver.ACTION_TOGGLE_PAUSE)
        val pi = PendingIntent.getBroadcast(
            this, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val icon = Icon.createWithResource(
            this, if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
        )
        return Notification.Action.Builder(icon, if (paused) "恢复" else "暂停", pi).build()
    }

    /** 暂停状态这类用户能立刻察觉的变化用 force 立刻刷，其余走节流。 */
    private fun updateNotification(force: Boolean = false) {
        // 这条通知只在文案真的变了才有必要重发，而 buildNotification 要读两次
        // SharedPreferences、重建一次 RemoteViews 再走一次 binder。广告动画期
        // 「结构广告层 第N拍」这类日志约 4.5 条/秒，逐条重建通知会把主线程吃满，
        // 表现就是命中后那次上滑迟迟不执行（实测晚到近百秒）。
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotifAt < NOTIF_MIN_GAP_MS) return
        lastNotifAt = now
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun logDebug(msg: String) {
        debugLog = msg
        Log.d(TAG, msg)
        DiagLog.log(msg)
        updateNotification()
    }

    // ==================== 事件监听 ====================

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        probeEvents(event)
        val pkg = event.packageName?.toString()
        val appSwitched = pkg != null && pkg != foregroundPkg
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != null) {
            // 系统浮层（pkg=android 的框架窗口、输入法、权限弹窗、广告 SDK 浮层）也会送窗口
            // 事件。只按事件包名更新前台归属，目标 App 明明在前台也会被反复顶掉，事件过滤
            // 与「30 秒兜底」同时失效（实测红果播放器在前台 7 分钟零巡检、零树扫描，
            // 前台包名被钉死在 android 上）。活动窗口是目标 App 时以它为准。
            // 自己的倒计时/撤回气泡也是本包的窗口事件，同样不能当成「切到了本 App」。
            val active = activeWindowPackage()
            foregroundPkg = when {
                TARGET_PACKAGES.contains(active) -> active
                pkg == packageName -> active.ifEmpty { pkg }
                else -> pkg
            }
            foregroundAt = SystemClock.elapsedRealtime()
            if (HONGGUO_PACKAGES.contains(pkg)) noteHongguoActivity(event.className?.toString().orEmpty())
            try {
                maybeBlockAdDeepLink(pkg)
            } catch (e: Exception) {
                Log.w(TAG, "深链兜底异常: ${e.message}")
            }
        }
        // 全局暂停：前台归属和深链守护照常更新，其余检测/手势全部停手
        if (Settings.paused) return
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
        if (pkg in TARGET_PACKAGES || pkg == packageName || pkg == splashGuardPkg) return
        // 输入法弹到前台是点完广告后聚焦变化的常态，不是深链：实测点 B站 前贴片
        // 「跳过 3」后 0.7 秒搜狗输入法上来，被当成跳转多按了一次 BACK
        if (pkg.contains("inputmethod")) return
        // 系统框架窗口（pkg=android）与桌面/通知栏同样不是深链：红果播放时它们每分钟都会
        // 冒一次，误判成跳转就会真的按 BACK，把用户从播放器里踢出去
        if (pkg == "android" || pkg == "com.miui.home" || pkg == "com.android.systemui") {
            deepLinkGuardUntil = 0
            return
        }
        if (deepLinkBacks >= 2) {
            deepLinkGuardUntil = 0
            return
        }
        deepLinkBacks++
        logDebug("拦截广告跳转：$pkg → 返回原应用(${deepLinkBacks})")
        handler.postDelayed({
            val top = activeWindowPackage()
            if (top != splashGuardPkg && top !in TARGET_PACKAGES && top != "com.miui.home") {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }, 350)
    }

    /** 滑动节流 + 连续滑动预算：命中后统一在 onAdDetected 里检查，避免事件驱动下重复滑动。 */
    private fun canSwipeNow(now: Long): Boolean {
        if (now - lastSwipeTime < SWIPE_DELAY_MS) return false
        if (consecutiveSwipes >= MAX_CONSECUTIVE_SWIPES) {
            if (now - lastAdDetectedTime <= RESET_TIMEOUT_MS) return false
            consecutiveSwipes = 0
        }
        return true
    }

    // ==================== 红果取证探针（只读、只打日志）====================

    /**
     * 事件侧探针：量「红果播放时到底哪些 contentChangeTypes 在刷」和「切集是不是有
     * TYPE_VIEW_SCROLLED」。这两条决定判定层能不能零截图、事件订阅能不能收窄省电。
     */
    private fun probeEvents(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg !in HONGGUO_PACKAGES) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                var bits = event.contentChangeTypes
                var shift = 0
                while (bits != 0) {
                    if (bits and 1 != 0) probeContentBits[shift] = (probeContentBits[shift] ?: 0) + 1
                    bits = bits ushr 1
                    shift++
                }
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                probeScrolled++
                val cls = event.className?.toString()?.substringAfterLast('.').orEmpty()
                val sinceSwipe = if (lastSwipeTime == 0L) -1 else System.currentTimeMillis() - lastSwipeTime
                Log.d(TAG, "探针·SCROLLED#$probeScrolled $cls ${event.fromIndex}→${event.toIndex}" +
                    " items=${event.itemCount} 距上滑${sinceSwipe}ms")
            }
        }
        val now = SystemClock.elapsedRealtime()
        if (now - probeFlushAt >= PROBE_FLUSH_MS) {
            probeFlushAt = now
            if (probeContentBits.isNotEmpty()) {
                Log.d(TAG, "探针·CONTENT " + probeContentBits.entries.sortedBy { it.key }
                    .joinToString(" ") { "${contentBitName(it.key)}:${it.value}" })
                probeContentBits.clear()
            }
        }
    }

    /**
     * 位号→名字。值取自 android-34 的 android.jar（javap -constants 实测）：
     * SUBTREE=1、TEXT=2、CONT_DESC=4、PANE_TITLE=8、PANE_APPEARED=16、PANE_DISAPPEARED=32、
     * STATE_DESCRIPTION=64，再往上依次是 DRAG_*=128/256/512、CONTENT_INVALID=1024、ERROR=2048、ENABLED=4096。
     */
    private fun contentBitName(bit: Int): String = when (bit) {
        0 -> "SUBTREE"
        1 -> "TEXT"
        2 -> "CONT_DESC"
        3 -> "PANE_TITLE"
        4 -> "PANE_IN"
        5 -> "PANE_OUT"
        6 -> "STATE_DESC"
        else -> "bit$bit"
    }

    /**
     * 分区签名日志：判定翻转或 4 秒心跳时落一行。
     * Mi 10（2340×1080）实测横屏只有剧间广告会同时凑齐三区：
     *   剧间广告   上左=2 上右=5 底条=1~2 广告角标=true  → 判定 1
     *   正片播放   上左=0 上右=0 底条=0                 → 0
     *   选集/倍速  上左=1 上右=0 底条=0                 → 0
     *   首页信息流 上左=9 上右=0 底条=0                 → 0
     * 广告层挂在树深 21~23 层，早先「广告一个字都不进树」是被 15/18 层截断坑了。
     */
    private fun probeZones(sig: ZoneSig) {
        val now = SystemClock.elapsedRealtime()
        val sign = if (sig.adScreen) 1 else 0
        if (sign == zoneLastSign && now - zoneLogAt < 4000L) return
        zoneLogAt = now
        zoneLastSign = sign
        val wins = try { windows.size } catch (e: Exception) { -1 }
        Log.d(TAG, "探针Z 上左=${sig.ul} 上右=${sig.ur} 底中=${sig.bm} 底条=${sig.bb} " +
            "可见=${sig.vis} 横屏=${sig.landscape} 播放器控件=${sig.playerUi} 提示条=${sig.hintText} " +
            (if (sig.hintCountdown > 0) "倒计时=${sig.hintCountdown}s " else "") +
            "守卫=${sig.playGuard} 直播面=${sig.liveRender} 窗口=$wins 判广告=$sign 文案[${sig.texts.take(5).joinToString(" ")}] " +
            "id[${sig.ids.joinToString(" ")}]")
    }

    /**
     * 一次遍历同时拿到分区签名和屏上文案：子节点要跨进程取，text/边界是随节点一起回来的
     * 快照字段，顺路读几乎免费 —— 所以屏上信息只数这一棵树，不再单独跑文案遍历。
     * 剪枝依据是 isVisibleToUser 沿父链继承：父不可见整棵子树都不可能被看到。
     */
    private fun zoneSignature(rootNode: AccessibilityNodeInfo): ZoneSig? {
        val sb = currentScreenBounds()
        val sw = sb.width().toFloat()
        val sh = sb.height().toFloat()
        if (sw <= 0f || sh <= 0f) return null
        var ul = 0   // 左上小件（返回箭头）
        var ur = 0   // 右上小件（上滑胶囊）
        var bm = 0   // 底部中间小件
        var bb = 0   // 底部通栏横条（广告操作条）
        var vis = 0
        var seen = 0
        var playerUi = false
        var hintText = false
        var hintCountdown = 0
        var playGuard = false
        var liveRender = false
        val texts = ArrayList<String>(24)
        val ids = ArrayList<String>(10)
        fun walk(n: AccessibilityNodeInfo, d: Int) {
            if (d > 30 || seen >= ZONE_NODE_CAP) return
            seen++
            if (!n.isVisibleToUser) return
            vis++
            val vid = n.viewIdResourceName
            // 直播广告的渲染面：穿山甲直播 SDK 自己的资源名（不是红果的混淆短名，改版稳）。
            // 正剧用 SurfaceView 播，树里不会出现这一枚；直播卡片整屏是视频，文案全画在视频层里。
            if (!liveRender && vid != null && vid.endsWith(LIVE_RENDER_ID_SUFFIX)) liveRender = true
            val label = n.text?.toString()?.trim()?.ifEmpty { null }
                ?: n.contentDescription?.toString()?.trim()?.ifEmpty { null }
            if (label != null) {
                // 竖屏要有这条才敢判广告：信息流/详情页的播放器控件在树里但不可见，
                // 只有真在播放页才会「全屏观看」可见（实测 00:04:47 信息流广告卡片就是靠这条挡掉的）
                if (label.contains("全屏观看")) playerUi = true
                // 广告层自己写进树里的提示条：竖屏「上滑继续观看短剧」，横屏同理带这条
                if (HINT_TEXT_RE.containsMatchIn(label)) {
                    hintText = true
                    // 「3s后可继续上滑观看短剧」：倒计时没走完前滑动会被广告吃掉
                    val m = COUNTDOWN_RE.find(label)
                    val secs = m?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    if (secs > hintCountdown) hintCountdown = secs
                }
                // 正片 UI 的反向闸门：命中即整屏不算广告（宁漏勿误）
                if (!playGuard && PLAY_GUARD_RE.containsMatchIn(label)) playGuard = true
                if (texts.size < 24) texts.add(label)
                // 广告层关键节点无条件抄 id（不管多大），这是逆向要的靶子
                if (vid != null && ids.size < 12 &&
                    (HINT_TEXT_RE.containsMatchIn(label) || label == "广告" || label.contains("立即领取"))
                ) {
                    val entry = (n.className?.toString()?.substringAfterLast('.') ?: "?") + "#" + vid
                    if (!ids.contains(entry)) ids.add(entry)
                }
            }
            val b = Rect()
            n.getBoundsInScreen(b)
            val w = (b.right - b.left).toFloat()
            val h = (b.bottom - b.top).toFloat()
            if (w > 0f && h > 0f) {
                val cx = (b.left + b.right) / 2f
                val cy = (b.top + b.bottom) / 2f
                val area = w * h
                // 最小件尺寸：树里常驻一个 1×1 的 View/fj（所有状态都有），不过滤会永远算成「左上小件」
                val big = w >= 0.03f * sh && h >= 0.03f * sh
                if (big && cx < 0.20f * sw && cy < 0.22f * sh && area <= 0.02f * sw * sh) ul++
                if (big && cx > 0.60f * sw && cy < 0.22f * sh && area <= 0.06f * sw * sh) ur++
                if (big && cx in 0.30f * sw..0.70f * sw && cy >= 0.80f * sh &&
                    area <= 0.06f * sw * sh
                ) bm++
                if (w >= 0.88f * sw && h <= 0.30f * sh && b.bottom >= 0.94f * sh) bb++
                // 探针顺路抄 id：viewId 是节点快照里的字段，不额外跨进程。
                // 只收中小件 —— 全屏容器一抓一大把，会把名额吃光，广告层的小卡片就看不见了。
                if (vid != null && ids.size < 12 && area >= 0.003f * sw * sh && area <= 0.25f * sw * sh) {
                    val entry = (n.className?.toString()?.substringAfterLast('.') ?: "?") + "#" + vid
                    if (!ids.contains(entry)) ids.add(entry)
                }
            }
            for (i in 0 until n.childCount) {
                if (seen >= ZONE_NODE_CAP) break
                n.getChild(i)?.let { walk(it, d + 1) }
            }
        }
        walk(rootNode, 0)
        val sig = ZoneSig(ul, ur, bm, bb, vis, false, sw > sh, playerUi, hintText, hintCountdown,
            playGuard, liveRender, ids, texts)
        // 「广告」合规角标只在判据已经成立后才查：findAccessibilityNodeInfosByText 是跨进程查询，
        // 每个事件都问一遍是主线程被拖死的原因之一。横屏必须先查它才能定 adLike，所以按 adGeo 提前。
        return if (!sig.adScreen && !sig.adGeo) sig else sig.copy(adTag = try {
            rootNode.findAccessibilityNodeInfosByText("广告").any { it.isVisibleToUser }
        } catch (e: Exception) {
            false
        })
    }

    // ==================== 定时轮询 ====================

    private fun performPeriodicCheck() {
        // 台账整段安静，第一件要排除的是"它当时根本没在跑"：手动暂停、关了红果开关、服务重新绑定，
        // 都会让记录断流却看不出原因。这里只在状态真的变了时落一行，顺带当成"服务在线"的心跳标记。
        val gate = when {
            Settings.paused -> "手动暂停，期间不跳广告"
            !Settings.hongguoEnabled -> "红果开关已关"
            else -> "运行中"
        }
        if (gate != gateNoted) {
            gateNoted = gate
            AdLog.note("跳跳狐 $gate")
        }
        if (!screenOn() || Settings.paused) return
        // 前台归属每拍都以活动窗口校正：窗口事件会把前台包名顶到系统浮层上，而
        // 「30 秒兜底刷新」只要还有窗口事件进来就会被无限推迟，永远等不到
        val active = activeWindowPackage()
        if (active.isNotEmpty() && active != foregroundPkg) {
            foregroundPkg = active
            foregroundAt = SystemClock.elapsedRealtime()
        }
        val beatNow = SystemClock.elapsedRealtime()
        if (beatNow - probeBeatAt >= 30_000L) {
            probeBeatAt = beatNow
            Log.d(TAG, "探针·心跳 巡检=$screenCheckCount 前台=$foregroundPkg 活动=$active")
        }
        if (foregroundPkg == BiliUiSkip.BILI_PKG) ensureBiliTicker()
        if (!Settings.hongguoEnabled) return
        // 按需唤醒：只有红果在前台才扫描界面
        if (!TARGET_PACKAGES.contains(foregroundPkg)) return
        // 启动切换瞬间 rootInActiveWindow 可能仍是上一个 App，以活动窗口为准
        if (!TARGET_PACKAGES.contains(active)) return
        val rootNode = activeWindowRoot()
        if (rootNode == null) {
            // 每20次打印一次"等待窗口"日志，避免刷屏
            if (screenCheckCount % 20 == 0) {
                logDebug("等待窗口... (检查${screenCheckCount}次)")
            }
            // 红果在前台却读不到树 = 判据全盲，和「这段本来就没广告」在台账里长得一模一样。
            // 轮询约 1.2 秒一拍，攒到 25 拍（约 30 秒）落一行，说明这段时间工具确实是瞎的。
            if (HONGGUO_PACKAGES.contains(foregroundPkg)) {
                nullRootStreak++
                val blindAt = SystemClock.elapsedRealtime()
                if (nullRootStreak >= 25 && blindAt - nullRootLogAt >= 60_000L) {
                    nullRootLogAt = blindAt
                    AdLog.note("红果@$hongguoActivity 取不到界面树 连续${nullRootStreak}拍（这段时间判据全盲，广告不会跳）")
                }
            }
            return
        }
        nullRootStreak = 0
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
            // MIUI 的分屏/小窗有时只置 focused 不置 active，只看 active 会拿到空
            val ws = windows
            (ws.firstOrNull { it.isActive } ?: ws.firstOrNull { it.isFocused })
                ?.root?.packageName?.toString()
        } catch (e: Exception) {
            null
        }
        return fromWindows ?: rootInActiveWindow?.packageName?.toString().orEmpty()
    }

    /** 读界面文本时优先用活动窗口，避免读到下层残留页面的内容。 */
    private fun activeWindowRoot(): AccessibilityNodeInfo? {
        val root = try {
            val ws = windows
            (ws.firstOrNull { it.isActive } ?: ws.firstOrNull { it.isFocused })?.root
        } catch (e: Exception) {
            null
        }
        return root ?: rootInActiveWindow
    }

    // ==================== 识别速度三档 ====================
    // 0=稳（省电）、1=平衡、2=快。采样节奏和确认窗口随档位变，判据不变。

    /** 目标 App 前台轮询兜底间隔：快 0.9s / 平衡 1.2s / 稳 1.8s。 */
    private fun checkIntervalActive(): Long = when (Settings.speedTier) {
        2 -> 900L
        0 -> 1800L
        else -> 1200L
    }

    /** 非目标 App 前台轮询间隔（只刷新前台包名，不读界面）：快 5s / 平衡 7.5s / 稳 10s。 */
    private fun checkIntervalIdle(): Long = when (Settings.speedTier) {
        2 -> 5000L
        0 -> 10000L
        else -> 7500L
    }

    // ==================== 核心处理：采集 + 检测广告 ====================

    /**
     * 广告签名挂上后自己追一拍：静态广告不再推 CONTENT_CHANGED，只等事件的话
     * 实测第 2 拍要 1.9 秒后才来（00:50:56.310），确认时延全压在事件密度上。
     */
    private fun scheduleStructNudge() {
        if (structNudges >= STRUCT_NUDGE_MAX) return
        structNudges++
        handler.removeCallbacks(structNudge)
        handler.postDelayed(structNudge, structSampleMs)
    }

    private fun stopStructNudge() {
        structNudges = 0
        handler.removeCallbacks(structNudge)
    }

    private fun processScreen(rootNode: AccessibilityNodeInfo, currentTime: Long) {
        screenCheckCount++
        val pkg = rootNode.packageName?.toString().orEmpty()
        val hongguo = pkg in HONGGUO_PACKAGES

        // 一次全树遍历同时给出分区签名和屏上文案（文案随节点快照回来，不再单独走一棵树）。
        // 广告动画期事件能密到 150ms 一条，按档位采样节奏走：两拍确认约 0.4~0.7 秒，
        // 又能把主线程留给手势派发 —— 之前每秒四五次全树遍历，划动手势被拖到 30 秒后才跑。
        val beat = SystemClock.elapsedRealtime()
        var sig: ZoneSig? = null
        if (beat - structSampleAt >= structSampleMs) {
            structSampleAt = beat
            sig = zoneSignature(rootNode)
            if (sig != null) {
                lastScreenTexts = sig.texts
                extractDramaInfo(sig.texts)
                if (hongguo) probeZones(sig)
            }
        }

        if (!hongguo) {
            // 红果不在前台时采样链停摆，残留的「连续匹配」必须清掉：实测切出 27 秒再切回，
            // 旧 streak 会让回来后的第一帧直接凑成第 2 拍（单帧确认，遇过渡画面就可能误滑）
            structStreak = 0
            structAdSince = 0L
            countdownAdSince = 0L
            countdownSecs = 0
            stopStructNudge()
            // 空树广告的计时也不能跨 App 残留：否则切回来第一帧就可能直接判定并划走
            layerAdSince = 0L
            lastAdFrameAt = 0L
        } else if (sig != null) {
            // 全屏时用户可能旋转屏幕：方向一变两套判据整体换向，旧方向的计数不能算数
            if (lastLandscape != null && lastLandscape != sig.landscape) {
                structStreak = 0
                structAdSince = 0L
                countdownAdSince = 0L
                countdownSecs = 0
                layerAdSince = 0L
                stopStructNudge()
                logDebug("方向切换 ${if (sig.landscape) "横屏" else "竖屏"}，重新计数")
            }
            lastLandscape = sig.landscape
            // 正片基线：只认「播放器正片 UI 在屏」的竖屏帧（标题带集号/作者声明），广告帧不参与。
            // 不用「全屏观看」做条件 —— K40 的播放器控件不进树（实测恒为 false），只靠它永远学不到基线。
            // 横屏帧也不能学：横屏正片本来就只有 44~66 个节点，混进来会把基线拉低，竖屏空树广告就漏了。
            if (!sig.landscape && sig.vis >= 40 &&
                sig.texts.any { EPISODE_RE.containsMatchIn(it) || it.contains("作者声明") }
            ) {
                val firstBase = playerVisBase <= 0f
                playerVisBase = if (firstBase) sig.vis.toFloat()
                    else playerVisBase * 0.8f + sig.vis * 0.2f
                lastPlayerFrameAt = beat
                // 基线是差分判据的分母：学不到（或学歪了）空树型广告就整类漏。
                // 落这一行是为了把「这台机器整夜零记录」分成「没广告」和「差分根本没武装上」两种。
                if (firstBase) {
                    AdLog.note(
                        "红果@$hongguoActivity 正片基线首学=${sig.vis}" +
                            (if (sig.vis < PLAYER_BASE_MIN) " ←低于${PLAYER_BASE_MIN.toInt()}，差分暂不生效" else "")
                    )
                }
            }
        }
        // 倒计时重试循环进行中：红果还在同一广告页，轮询不再重复触发（避免耗尽连续滑动预算）
        if (retryingAd) return
        // 零截图主链路：广告层是原生 View，一次全树遍历就能看出它挂上来了。
        // · 竖屏广告层自带「上滑继续观看短剧」提示条，同时把播放器的「全屏观看」整块盖掉；
        //   这两条都是广告自己写进树里的话，跟机型/分辨率无关：Mi 10 24 帧真广告 24/24 全召回，
        //   正片里残留提示条的 3 帧都带「全屏观看」，靠 playerUi 挡掉。
        // · 横屏读不到文案，只能退回分区几何，再用正片保护词（倍速/热评/作者声明…）兜住误判。
        // · 还要连续挂够：实测切集动画那一拍会短暂凑齐三区
        //（00:29:59 竖屏 上左3 上右12 底条2 可见202，只活了 1 秒），广告则一挂几十秒。
        // 宁可漏跳也不能在信息流里误划 —— 快速上滑曾被广告卡片吃成深链。
        if (sig != null) {
            val byText = sig.verticalAd
            val byLive = !byText && sig.liveAd
            if (sig.adScreen) {
                lastAdFrameAt = beat
                if (structAdSince == 0L) structAdSince = beat
                structStreak++
                val held = beat - structAdSince
                val need = if (byText) hintHoldMs else structHoldMs
                // 倒计时型：「3s后可继续上滑观看短剧」在秒数没走完前滑动会被广告层吃掉
                //（11-03 15:11 实测首划白费，2.9 秒后才靠重试划走）。读到 x 秒就钉住出手时刻。
                if (byText && sig.hintCountdown > 0 && countdownAdSince == 0L) {
                    countdownAdSince = beat
                    countdownSecs = sig.hintCountdown.coerceAtMost(COUNTDOWN_CAP_SEC)
                    logDebug("倒计时提示条 ${sig.hintCountdown}秒，${countdownSecs * 1000L + COUNTDOWN_MARGIN_MS}ms 后再划")
                }
                val gateAt = if (countdownAdSince > 0L)
                    countdownAdSince + countdownSecs * 1000L + COUNTDOWN_MARGIN_MS else 0L
                // 文案里秒数消失（只剩纯提示条）说明倒计时已结束，不必再等到点
                val gateOk = gateAt == 0L || beat >= gateAt || sig.hintCountdown == 0
                logDebug(
                    "结构广告层 第${structStreak}拍 已挂${held}ms " +
                        (when {
                            byText -> "提示条文案 上右${sig.ur} 底条${sig.bb}"
                            byLive -> "直播渲染面"
                            else -> "上左${sig.ul} 上右${sig.ur} 底条${sig.bb}"
                        }) +
                        if (sig.adTag) " 带广告角标" else ""
                )
                if (held >= need && structStreak >= STRUCT_STREAK_NEED && gateOk) {
                    structStreak = 0
                    structAdSince = 0L
                    countdownAdSince = 0L
                    countdownSecs = 0
                    stopStructNudge()
                    onAdDetected(
                        when {
                            byText -> "上滑继续看短剧"
                            byLive -> "直播广告"
                            else -> "结构:广告层"
                        },
                        currentTime,
                        sig,
                        held
                    )
                    return
                }
                scheduleStructNudge()
            } else {
                structStreak = 0
                structAdSince = 0L
                countdownAdSince = 0L
                countdownSecs = 0
                stopStructNudge()
                // 广告层差分：正片基线 30 秒内学过、当前可见节点掉到基线 70% 以下、
                // 且屏上再没有任何正片证据（集号 / 作者声明 / 全屏观看 / 保护词）。
                // 取代原来的「必须零文案」：Mi 10 实测一条半空树创意只剩 51 个节点 + 一句「立即领取」，
                // 挂 3 分 30 秒、44 拍全判 0 —— 旧条件被那一个词挡死，而这种"画面全画在 canvas 里、
                // 只留一枚按钮"的形态是广告创意里最常见的一种。节点差分量本身机型无关。
                // 只认竖屏：横屏全屏播放器本来就只有 44~66 个节点，与广告同形，没有差分量。
                val layerAd = !sig.landscape && playerVisBase >= PLAYER_BASE_MIN &&
                    sig.vis >= 8 && sig.vis <= LAYER_RATIO * playerVisBase &&
                    !sig.playerUi && !sig.playGuard &&
                    sig.texts.none {
                        EPISODE_RE.containsMatchIn(it) || it.contains("作者声明") || it.contains("全屏观看")
                    } &&
                    lastPlayerFrameAt > 0 && beat - lastPlayerFrameAt <= PLAYER_WINDOW_MS
                if (layerAd) {
                    lastAdFrameAt = beat
                    if (layerAdSince == 0L) {
                        layerAdSince = beat
                        logDebug(
                            "广告层差分 可见=${sig.vis} 基线=${playerVisBase.toInt()} " +
                                "剩${sig.texts.size}条文案[${sig.texts.take(3).joinToString(" ")}]"
                        )
                    }
                    // 广告挂多久都要能撑住：把「玩家窗口」一直续到最后一拍，
                    // 否则长广告过了 30 秒窗口就自己失效了
                    lastPlayerFrameAt = beat
                    if (beat - layerAdSince >= layerHoldMs) {
                        val held = beat - layerAdSince
                        layerAdSince = 0L
                        onAdDetected("广告层差分", currentTime, sig, held)
                        return
                    }
                } else if (layerAdSince != 0L) {
                    layerAdSince = 0L
                }
                // 候选未出手：可见节点确实掉到基线 70% 以下、却被某道闸挡住的画面。只记不动手 ——
                // 这是没人挂 adb 现场盯、也能发现「新形态漏跳」的通道之一。
                // 连挂 1.5 秒以上才落一行（切集动画与加载中的瞬时掉节点不值得记），同一种挡法 30 秒内只记一次。
                val blocked = if (layerAd) null else layerBlockedReason(sig, beat)
                if (blocked == null) {
                    layerCandSince = 0L
                } else {
                    if (layerCandSince == 0L) layerCandSince = beat
                    if (beat - layerCandSince >= CAND_HOLD_MS && beat - layerCandLogAt >= CAND_LOG_GAP_MS) {
                        layerCandLogAt = beat
                        AdLog.note("红果@$hongguoActivity 候选未出手 挡住=$blocked" + sigFeature(sig, beat - layerCandSince))
                    }
                }
                // 静默样本：这一帧三条判据一条都没擦边，还是每 10 分钟留一行树形快照。
                // 「完全不在判据射程里的新形态广告」没有别的通道能事后发现 —— 台账整晚干净
                // 和台账整晚没记录，只有靠这种采样行才分得开。
                if (beat - sampleLogAt >= SAMPLE_GAP_MS) {
                    sampleLogAt = beat
                    AdLog.note("红果@$hongguoActivity 静默样本" + sigFeature(sig, 0))
                }
            }
        }
        val detected = detectAdFor(pkg, sig?.texts ?: lastScreenTexts)
        if (detected) {
            lastAdFrameAt = beat
            onAdDetected(lastAdKeyword, currentTime, sig, 0)
        } else {
            // 当前不是广告，检查上一次滑动是否成功
            if (wasAdBeforeSwipe && (currentTime - lastSwipeTime) > SWIPE_DELAY_MS) {
                // 之前是广告，现在不是了 → 滑动成功
                // （只在第一次检测到非广告时计数，避免重复）
                wasAdBeforeSwipe = false
            }
        }

        // 通知由 logDebug 节流刷新，这里不再按拍数重复重建
    }

    /** 命中广告后的统一动作：计数、记日志、延迟上滑、再复核。 */
    private fun onAdDetected(keyword: String, currentTime: Long, sig: ZoneSig?, heldMs: Long) {
        // 滑动节流 + 连续预算统一在这里拦截（事件驱动下入口不再前置，防止同一广告反复滑动）
        if (!canSwipeNow(currentTime)) {
            logDebug("广告命中但滑动被节流拦截")
            return
        }
        lastAdKeyword = keyword
        Counters.inc(SkipStat.HG_DETECT)
        successCounted = false
        lastAdDetectedTime = currentTime
        lastAdTime = formatTime(currentTime)
        consecutiveSwipes++
        wasAdBeforeSwipe = true

        logDebug("广告命中:[$keyword] 剧:$dramaTitle 集:$currentEpisode")
        adHitAt = SystemClock.elapsedRealtime()
        AdLog.start(adFeatureLine(keyword, sig, heldMs))

        lastSwipeTime = currentTime
        Counters.inc(SkipStat.HG_SKIP)
        sendStatsBroadcast()
        // 同一条广告只保留最后一次排队：实测一次横屏直播广告连命中 3 次，
        // 三条排队手势在 98 秒后一起爆发，把正片连划了三下。
        handler.removeCallbacks(pendingSwipe)
        swipeQueuedAt = SystemClock.elapsedRealtime()
        handler.postDelayed(pendingSwipe, adWaitMs())
    }

    /**
     * 台账一行的特征段：命中时刻那一帧在树上的全部读数。
     * 分区/可见数/基线用来判形态，文案与 id 用来事后认出新形态；全是相对量，两台手机的行能直接对比。
     */
    private fun sigFeature(sig: ZoneSig?, heldMs: Long): String {
        if (sig == null) return " 无结构签名（退回文案判定）"
        val flags = buildString {
            if (sig.hintText) append(" 提示条")
            if (sig.hintCountdown > 0) append(" 倒计时${sig.hintCountdown}s")
            if (sig.liveRender) append(" 直播面")
            if (sig.adTag) append(" 角标")
            if (sig.playerUi) append(" 播放器控件")
        }
        return " ${if (sig.landscape) "横屏" else "竖屏"} 分区${sig.ul}/${sig.ur}/${sig.bb}" +
            " 可见${sig.vis}" + (if (playerVisBase > 0f) "/基线${playerVisBase.toInt()}" else "") +
            flags + (if (heldMs > 0) " 挂${heldMs}ms" else "") +
            " 文案[${sig.texts.take(3).joinToString(" ")}] id[${sig.ids.take(3).joinToString(" ")}]"
    }

    private fun adFeatureLine(keyword: String, sig: ZoneSig?, heldMs: Long): String =
        "红果@$hongguoActivity 判据=$keyword" + sigFeature(sig, heldMs)

    /**
     * 记红果当前的界面名，同一会话里每个新界面落一行。
     * 判据按包名收口、不看界面，所以「在商城/听书页上划了一下」这类事故只有靠这份名片才查得出来；
     * 反过来，整夜台账里只有 ShortSeriesActivity 也说明广告全在播放器内，不用扩页面白名单。
     */
    private fun noteHongguoActivity(cls: String) {
        if (!cls.endsWith("Activity")) return
        val simple = cls.substringAfterLast('.')
        hongguoActivity = simple
        if (!seenHongguoActivities.add(simple)) return
        AdLog.note(
            "红果 界面 $simple" +
                (if (simple in PLAYER_ACTIVITIES) "" else " ←不是播放器界面，这里的广告判据本不该出手")
        )
    }

    /**
     * 这一帧算不算「像广告但被挡下」，返回挡住它的那道闸；不算候选就返回 null。
     * 顺序跟 layerAd 一致，只有差分量成立（节点掉到基线 70% 以下）才继续问下去。
     */
    private fun layerBlockedReason(sig: ZoneSig, beat: Long): String? {
        // 横屏全屏播放器本来就只有 44~66 个节点，与广告同形，没有差分量 —— 不制造噪声
        if (sig.landscape || playerVisBase < PLAYER_BASE_MIN) return null
        val inPlayerWindow = lastPlayerFrameAt > 0 && beat - lastPlayerFrameAt <= PLAYER_WINDOW_MS
        if (sig.vis < 8) return if (inPlayerWindow) "近空树" else null
        if (sig.vis > LAYER_RATIO * playerVisBase) return null
        if (!inPlayerWindow) return "距上次正片>${PLAYER_WINDOW_MS / 1000}s"
        if (sig.playerUi) return "播放器控件在屏"
        if (sig.playGuard) return "保护词"
        sig.texts.firstOrNull {
            EPISODE_RE.containsMatchIn(it) || it.contains("作者声明") || it.contains("全屏观看")
        }?.let { return "正片证据「${it.take(12)}」" }
        return null
    }

    /**
     * 排队到点的手势：先核对「没过期 + 广告还在屏上」再划。
     * 主线程被事件洪压住或进程被系统冻结时，延时会从 0.3 秒涨到几十秒，
     * 这时界面上早就不是刚才那条广告了，宁可漏跳也不能在正片上乱划。
     */
    private val pendingSwipe = Runnable {
        val now = SystemClock.elapsedRealtime()
        val queued = now - swipeQueuedAt
        if (queued > STALE_SWIPE_MS) {
            logDebug("手势排队${queued}ms 已过期，丢弃")
            AdLog.end("手势丢弃：排队${queued}ms 过期，没划")
        } else if (now - lastAdFrameAt > STALE_SWIPE_MS) {
            logDebug("出手前复核：广告已不在界面（${now - lastAdFrameAt}ms 前），丢弃")
            AdLog.end("广告已自己收场，没划")
        } else if (performSwipeUp()) {
            logDebug("已执行滑动 第${consecutiveSwipes}次")
            if (adHitAt > 0) AdLog.mark("出手+${now - adHitAt}ms")
            showUndoBubble(consecutiveSwipes.toLong())
            // 延迟后再检查一次，判断是否成功跳过
            handler.postDelayed({
                checkSwipeResult()
            }, 2000)
        } else {
            logDebug("滑动未执行：红果已不在前台")
            AdLog.end("手势未执行：红果已不在前台")
        }
    }

    /** 出手前的人类反应延时：快档 150~300ms，平衡 200~400ms，稳档 300~550ms。 */
    private fun adWaitMs(): Long = when (Settings.speedTier) {
        2 -> 150L + gestureRandom.nextInt(151)
        0 -> 300L + gestureRandom.nextInt(251)
        else -> 200L + gestureRandom.nextInt(201)
    }

    /** 跳过成功后显示撤回气泡（可关）：跳错了点「撤回」反向下滑回到上一状态。 */
    private fun showUndoBubble(swipeNo: Long) {
        if (!Settings.undoBubbleEnabled) return
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
        val (gesture, desc) = buildSwipeGesture(bounds, upward = false)
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gd: GestureDescription?) { Log.d(TAG, "撤回滑动完成") }
            override fun onCancelled(gd: GestureDescription?) { Log.w(TAG, "撤回滑动取消") }
        }, null)
        logDebug("已执行撤回滑动 $desc")
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
                    val cbAt = SystemClock.elapsedRealtime()
                    darkSampleInFlight = false
                    val buffer = result.hardwareBuffer
                    // 回图可以晚到 2 分钟，这期间 onDestroy 已经把线程池关了，
                    // 往 terminated 的池投递会抛 RejectedExecutionException 直接把服务打挂，
                    // MIUI 随之把无障碍开关关掉 —— 之后一条广告都跳不了。
                    try {
                        shotExecutor.execute {
                            var score = 0f
                            var convMs = 0L
                            var matchMs = 0L
                            try {
                                val t0 = SystemClock.elapsedRealtime()
                                val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                val sw = hw?.copy(Bitmap.Config.ARGB_8888, false)
                                hw?.recycle()
                                val t1 = SystemClock.elapsedRealtime()
                                convMs = t1 - t0
                                if (sw != null) {
                                    score = matchHint(sw)
                                    matchMs = SystemClock.elapsedRealtime() - t1
                                    sw.recycle()
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "截图分析失败: ${e.message}")
                            } finally {
                                buffer.close()
                            }
                            val s = score
                            handler.post {
                                // 取证期每拍都打：回图慢是系统等 SurfaceFlinger，转图/匹配慢才是我们自己的账
                                logDebug("采样链路 回图${cbAt - shotAt}ms 转图${convMs}ms 匹配${matchMs}ms 分数 ${(s * 100).toInt()}%")
                                onResult(s)
                            }
                        }
                    } catch (e: RejectedExecutionException) {
                        buffer.close()
                    }
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
     * 滑动后复核：横屏直接读结构签名（毫秒级、零截图），竖屏没有可用的结构判据才退回画面。
     */
    private fun checkSwipeResult(attempt: Int = 0) {
        // 滑动 + 复核一共延了 3.2 秒，用户可能已经按了电源；息屏时截图既没意义也费电
        if (!screenOn()) {
            logDebug("复核跳过：已息屏")
            AdLog.end("复核跳过：已息屏")
            return
        }
        val rootNode = activeWindowRoot()
        if (rootNode == null) {
            logDebug("复核跳过：取不到活动窗口")
            AdLog.end("复核跳过：取不到活动窗口")
            return
        }
        val packageName = rootNode.packageName?.toString()
        if (packageName == null) {
            logDebug("复核跳过：窗口无包名")
            AdLog.end("复核跳过：窗口无包名")
            return
        }
        if (!TARGET_PACKAGES.contains(packageName)) {
            logDebug("复核跳过：前台是 $packageName")
            AdLog.end("复核跳过：前台变成 $packageName")
            return
        }

        val sig = zoneSignature(rootNode)
        if (sig != null && sig.decidable) {
            // 复核帧把「正片证据」也带上：判据说离开了广告，可如果这一帧就是正片，
            // 到底是广告收场了还是我们把正片划走了，只有这行能事后分清。
            val ep = sig.texts.firstOrNull { EPISODE_RE.containsMatchIn(it) || it.contains("作者声明") }
            handleSwipeCheck(
                sig.adScreen,
                "结构 上左${sig.ul} 上右${sig.ur} 底条${sig.bb} 可见${sig.vis}" +
                    (if (ep != null) " 正片证据「${ep.take(12)}」" else "")
            )
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
                    AdLog.end("复核取样连续失败，交给轮询")
                }
                return@sampleScreenMatch
            }
            handleSwipeCheck(score >= HINT_SCORE, "提示条 ${(score * 100).toInt()}%")
        }
    }

    /** 复核结论落地：还在广告就进倒计时重试，已经离开就记一次成功。 */
    private fun handleSwipeCheck(stillAd: Boolean, reason: String) {
        if (stillAd) {
            // 倒计时锁定型广告：红果开头锁定上滑（x 秒后可划），首滑无效是正常的。
            // 复核失败进入快速重试循环（绕过 3 秒节流），倒计时一结束立即划走。
            if (!retryingAd) {
                Counters.inc(SkipStat.HG_FAIL)   // 一条广告的连续失败只记一次
                retryingAd = true
                lockRetries = 0
                logDebug("滑动可能失败，进入倒计时重试（$reason）")
                AdLog.mark("首滑无效")
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
                AdLog.end("划了${LOCK_RETRY_MAX}次仍没离开广告，交给轮询")
            }
        } else if (!successCounted) {
            // 同一轮里「命中后的复核」和「重试的复核」会同时到达（实测 11ms 内两条），
            // 一条广告只记一次成功，否则成功数会盖过检测数
            successCounted = true
            Counters.inc(SkipStat.HG_SUCCESS)
            Today.record(15_000L)
            retryingAd = false
            lockRetries = 0
            logDebug("滑动成功，已离开广告（$reason）")
            AdLog.end("已离开广告（$reason） 复核+${SystemClock.elapsedRealtime() - adHitAt}ms")
        }
        sendStatsBroadcast()
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

    /**
     * 真手模拟：起点/终点带小偏差、轨迹略弯、时长随机 240~380ms。
     * 横向只留很小分量，避免斜向甩动被广告创意吃成深链。
     */
    private fun buildSwipeGesture(bounds: Rect, upward: Boolean): Pair<GestureDescription, String> {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        fun drift(span: Float) = (gestureRandom.nextFloat() - 0.5f) * span
        val startX = w * (0.5f + drift(0.04f))
        val endX = startX + w * drift(0.02f)
        val startY = if (upward) h * (0.66f + drift(0.04f)) else h * (0.30f + drift(0.04f))
        val endY = if (upward) h * (0.36f + drift(0.04f)) else h * (0.70f + drift(0.04f))
        val midX = (startX + endX) / 2f + w * drift(0.015f)
        val midY = (startY + endY) / 2f
        val duration = 240L + gestureRandom.nextInt(141)
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(midX, midY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        val desc = "x=${startX.toInt()}->${endX.toInt()} y=${startY.toInt()}->${endY.toInt()} 用时${duration}ms"
        return gesture to desc
    }

    /** 手势是全局的，落点取决于执行那一刻的前台 App，所以出手前必须再核对一次。 */
    private fun performSwipeUp(): Boolean {
        if (!screenOn() || Settings.paused) return false
        if (!TARGET_PACKAGES.contains(activeWindowPackage())) return false
        val bounds = currentScreenBounds()
        val (gesture, desc) = buildSwipeGesture(bounds, upward = true)
        Log.d(TAG, "滑动手势 $desc 屏幕 ${bounds.width()}x${bounds.height()}")
        // 滑动可能被广告创意吃成深链：开 5 秒守护，跳到别的 App 立刻 BACK 回来
        splashGuardPkg = ""
        deepLinkGuardUntil = SystemClock.elapsedRealtime() + 5000
        deepLinkBacks = 0
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gd: GestureDescription?) {
                Log.d(TAG, "滑动完成")
                // 300ms 快拖下深链可能在手势结束前就弹出来，先当场查一次，再补两次
                maybeBlockAdDeepLink(activeWindowPackage())
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
    private var splashClosedPending = false
    private var splashDetected = false
    private var splashSkipCounted = false
    private var splashSuccessCounted = false
    private var lastSplashScan = 0L
    private var splashTreeLogs = 0
    private var lastSplashDigest = ""
    private var splashMatchInfo = ""
    // 台账用：窗口开始到第一下点击的时延（-1 = 这条开屏一次都没点）
    private var splashFirstClickMs = -1L
    private var splashFirstClickInfo = ""
    private var lastSplashClickKey = ""

    // 本轮开屏窗口的取证。缺这组数，「有时不好使」永远分不出是四种漏跳里的哪一种：
    // 拿不到树（空转）、画在拿不到的子窗口、纯图无节点、还是按钮确实像但被闸挡掉了。
    private var splashScans = 0
    private var splashAdScans = 0
    // 活动/聚焦窗口里拿不到这个包的根：广告画在我们根本枚举不到的窗口上，只能记一笔
    private var splashEmptyRoots = 0
    private var splashBlankTree = 0
    private var splashReject = ""

    /** 窗口收场：到期、切走、被新窗口顶掉都收口，留一行取证说明这一条开屏走到了哪一步。 */
    private fun closeSplashWindow(why: String) {
        val pkg = splashPkg ?: return
        splashPkg = null
        handler.removeCallbacks(splashTicker)
        val outcome = when {
            splashSuccessCounted -> "已跳掉"
            splashSkipCounted -> "点了没复核到"
            splashDetected -> "认出没点着"
            splashAdScans > 0 -> "见广告面没见过按钮"
            else -> "没见广告"
        }
        logDebug(
            "开屏收场 ${pkg.substringAfterLast('.')} $outcome（$why）" +
                " 扫${splashScans}拍 无根${splashEmptyRoots} 无文案${splashBlankTree}" +
                if (splashReject.isNotEmpty()) " 挡掉「$splashReject」" else ""
        )
        AdLog.splash(
            "开屏 $pkg $outcome（$why） 扫${splashScans}拍 无根${splashEmptyRoots} 无文案${splashBlankTree}" +
                (if (splashFirstClickMs >= 0) " 首点+${splashFirstClickMs}ms「$splashFirstClickInfo」" else "") +
                (if (splashMatchInfo.isNotEmpty() && splashMatchInfo != splashFirstClickInfo)
                    " 末次认到「$splashMatchInfo」" else "") +
                (if (splashReject.isNotEmpty()) " 挡掉「$splashReject」" else "")
        )
    }

    /** 开屏窗口出现后独立轮询若干秒：广告窗口常常不产生无障碍事件。 */
    private val splashTicker = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (splashPkg != null && now > splashUntil) {
                closeSplashWindow("到期")
                return
            }
            if (now > splashUntil || !screenOn() || Settings.paused || splashPkg == null) return
            try {
                scanSplashOnce(now)
            } catch (e: Exception) {
                Log.w(TAG, "开屏扫描异常: ${e.message}")
            }
            handler.postDelayed(this, 250L)
        }
    }

    private fun handleSplashEvent(event: AccessibilityEvent, appSwitched: Boolean) {
        if (!Settings.splashEnabled || Settings.paused) return
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
        // 上一个包的窗口还没收场就切进了新 App：先补一行取证，否则那轮扫描到底卡在哪一步就查不到了
        closeSplashWindow("被新窗口顶掉")
        splashPkg = pkg
        splashWindowStart = now
        splashUntil = now + SPLASH_WINDOW_MS
        splashAdStreak = 0
        splashNodeClicks = 0
        splashClosedPending = false
        splashDetected = false
        splashSkipCounted = false
        splashSuccessCounted = false
        lastSplashClick = 0
        lastSplashScan = 0
        splashTreeLogs = 0
        lastSplashDigest = ""
        lastSplashClickKey = ""
        splashScans = 0
        splashAdScans = 0
        splashEmptyRoots = 0
        splashBlankTree = 0
        splashReject = ""
        splashMatchInfo = ""
        splashFirstClickMs = -1L
        splashFirstClickInfo = ""
        Stats.splashLastPkg = pkg
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
            // 但切 App 的头几拍活动窗口还是上一个 App：实测从红果播放器冷启动小蚕，
            // 新窗口事件后 5ms 活动窗口仍是红果，扫描当场自停，那条开屏广告再没被扫过。
            // 给过渡 800ms 宽限，过了还停在目标 App 上才算真的没切换。
            if (splashNodeClicks == 0 && now - splashWindowStart < 800L) return
            if (active != null && (splashNodeClicks > 0 || TARGET_PACKAGES.contains(active))) {
                // 置空 splashPkg 才能真正停：ticker 的 run() 在本函数返回后还会无条件下一次
                // postDelayed，只 removeCallbacks 会被自己重新挂上（实测每 250ms 刷一条停止日志）
                closeSplashWindow("前台已换成${active.substringAfterLast('.')}")
            }
            return
        }
        // 点击预算用尽后不再扫（省算力），但刚点过、还没复核出结果时必须继续扫，
        // 否则这一条开屏到底有没有被跳掉就永远数不到
        if (splashNodeClicks >= SPLASH_NODE_MAX && !splashClosedPending) return
        if (now - lastSplashClick < SPLASH_CLICK_CD_MS) return
        if (now - lastSplashScan < SPLASH_SCAN_STEP_MS) return
        lastSplashScan = now
        splashScans++

        val roots = splashRoots(pkg)
        if (roots.isEmpty()) {
            splashEmptyRoots++
            return
        }
        // 点过跳过按钮后，若某一拍既找不到跳过按钮、也不像广告面，说明开屏真的被跳掉了；
        // 6 秒内没等到就放弃这一条的复核（预算用尽后扫描会提前 return，不能无限等）
        if (splashClosedPending) {
            val noBudget = now - lastSplashClick > SPLASH_CLOSE_CONFIRM_MS
            val gone = roots.none { findSplashSkipNode(it, 0, 16) != null } &&
                !looksLikeSplashAd(roots, roots.flatMap { sampleList(it, 8) })
            if (noBudget) {
                splashClosedPending = false
            } else if (gone) {
                splashClosedPending = false
                // 一条开屏只记一次成功：同窗口内点两下、复核两次不重复计
                if (!splashSuccessCounted) {
                    splashSuccessCounted = true
                    Counters.inc(SkipStat.SPLASH_SUCCESS)
                    Today.record(5_000L)
                }
                logDebug("开屏已关闭")
            }
        }
        val node = roots.firstNotNullOfOrNull { findSplashSkipNode(it, 0, 16) }
        if (node != null) {
            // 窗口刚出现 0.5 秒内不动手：厂商 ROM 在窗口切换的快照期会把「上一次退出时的界面」
            // 画在新窗口上，此刻扫到的「跳过」可能是早已不存在的广告，点下去就打在新首页上。
            val sinceStart = now - splashWindowStart
            if (sinceStart < SPLASH_FIRST_CLICK_MS) return
            // 同一个键点过一次没反应就不再点：实测连点两下会把用户带进广告落地页
            val key = "${node.viewIdResourceName}|${node.text}|${node.contentDescription}|${node.className}"
            if (key == lastSplashClickKey) return
            if (splashNodeClicks >= SPLASH_NODE_MAX) return
            // 认出可点的跳过控件就算「检测」：一条开屏只算一次，点两下不重复计
            if (!splashDetected) {
                splashDetected = true
                Counters.inc(SkipStat.SPLASH_DETECT)
            }
            if (clickNode(node)) {
                splashNodeClicks++
                if (splashNodeClicks == 1) {
                    splashFirstClickMs = sinceStart
                    splashFirstClickInfo = splashMatchInfo.ifEmpty {
                        node.viewIdResourceName ?: node.className?.toString().orEmpty()
                    }
                    Log.i(TAG, "开屏首击时延 +${sinceStart}ms")
                }
                lastSplashClickKey = key
                lastSplashClick = SystemClock.elapsedRealtime()
                splashClosedPending = true
                // 点完开屏「跳过」同样开守护：这一跳若把前台带进别的 App（实测触发了
                // 「晓晓优选 想要打开 美团」），说明点落在广告热区上，立刻 BACK 回去。
                // 窗口只开 1.5 秒：实测深链的「想要打开 美团」在点完 260ms 就冒出来，
                // 而用户自己切 App 是常态——开太久会把正常切换误判成深链（5 秒时实测误伤两次 BACK）。
                splashGuardPkg = pkg
                deepLinkGuardUntil = SystemClock.elapsedRealtime() + 1500
                deepLinkBacks = 0
                if (!splashSkipCounted) {
                    splashSkipCounted = true
                    Counters.inc(SkipStat.SPLASH_SKIP)
                }
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
        // 有根、有节点，但一个字都采不到：这就是画在 canvas/Lynx 里的纯图开屏，
        // 树里没有可点的跳过件，按底线只能放弃（记下来才知道这种形态还有多少）
        if (samples.isEmpty()) splashBlankTree++
        val adSurface = looksLikeSplashAd(roots, samples)
        splashAdStreak = if (adSurface) splashAdStreak + 1 else 0
        // 广告特征连续两拍才认，避免普通页面里一两个“广告”字样就续期
        val confirmedAd = splashAdStreak >= 2
        if (confirmedAd) {
            splashAdScans++
            if (!splashDetected) {
                splashDetected = true
                Counters.inc(SkipStat.SPLASH_DETECT)
            }
            splashUntil = maxOf(splashUntil, now + SPLASH_RENEW_MS)
        }
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
                // 同一个 App 的后台窗口也留在窗口并集里：实测在 B站 搜索页点到了
                // 退到后台的视频窗口残留的「跳过」倒计时。看不见的窗口不该点。
                if (!w.isActive && !w.isFocused) continue
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
        // 开了 flagIncludeNotImportantViews 之后，看不见的节点也会进树。
        // 不可见的「跳过」可能是上一屏广告留下的残骸，点它等于朝空气落指、
        // 甚至点到已经被它盖住的首页按钮上，一律不认。
        val visible = node.isVisibleToUser
        // 文案/id 命中只在可见节点上算：开了 flagIncludeNotImportantViews 之后树里大量不可见节点，
        // 提前短路是白省一趟正则（这一层每 300ms 全树走一遍，主线程还要腾出来给红果划广告）
        val labelHit = visible && (isSkipLabel(text) || isSkipLabel(desc))
        val idHit = visible && isSkipViewId(id)
        if (visible && isCornerCountdown(node)) {
            splashMatchInfo = "倒计时数字「${node.text ?: node.contentDescription}」"
            return node // 倒计时数字只认它自己可点，往上爬会点到广告或角标容器
        }
        if (labelHit && node.childCount == 0 && isButtonSized(node)) {
            splashMatchInfo = "text=$text desc=$desc id=$id"
            return node // 点「跳过」这两个字本身，不去点可点的祖先（实测祖先常是广告容器）
        }
        if (idHit && isButtonSized(node)) {
            // 按 id 命中只许点「按钮自己」或「同样只有按钮尺寸的可点祖先」。实测晓晓优选
            // (TopOn 聚合)的 tobid_splash_skip_text 往上爬到的可点 RelativeLayout 是整块广告热区，
            // 点它直接触发深链，被 MIUI 拦成「晓晓优选 想要打开 美团」。
            val target = if (node.isClickable && node.isVisibleToUser) node else clickableAncestorAsButton(node)
            if (target != null) {
                val box = Rect()
                target.getBoundsInScreen(box)
                splashMatchInfo = "id=$id 点击区${box.width()}x${box.height()}"
                return target
            }
        }
        // 只记证、不改判定：像跳过键却被下面三道闸挡掉的，留一条给收场日志。
        // 漏跳到底是「尺寸档太紧」「按钮带子节点」还是「整条不可点」，决定下一步该放宽哪一条
        if (splashReject.isEmpty() && (labelHit || idHit)) {
            val box = Rect()
            node.getBoundsInScreen(box)
            val name = text.ifBlank { desc }.ifBlank { id.substringAfterLast('/') }.take(12)
            val why = when {
                labelHit && node.childCount > 0 -> "带${node.childCount}个子节点"
                !isButtonSized(node) -> "${box.width()}x${box.height()}超按钮档"
                else -> "自己与祖先都不可点"
            }
            splashReject = "$name·$why"
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
        val short = id.substringAfterLast('/')
        // 摇一摇触发件、信息流广告本体：点下去就是进广告，社区在点的是它们各自的关闭件。
        // 带 setting 的是「关闭广告推荐」这类开关（社区表里的 settings_close_advertisement），
        // 点它是替用户改了别的 App 的隐私设置，比误点一下广告更不该我们动手。
        // 这一条放在所有表前面，后面再扩表也不会把这类入口放进来
        if (short.contains("sensor") || short.contains("shake") || short.contains("feedad") ||
            short.contains("setting")
        ) return false
        // 社区验证过的具名跳过件（高置信）
        if (SPLASH_SKIP_IDS.any { short.contains(it) }) return true
        if (AD_SKIP_ID.containsMatchIn(short)) return true
        // 倒计时件：短名同时含 count 与 down（download 已在上面排除，挡住「点击下载」假跳过）
        if (short.contains("count") && short.contains("down")) return true
        // 泛匹配兜新 SDK 的命名；splash_ad / ad_container 这类广告容器名不在表里，也绝不能当跳过键
        return short.contains("skip_ad") || short.contains("ad_skip") || short.contains("skip_view") ||
                short.contains("close_ad") || short.contains("skip")
    }

    /**
     * 只有按钮尺寸的节点才允许点。上限按 dp 算，不按绝对像素：跳过键是 dp 尺寸，
     * 屏密度越高它的像素越大，写死 300×200 会在高分屏上把真按钮判成「太大不像按钮」而漏跳
     * （参考机 Mi 10 密度 2.75，110dp×73dp 换过来就是原先的 300×200，两台在用机器上等价）。
     * 再叠一层屏幕占比，把「全屏热区冒充按钮」这条路从几何上切断。
     */
    private fun isButtonSized(node: AccessibilityNodeInfo): Boolean {
        val b = Rect()
        node.getBoundsInScreen(b)
        val screen = currentScreenBounds()
        val d = resources.displayMetrics.density
        return b.width() in 1..(110f * d).toInt() && b.height() in 1..(73f * d).toInt() &&
            b.width() < screen.width() * 0.28f && b.height() < screen.height() * 0.09f
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

    /**
     * 只在「祖先自己也还是按钮尺寸」时才允许点祖先。往上爬到一个铺满屏幕的可点容器，
     * 意味着点的是整块广告热区（会拉起落地页/别的 App），这种情况一律不点、宁可漏跳。
     */
    private fun clickableAncestorAsButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur = node.parent
        var guard = 0
        while (cur != null && guard < 3) {
            if (cur.isClickable && cur.isVisibleToUser && isButtonSized(cur)) return cur
            cur = cur.parent
            guard++
        }
        return null
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        if (Settings.paused) return false
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
        if (instance === this) instance = null
        // 一条清干净：逐个 removeCallbacks 早晚会漏掉新加的 runnable。
        // 漏掉的 splashTicker 会在服务销毁后继续 250ms 自投，拿着已失效的窗口树
        // 找「跳过」并按坐标落指（进程不会随服务销毁退出，这条尾巴能挂到下次冷启）
        handler.removeCallbacksAndMessages(null)
        shotExecutor.shutdownNow()
        biliBubble.hide()
        undoBubble.hide()
        Log.d(TAG, "服务已销毁")
    }
}

/**
 * 正片反向闸门（社区 ad-auto 同款）：命中任意一词就认为播放器正片 UI 还在，整屏不算广告。
 * K40 1496 帧正片覆盖 99.4%，而 Mi 10 三条真竖屏广告帧全部不命中。
 * 「选集/已完结」故意不进闸门 —— 00:51:40 那条真广告帧里这行选集条还挂在屏上。
 *
 * 解锁卡三条来自 T18 静态反编译（res/r/cyn.xml）：那一页上滑 = 进全屏广告，
 * 是红果自己 string/g5t「向上滑动进入全屏广告」说的，命中就绝不出手。
 */
private val PLAY_GUARD_RE = Regex(
    "倍速|热评|分享|收藏|评论|下一集|全集|作者声明|跟播|点赞|弹幕|展开|暂停|剧备字|第\\d+集" +
        "|试看结束|看广告支持正版|暂不解锁|开会员解锁|向上滑动进入全屏广告"
)

/** 正片标题里的集号：两台机器 1496 帧正片全带，广告（原生/空树）都不带 */
private val EPISODE_RE = Regex("第\\d+集")

/** 穿山甲直播 SDK 渲染面的资源名后缀（完整形如 com.phoenix.read:id/ttlive_player_render_view） */
private const val LIVE_RENDER_ID_SUFFIX = "/ttlive_player_render_view"

/**
 * 竖屏广告提示条的文案族。T18 从红果 7.4.3.32 资源表抠出四种：
 * 上滑继续观看短剧(bxk)、继续上滑观看短剧(fhg)、上滑继续看短剧(cex)、
 * %1$ds后可继续上滑观看短剧(ffz)。倒计时那型不含「上滑继续」，
 * 11-03 实测 15:01:51 它先挂 1.8 秒才轮到 pill，只认「上滑继续」会晚一拍、fhg 型则整条漏跳。
 * 放宽只认「继续上滑 + 短剧」这一种搭配，不碰泛化文案（离线 358 帧里 2 帧命中全是广告帧）。
 */
private val HINT_TEXT_RE = Regex("上滑继续|继续上滑(观看|看)?短剧")

/** 提示条里的剩余秒数：「3s后」「3秒后」「3 秒 后」都是同一型 */
private val COUNTDOWN_RE = Regex("(\\d{1,2})\\s*[秒sS]\\s*后")

/**
 * 无障碍树分区签名：四个几何区里「可见」的节点数 + 两条关键文案，全部按屏幕宽高归一化，与分辨率无关。
 * 横屏剧间广告没有可读文案，只能靠几何（左上返回 + 右上「上滑」胶囊 + 底部通栏条三区齐）；
 * 竖屏剧间广告自带「上滑继续观看短剧」提示条，且会把播放器的「全屏观看」整块盖掉。
 */
private data class ZoneSig(
    val ul: Int,
    val ur: Int,
    val bm: Int,
    val bb: Int,
    val vis: Int,
    val adTag: Boolean,
    val landscape: Boolean,
    val playerUi: Boolean,
    val hintText: Boolean,
    val hintCountdown: Int,
    val playGuard: Boolean,
    val liveRender: Boolean,
    val ids: List<String>,
    val texts: List<String>
) {
    /** 横屏广告层的分区几何：左上返回 + 右上「上滑」胶囊 + 底部通栏条三区齐 */
    val adGeo: Boolean get() = ul >= 1 && ur >= 1 && bb >= 1 && !playGuard

    /**
     * 横屏还要带上「广告」合规角标（右上 pill 旁的「广告 ⌄」）才认：
     * 真横屏广告实测 上左2 上右5 底条1 角标=true；横屏播放器控件也能凑齐三区，
     * 只是靠 playGuard（标题里的「第N集」）先挡了一道，角标是第二道。
     */
    val adLike: Boolean get() = adGeo && adTag

    /**
     * 竖屏广告层：红果自己写进树的「上滑继续观看短剧」这一句就是充分证据，跟屏幕尺寸、
     * dpi 无关（Mi 10 竖屏 24 帧真广告实测 24/24 全召回，而正片帧里这句根本不出现）。
     * 原先还要求「右上≥3 且 底条≥3」的绝对节点个数 —— 那是在 Mi 10 上调出来的，同一批
     * 真广告帧里有 6 帧右上=0 被漏判，换到 K40 正片态就已经是「右上2 底条2」，差 1 就误判。
     * 个数只保留给读不到文案的横屏降级路径用。
     */
    val verticalAd: Boolean get() = !landscape && hintText && !playerUi

    /**
     * 直播广告（Mi 10 11-07 14:04 实锤）：整屏是直播画面，「点击进入直播间」「直播中」
     * 「上滑继续看短剧」全画在视频层里，树里只剩一句「立即领取」，提示条/几何/空树三条判据
     * 同时失效，广告挂了 4 分钟一次没划。唯一进树的强证据是穿山甲直播 SDK 的渲染面。
     * 只上滑、绝不点：这一屏点中任何一块都是进直播间。
     *
     * 横屏同样要认（Mi 10 11-07 23:18 实锤）：横屏直播广告实测 `上左1 上右3 底条0 可见39 直播面=true`，
     * 横屏几何判据要「底条≥1」而这一屏根本没有底条，挂了 70 秒一次没划。原先加 `!landscape` 是
     * 怕横屏误判，但横屏正片实测直播面恒为 false（23:07/23:09/23:19 多帧），漏跳的代价落在用户身上。
     */
    val liveAd: Boolean get() = liveRender && !playerUi && !playGuard

    val adScreen: Boolean get() = (landscape && adLike) || verticalAd || liveAd

    /** 结构签名能否独立判定这一屏（判不了才允许退回截图复核） */
    val decidable: Boolean get() = landscape || playerUi || hintText || liveAd
}

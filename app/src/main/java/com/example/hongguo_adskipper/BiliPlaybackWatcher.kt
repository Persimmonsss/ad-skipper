package com.example.hongguo_adskipper

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import java.util.concurrent.Executors

/**
 * 监听 B站 的媒体会话：拿标题定位 BV 号，并暴露会话 seek 能力。
 * 详情页内联播放器不更新 PlaybackState，进度与跳转时机交给 BiliUiSkip 的无障碍路径。
 */
class BiliPlaybackWatcher : NotificationListenerService() {

    companion object {
        const val BILI_PKG = "tv.danmaku.bili"
        private val BV_PATTERN = Regex("""BV[0-9A-Za-z]{10}""")

        @Volatile var connected = false
            private set

        @Volatile private var instance: BiliPlaybackWatcher? = null

        /** 会话支持 SEEK_TO 时优先用它跳转：精确、不需要控件可见。 */
        fun seekBili(ms: Long): Boolean {
            val c = instance?.controller ?: return false
            return try {
                c.transportControls.seekTo(ms)
                true
            } catch (e: Exception) {
                false
            }
        }

        /** 会话上报的进度（平时陈旧，seek 后会刷新一次，可用于校验）。 */
        fun biliPositionMs(): Long? = instance?.controller?.playbackState?.position
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private var controller: MediaController? = null
    private var currentTitle: String? = null
    private var resolving = false
    private var pendingResolve: Pair<String, String?>? = null
    private var resolveFailures = 0

    private val listener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        main.post { attach(list) }
    }

    private val sessionCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            main.post { handleMetadata(metadata) }
        }

        override fun onSessionDestroyed() {
            main.post {
                Stats.biliTitle = "-"
                Stats.biliSegList = emptyList()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        instance = this
    }

    override fun onListenerConnected() {
        connected = true
        Stats.log("B站监听已连接")
        val mgr = getSystemService(MediaSessionManager::class.java) ?: return
        val comp = ComponentName(this, BiliPlaybackWatcher::class.java)
        runCatching { mgr.addOnActiveSessionsChangedListener(listener, comp) }
            .onFailure { Stats.log("会话监听失败：${it.message}") }
        runCatching { attach(mgr.getActiveSessions(comp)) }
            .onFailure { Stats.log("读取会话失败：${it.message}") }
    }

    override fun onListenerDisconnected() {
        connected = false
        Stats.log("B站监听已断开")
        releaseController()
    }

    override fun onDestroy() {
        releaseController()
        io.shutdownNow()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun attach(list: List<MediaController>?) {
        val target = list?.firstOrNull { it.packageName == BILI_PKG }
        if (target == null) {
            if (controller != null) releaseController()
            return
        }
        if (target.sessionToken == controller?.sessionToken) return
        releaseController()
        controller = target
        runCatching { target.registerCallback(sessionCallback) }
        handleMetadata(target.metadata)
    }

    private fun releaseController() {
        controller?.let { c -> runCatching { c.unregisterCallback(sessionCallback) } }
        controller = null
    }

    /** 合集/自动连播会给标题追加“自动连播(131/526)”，不清理会匹配不到 BV。 */
    private fun cleanTitle(raw: String): String =
        raw.replace(Regex("""\s*自动连播.*$"""), "").trim()

    private fun handleMetadata(metadata: MediaMetadata?) {
        if (metadata == null) return
        val title = cleanTitle(
            metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: metadata.description?.title?.toString()
                ?: return
        )
        if (title.isEmpty()) return
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        if (duration > 0) Stats.biliDurationMs = duration
        Stats.biliTitle = title
        if (title == currentTitle) return

        currentTitle = title
        resolveFailures = 0
        Stats.biliBvid = null
        Stats.biliSegments = 0
        Stats.biliSegList = emptyList()

        if (!Settings.biliEnabled) {
            Stats.log("B站跳过已关闭")
            return
        }
        // 会话元数据里有时直接带 BV（mediaId / mediaUri），能省掉一次标题搜索
        val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty()
        val mediaUri = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI).orEmpty()
        val hint = BV_PATTERN.find(mediaId)?.value ?: BV_PATTERN.find(mediaUri)?.value
        if (!hint.isNullOrEmpty()) Stats.log("元数据带 BV：$hint")
        resolve(title, hint)
    }

    private fun resolve(title: String, hintedBvid: String? = null) {
        if (resolving) {
            pendingResolve = title to hintedBvid
            return
        }
        resolving = true
        Stats.log("解析视频：${title.take(18)}…")
        io.execute {
            val bvid = hintedBvid ?: BiliSegmentRepo.resolveByTitle(title)
            val segs = if (bvid != null) BiliSegmentRepo.fetchSegments(bvid) else emptyList()
            main.post {
                resolving = false
                // 解析期间切了视频就丢弃旧结果，否则会在新视频上用旧广告段跳转
                if (title != currentTitle) {
                    takePendingResolve()?.let { resolve(it.first, it.second) }
                    return@post
                }
                Stats.biliBvid = bvid
                Stats.biliSegments = segs.size
                Stats.biliSegList = segs.filter { Settings.biliEnabled }
                if (bvid == null) {
                    Stats.log("未能匹配 BV 号")
                    // 搜索接口偶发返回不相干结果，隔几秒重试，避免整场视频失去保护
                    if (Settings.biliEnabled && resolveFailures < 2) {
                        resolveFailures++
                        Stats.log("8 秒后重试解析 $resolveFailures/2")
                        main.postDelayed({
                            if (title == currentTitle && Settings.biliEnabled && Stats.biliBvid == null) {
                                resolve(title, hintedBvid)
                            }
                        }, 8000L)
                    }
                } else {
                    resolveFailures = 0
                    Stats.log("匹配 $bvid，${segs.size} 段广告")
                }
                takePendingResolve()?.takeIf { it.first != title }
                    ?.let { resolve(it.first, it.second) }
            }
        }
    }

    private fun takePendingResolve(): Pair<String, String?>? =
        pendingResolve.also { pendingResolve = null }
}

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

    private fun handleMetadata(metadata: MediaMetadata?) {
        if (metadata == null) return
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.description?.title?.toString()
            ?: return
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        if (duration > 0) Stats.biliDurationMs = duration
        Stats.biliTitle = title
        if (title == currentTitle) return

        currentTitle = title
        Stats.biliBvid = null
        Stats.biliSegments = 0
        Stats.biliSegList = emptyList()

        if (!Settings.biliEnabled) {
            Stats.log("B站跳过已关闭")
            return
        }
        resolve(title)
    }

    private fun resolve(title: String) {
        if (resolving) return
        resolving = true
        Stats.log("解析视频：${title.take(18)}…")
        io.execute {
            val bvid = BiliSegmentRepo.resolveByTitle(title)
            val segs = if (bvid != null) BiliSegmentRepo.fetchSegments(bvid) else emptyList()
            main.post {
                resolving = false
                Stats.biliBvid = bvid
                Stats.biliSegments = segs.size
                Stats.biliSegList = segs.filter { Settings.biliEnabled }
                if (bvid == null) Stats.log("未能匹配 BV 号") else Stats.log("匹配 $bvid，${segs.size} 段广告")
            }
        }
    }
}

package com.example.hongguo_adskipper

import android.app.Notification
import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import java.util.concurrent.Executors

/**
 * 监听 B站 的媒体会话：拿标题/进度，命中社区库 sponsor 片段后 seek 跳过。
 * 只在播放时以 500ms 节拍取值，暂停/无会话时完全静止（按需唤醒）。
 */
class BiliPlaybackWatcher : NotificationListenerService() {

    companion object {
        const val BILI_PKG = "tv.danmaku.bili"
        private const val TICK_MS = 500L
        private const val LEAD_MS = 3000L
        private const val SEEK_GUARD_MS = 4000L

        @Volatile var connected = false
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val bubble by lazy { SkipBubble(applicationContext) }

    private var controller: MediaController? = null
    private var currentTitle: String? = null
    private var resolving = false
    private var segments: List<AdSegment> = emptyList()
    private var skipped = mutableSetOf<String>()
    private var lastSeekAt = 0L
    private var seekWatchSeg: AdSegment? = null
    private var seekTargetMs = 0L

    private val listener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        main.post { attach(list) }
    }

    private val sessionCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            main.post { handleMetadata(metadata) }
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            main.post { if (state?.state != PlaybackState.STATE_PLAYING) bubble.hide(); startTickerIfNeeded() }
        }

        override fun onSessionDestroyed() {
            main.post {
                bubble.hide()
                segments = emptyList()
                Stats.biliTitle = "-"
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val c = controller
            if (c == null) return
            val st = c.playbackState
            if (st == null || st.state != PlaybackState.STATE_PLAYING) {
                bubble.hide()
                main.postDelayed(this, TICK_MS)
                return
            }
            val pos = st.position + ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
            onPosition(pos.coerceAtLeast(0))
            main.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
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
        startTickerIfNeeded()
    }

    private fun releaseController() {
        main.removeCallbacks(ticker)
        controller?.let { c -> runCatching { c.unregisterCallback(sessionCallback) } }
        controller = null
        bubble.hide()
    }

    private fun startTickerIfNeeded() {
        if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING) {
            main.removeCallbacks(ticker)
            main.post(ticker)
        } else {
            main.removeCallbacks(ticker)
        }
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
        segments = emptyList()
        skipped = mutableSetOf()
        Stats.biliBvid = null
        Stats.biliSegments = 0
        bubble.hide()

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
                segments = segs.filter { Settings.biliEnabled }
                skipped = mutableSetOf()
                if (bvid == null) Stats.log("未能匹配 BV 号") else Stats.log("匹配 $bvid，${segs.size} 段广告")
            }
        }
    }

    private fun onPosition(pos: Long) {
        Stats.biliPositionMs = pos
        if (!Settings.biliEnabled) return
        if (segments.isEmpty()) return

        checkSeekResult(pos)

        val seg = segments.firstOrNull { pos >= it.startMs - LEAD_MS && pos < it.endMs }
        if (seg == null) {
            bubble.hide()
            return
        }
        if (pos < seg.startMs) {
            val secLeft = ((seg.startMs - pos + 999) / 1000).toInt().coerceIn(1, 3)
            if (Settings.bubbleEnabled) {
                bubble.show("$secLeft 秒后跳过广告")
            } else {
                bubble.hide()
            }
        } else if (!skipped.contains(seg.uuid) && SystemClock.elapsedRealtime() - lastSeekAt > SEEK_GUARD_MS) {
            doSeek(seg)
        }
    }

    private fun doSeek(seg: AdSegment) {
        val c = controller ?: return
        val target = seg.endMs + 150
        lastSeekAt = SystemClock.elapsedRealtime()
        skipped.add(seg.uuid)
        bubble.hide()
        runCatching { c.transportControls.seekTo(target) }
            .onFailure {
                Stats.log("seek 调用失败：${it.message}")
                return
            }
        seekWatchSeg = seg
        seekTargetMs = target
        Stats.biliSkipCount++
        Stats.biliLastSkip = "${formatMs(seg.startMs)} → ${formatMs(seg.endMs)}"
        Stats.log("跳过广告段 ${formatMs(seg.startMs)}→${formatMs(seg.endMs)}")
    }

    private fun checkSeekResult(pos: Long) {
        val seg = seekWatchSeg ?: return
        if (pos >= seekTargetMs - 3000) {
            seekWatchSeg = null
            Stats.log("seek 生效，当前 ${formatMs(pos)}")
        } else if (SystemClock.elapsedRealtime() - lastSeekAt > 2500) {
            seekWatchSeg = null
            Stats.lastError = "seek 未生效"
            Stats.log("seek 未生效（B站忽略），位置 ${formatMs(pos)}")
            skipped.remove(seg.uuid)
        }
    }

    private fun formatMs(ms: Long): String {
        val s = ms / 1000
        return "%02d:%02d".format(s / 60, s % 60)
    }
}

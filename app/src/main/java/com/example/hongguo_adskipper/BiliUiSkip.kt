package com.example.hongguo_adskipper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * B站 播放器无障碍路径：读界面进度文本 + 拖进度条。
 * 详情页内联播放器不更新 MediaSession，只能靠这条路径拿真实进度和跳转。
 * 控件隐藏后无障碍树里没有进度，用最后读数外推；广告前几秒主动唤出控件刷新。
 */
class BiliUiSkip(
    private val service: AccessibilityService,
    private val bubble: SkipBubble
) {
    companion object {
        const val BILI_PKG = "tv.danmaku.bili"
        private const val SCAN_INTERVAL_MS = 300L
        private const val LEAD_MS = 3000L
        private const val REVEAL_LEAD_MS = 6000L
        private const val SEEK_GUARD_MS = 4000L
        private const val FRESH_MS = 2000L
        private const val MAX_SCAN_NODES = 600
        private const val UNDO_MS = 3000L
        private const val UNDO_BACK_MS = 2000L
        private const val THUMB_RADIUS_DP = 8f
        private val PROGRESS = Regex("""(\d{1,2}):(\d{2})(?::(\d{2}))?\s*/\s*(\d{1,2}):(\d{2})(?::(\d{2}))?""")
    }

    private val main = Handler(Looper.getMainLooper())

    private var lastScan = 0L
    private var posMs = -1L
    private var posAt = 0L
    private var durMs = -1L
    private var bar: Rect? = null
    private var lastSeekAt = 0L
    private var pendingSeg: AdSegment? = null
    private var pendingTarget = 0L
    private var pendingBeforePos: Long? = null
    private var lastBvid: String? = null
    private var lastPosLog = 0L
    private var lastStateLog = 0L
    private var lastBarLog = ""
    private var lastBootstrap = 0L
    private var lastPassiveScan = 0L
    private var pausedReanchors = 0

    private var paused: Boolean? = null
    private var undoUntil = 0L
    private var undoTo = 0L
    private var undoUuid: String? = null
    private val attempts = mutableMapOf<String, Int>()
    private val skipped = mutableSetOf<String>()

    /** 由无障碍事件驱动：读一次界面并处理。 */
    fun onEvent(rootProvider: () -> AccessibilityNodeInfo?) {
        val now = SystemClock.elapsedRealtime()
        // 没有广告段或已暂停时只为仪表盘刷新进度，没必要 300ms 扫一次
        val interval = if (Stats.biliSegList.isEmpty() || paused == true) 1000L else SCAN_INTERVAL_MS
        if (now - lastScan < interval) return
        lastScan = now
        resetIfVideoChanged()
        scanInto(rootProvider())
        apply(now)
    }

    /** 由服务定时器驱动：控件隐藏时靠外推决定何时唤出控件。 */
    fun tick() {
        if (!Settings.biliEnabled) {
            bubble.hide()
            return
        }
        val now = SystemClock.elapsedRealtime()
        // 暂停时 B站 也会自动隐藏控件，读数会陈旧；但只有停在广告段附近才值得点一下屏幕
        // 重读，否则用户暂停看评论时会被我们反复唤出控件
        if (paused == true) {
            // 暂停期间必须冻住外推：不然用户停十分钟，est 就永远越过广告段，恢复播放后
            // 这一整场都不会再跳
            posAt = now
            val near = Stats.biliSegList.any {
                !skipped.contains(it.uuid) && posMs in (it.startMs - 90_000)..it.endMs
            }
            if (near && pausedReanchors < 3 && now - lastBootstrap > 8000) {
                // 停在广告前不动时最多戳 3 次，之后交给静默重扫，不和人抢屏幕
                pausedReanchors++
                lastBootstrap = now
                reanchor()
            } else if (now - lastPassiveScan > 5000) {
                // 控件隐藏时详情页几乎没有无障碍事件，没人扫节点的话 paused 会一直停在
                // true，用户恢复播放也救不回来；这里每 5 秒静默重扫一次（不点屏）
                lastPassiveScan = now
                scanInto(service.rootInActiveWindow)
            }
            return
        }
        pausedReanchors = 0
        if (now < undoUntil) return
        resetIfVideoChanged()
        val segs = Stats.biliSegList
        if (segs.isEmpty()) return
        if (posMs < 0) {
            if (now - lastBootstrap > 15_000) {
                lastBootstrap = now
                reanchor()
            }
            return
        }
        val est = posMs + (now - posAt)
        val seg = segs.firstOrNull { est >= it.startMs - REVEAL_LEAD_MS && est < it.endMs }
        if (seg == null) {
            // 暂停/拖动过会让外推值漂移，广告段附近定期重新读一次真实位置
            val near = segs.any {
                !skipped.contains(it.uuid) && est >= it.startMs - 60_000 && est <= it.endMs + 60_000
            }
            if (near && bar == null && now - lastBootstrap > 8000) {
                lastBootstrap = now
                reanchor()
            }
            return
        }
        if (est < seg.startMs && now - lastStateLog > 4000) {
            lastStateLog = now
            Stats.log("临近广告 ${fmt(est)} 控件${if (bar == null) "隐藏" else "可见"}")
        }
        if (est < seg.startMs - LEAD_MS) {
            if (bar == null && now - lastBootstrap > 6000) {
                lastBootstrap = now
                reanchor()
            }
            return
        }
        apply(now)
    }

    /** 唤出控件并主动扫一次，把外推位置纠回真实读数。 */
    private fun reanchor() {
        revealControls()
        main.postDelayed({
            lastScan = 0
            scanInto(service.rootInActiveWindow)
        }, 600)
    }

    private fun resetIfVideoChanged() {
        val bvid = Stats.biliBvid
        if (bvid != lastBvid) {
            lastBvid = bvid
            skipped.clear()
            attempts.clear()
            pendingSeg = null
            bar = null
            undoUuid = null
            undoUntil = 0
            bubble.hide()
        }
    }

    private fun scanInto(root: AccessibilityNodeInfo?) {
        if (root == null) return
        val scan = scanPlayer(root)
        Stats.biliUiActiveAt = SystemClock.elapsedRealtime()
        if (scan.pos >= 0) {
            posMs = scan.pos
            posAt = SystemClock.elapsedRealtime()
            if (scan.dur > 0) durMs = scan.dur
            Stats.biliPositionMs = posMs
            if (durMs > 0) Stats.biliDurationMs = durMs
            val b = scan.bar
            if (b != null && lastBarLog != "${b.left},${b.top},${b.width()}x${b.height()}") {
                lastBarLog = "${b.left},${b.top},${b.width()}x${b.height()}"
                Stats.log("进度条 $lastBarLog")
            }
            if (Stats.biliSegList.isNotEmpty() && posAt - lastPosLog > 10_000) {
                lastPosLog = posAt
                Stats.log("进度 ${fmt(posMs)} / ${fmt(durMs)}")
            }
        }
        if (scan.bar != null) {
            bar = scan.bar
            if (scan.paused != null && paused != null && scan.paused != paused) {
                Stats.log(if (scan.paused) "检测到暂停，暂不跳过" else "恢复播放")
            }
            paused = scan.paused
        } else {
            bar = null
            paused = null
        }
    }

    private fun apply(now: Long) {
        if (!Settings.biliEnabled) {
            bubble.hide()
            return
        }
        if (paused == true) {
            bubble.hide()
            return
        }
        if (pendingSeg != null) return
        if (now < undoUntil) return
        val segs = Stats.biliSegList
        if (segs.isEmpty() || posMs < 0) return
        val est = posMs + (now - posAt)

        val seg = segs.firstOrNull { est >= it.startMs - LEAD_MS && est < it.endMs }
        if (seg == null) {
            bubble.hide()
            return
        }
        if (skipped.contains(seg.uuid)) {
            bubble.hide()
            return
        }
        if (est < seg.startMs) {
            if (Settings.bubbleEnabled) {
                val left = ((seg.startMs - est + 999) / 1000).toInt().coerceIn(1, 3)
                bubble.show("$left 秒后跳过广告")
            } else {
                bubble.hide()
            }
            return
        }
        if (now - lastSeekAt < SEEK_GUARD_MS) return
        val tries = attempts[seg.uuid] ?: 0
        if (tries >= 3) {
            skipped.add(seg.uuid)
            Stats.log("多次跳转未生效，放弃该片段")
            return
        }
        attempts[seg.uuid] = tries + 1
        bubble.hide()
        startSkip(seg)
    }

    /** 优先会话 seek（精确、不需要控件），失败再退回拖进度条。 */
    private fun startSkip(seg: AdSegment) {
        val target = skipTargetMs(seg)
        val before = BiliPlaybackWatcher.biliPositionMs()
        if (BiliPlaybackWatcher.seekBili(target)) {
            lastSeekAt = SystemClock.elapsedRealtime()
            skipped.add(seg.uuid)
            pendingSeg = seg
            pendingTarget = target
            pendingBeforePos = before
            lastStateLog = 0
            bubble.hide()
            Stats.log("会话 seek ${fmt(seg.startMs)}→${fmt(target)}")
            main.postDelayed({ verifySkip() }, 1500)
            return
        }
        dragPath(seg)
    }

    private fun verifySkip() {
        val seg = pendingSeg ?: return
        lastScan = 0
        scanInto(service.rootInActiveWindow)
        confirmPosition()?.let { return finishSkip(seg, it) }
        if (bar == null) {
            revealControls()
            main.postDelayed({
                lastScan = 0
                scanInto(service.rootInActiveWindow)
                val ok = confirmPosition()
                if (ok != null) {
                    finishSkip(seg, ok)
                } else {
                    Stats.log("会话 seek 无效，改用拖动")
                    retryDrag(seg)
                }
            }, 900)
            return
        }
        Stats.log("会话 seek 无效（${fmt(posMs)}），改用拖动")
        retryDrag(seg)
    }

    /** 拖动失败会回到这里，必须限次数，否则一直原地重试。 */
    private fun retryDrag(seg: AdSegment) {
        pendingSeg = null
        skipped.remove(seg.uuid)
        val tries = attempts[seg.uuid] ?: 0
        if (tries >= 3) {
            skipped.add(seg.uuid)
            Stats.log("多次跳转未生效，放弃该片段")
            return
        }
        attempts[seg.uuid] = tries + 1
        dragPath(seg)
    }

    /** 确认是否已跳到目标之后。返回 界面读数? -> 位置。 */
    private fun confirmPosition(): Pair<Boolean, Long>? {
        if (SystemClock.elapsedRealtime() - posAt < 1800 && posMs >= pendingTarget - 2000) {
            return true to posMs
        }
        val session = BiliPlaybackWatcher.biliPositionMs()
        return if (session != null && session != pendingBeforePos && session >= pendingTarget - 3000) {
            false to session
        } else {
            null
        }
    }

    private fun finishSkip(seg: AdSegment, at: Pair<Boolean, Long>) {
        pendingSeg = null
        lastSeekAt = SystemClock.elapsedRealtime()
        bubble.hide()
        if (!at.first) {
            posMs = at.second
            posAt = SystemClock.elapsedRealtime()
            Stats.biliPositionMs = posMs
        }
        Stats.biliSkipCount++
        Stats.biliLastSkip = "${fmt(seg.startMs)} → ${fmt(seg.endMs)}"
        Stats.log("seek 生效（${if (at.first) "界面" else "会话"}），当前 ${fmt(at.second)}")
        offerUndo(seg)
    }

    /** 跳过生效后 3 秒内可点气泡撤回，回到广告前 2 秒。 */
    private fun offerUndo(seg: AdSegment) {
        val now = SystemClock.elapsedRealtime()
        undoUntil = now + UNDO_MS
        undoTo = (seg.startMs - UNDO_BACK_MS).coerceAtLeast(0)
        undoUuid = seg.uuid
        bubble.show("已跳过广告", "撤回") { undo(seg) }
        main.postDelayed({
            if (undoUuid == seg.uuid && SystemClock.elapsedRealtime() >= undoUntil) {
                undoUuid = null
                undoUntil = 0
                bubble.hide()
            }
        }, UNDO_MS + 60)
    }

    private fun undo(seg: AdSegment) {
        if (undoUuid != seg.uuid || SystemClock.elapsedRealtime() > undoUntil) {
            bubble.hide()
            return
        }
        val to = undoTo
        undoUuid = null
        undoUntil = 0
        bubble.hide()
        if (BiliPlaybackWatcher.seekBili(to)) {
            applyUndo(to)
            return
        }
        // 控件可能已隐藏，先唤出再拖
        if (bar == null) revealControls()
        main.postDelayed({
            lastScan = 0
            scanInto(service.rootInActiveWindow)
            val est = posMs + (SystemClock.elapsedRealtime() - posAt)
            if (dragBarTo(est, to)) applyUndo(to) else Stats.log("撤回失败：播放器未响应")
        }, if (bar == null) 700 else 0)
    }

    private fun applyUndo(to: Long) {
        lastSeekAt = SystemClock.elapsedRealtime()
        posMs = to
        posAt = SystemClock.elapsedRealtime()
        Stats.biliPositionMs = posMs
        if (Stats.biliSkipCount > 0) Stats.biliSkipCount--
        Stats.biliLastSkip = "已撤回 → ${fmt(to)}"
        Stats.log("撤回跳过，回到 ${fmt(to)}")
        main.postDelayed({ verifyUndo(to) }, 1200)
    }

    /** 会话 seek 可能被播放器忽略：读界面/会话复核，不一致就改用拖动再撤一次。 */
    private fun verifyUndo(to: Long) {
        lastScan = 0
        scanInto(service.rootInActiveWindow)
        val fresh = SystemClock.elapsedRealtime() - posAt < 500
        val uiOk = fresh && Math.abs(posMs - to) <= 6000
        val session = BiliPlaybackWatcher.biliPositionMs()
        val sessionOk = session != null && Math.abs(session - to) <= 6000
        if (uiOk || sessionOk) return
        Stats.log("撤回未生效（${fmt(posMs)}），改用拖动")
        if (bar == null) revealControls()
        main.postDelayed({
            lastScan = 0
            scanInto(service.rootInActiveWindow)
            val from = posMs + (SystemClock.elapsedRealtime() - posAt)
            if (dragBarTo(from, to)) {
                lastSeekAt = SystemClock.elapsedRealtime()
                posMs = to
                posAt = SystemClock.elapsedRealtime()
                Stats.biliPositionMs = to
                Stats.biliLastSkip = "已撤回 → ${fmt(to)}"
                Stats.log("撤回拖动生效，回到 ${fmt(to)}")
            } else {
                Stats.biliLastSkip = "撤回未生效（${fmt(posMs)}）"
                Stats.log("撤回失败：播放器未响应")
            }
        }, if (bar == null) 700 else 0)
    }

    private fun dragPath(seg: AdSegment) {
        if (bar != null && SystemClock.elapsedRealtime() - posAt < FRESH_MS) {
            dragTo(seg)
        } else {
            freshThenSeek(seg)
        }
    }

    /** 控件可见但读数陈旧时先重扫；控件隐藏则单击播放区唤出后再扫。 */
    private fun freshThenSeek(seg: AdSegment) {
        if (bar != null) {
            lastScan = 0
            scanInto(service.rootInActiveWindow)
            if (bar != null && SystemClock.elapsedRealtime() - posAt < FRESH_MS) {
                dragTo(seg)
            } else {
                lastSeekAt = SystemClock.elapsedRealtime()
                Stats.log("进度读数不新鲜，放弃本次跳过")
            }
            return
        }
        revealControls()
        main.postDelayed({
            if (SystemClock.elapsedRealtime() - lastSeekAt < 3000) return@postDelayed
            lastScan = 0
            scanInto(service.rootInActiveWindow)
            val est = posMs + (SystemClock.elapsedRealtime() - posAt)
            if (bar != null && est >= seg.startMs - 4000 && est < seg.endMs) {
                dragTo(seg)
            } else {
                lastSeekAt = SystemClock.elapsedRealtime()
                Stats.log(if (bar == null) "未找到进度条，本次未跳过" else "播放位置与预期不符，放弃本次跳过")
            }
        }, 700)
    }

    /** 单击播放区唤出控件（控件已隐藏时不会暂停播放）。 */
    private fun revealControls() {
        val m = service.resources.displayMetrics
        tap(m.widthPixels / 2f, m.heightPixels * 0.22f)
    }

    /**
     * 跳过目标。广告一直顶到片尾时（实测 BV1imaN6tEK3，片段 11:07.3–13:28.3 = 片长），
     * 目标必须留在片长内：seek 越过片长会被播放器当成"没这条片段"处理，直接从 00:01 重播。
     */
    private fun skipTargetMs(seg: AdSegment): Long {
        val raw = seg.endMs + 300
        return if (durMs > 0) minOf(raw, durMs - 1500).coerceAtLeast(0) else raw
    }

    private fun dragTo(seg: AdSegment) {
        val target = skipTargetMs(seg)
        val est = posMs + (SystemClock.elapsedRealtime() - posAt)
        if (!dragBarTo(est, target)) return
        lastSeekAt = SystemClock.elapsedRealtime()
        skipped.add(seg.uuid)
        pendingSeg = seg
        pendingTarget = target
        pendingBeforePos = null
        lastStateLog = 0
        bubble.hide()
        Stats.log("拖动进度条 ${fmt(est)}→${fmt(target)}")
        main.postDelayed({ verifySkip() }, 1600)
    }

    /** 在进度条上从 fromMs 拖到 toMs；失败返回 false。 */
    private fun dragBarTo(fromMs: Long, toMs: Long): Boolean {
        val r = bar ?: return false
        val dur = durMs
        if (dur <= 0 || posMs < 0) return false
        val y = r.exactCenterY()
        // 拖动必须从滑块中心起手，B站 才认；直接用 left+比例 会落在轨道上被忽略
        val thumb = THUMB_RADIUS_DP * service.resources.displayMetrics.density
        val usable = (r.width() - 2 * thumb).coerceAtLeast(1f)
        fun xOf(ms: Long) = (r.left + thumb + (ms.toFloat() / dur) * usable)
            .coerceIn(r.left + 1f, r.right - 1f)
        val fromX = xOf(fromMs)
        val toX = xOf(toMs)
        val path = Path().apply {
            moveTo(fromX, y)
            lineTo(toX, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 500L))
            .build()
        if (!service.dispatchGesture(gesture, null, null)) {
            Stats.log("拖动进度条失败")
            return false
        }
        return true
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60L))
            .build()
        service.dispatchGesture(gesture, null, null)
    }

    private class ScanResult(val pos: Long, val dur: Long, val bar: Rect?, val paused: Boolean?)

    private fun scanPlayer(root: AccessibilityNodeInfo): ScanResult {
        var pos = -1L
        var dur = -1L
        var barRect: Rect? = null
        var isPaused: Boolean? = null
        var visited = 0

        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 30 || visited >= MAX_SCAN_NODES) return
            visited++
            val t = n.text?.toString()
            if (t != null && pos < 0 && t.length <= 16) {
                val m = PROGRESS.find(t)
                if (m != null) {
                    pos = toMs(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                    dur = toMs(m.groupValues[4], m.groupValues[5], m.groupValues[6])
                }
            }
            val id = n.viewIdResourceName
            if (barRect == null && id != null &&
                (id.contains("seekbar", true) || id.contains("seek_bar", true))
            ) {
                val r = Rect()
                n.getBoundsInScreen(r)
                // 实测该节点支持 ACTION_SET_PROGRESS，但无障碍进度值只有 0-100（29 分钟视频
                // 一档 17.8 秒），落点精度不如 seekTo 与拖动，所以不用它
                if (r.width() > 400 && r.height() in 6..240) barRect = r
            }
            // 页面里“49.2万播放”这类文案也含“播放”，只认播放器按钮的精确描述
            if (isPaused == null && id != null && id.contains("play", true)) {
                when (n.contentDescription?.toString()?.trim()) {
                    "播放" -> isPaused = true
                    "暂停" -> isPaused = false
                }
            }
            for (i in 0 until n.childCount) {
                val child = n.getChild(i) ?: continue
                walk(child, depth + 1)
            }
        }
        walk(root, 0)
        return ScanResult(pos, dur, barRect, isPaused)
    }

    private fun toMs(a: String, b: String, c: String): Long {
        val n1 = a.toLongOrNull() ?: return 0
        val n2 = b.toLongOrNull() ?: return 0
        return if (c.isEmpty()) {
            n1 * 60_000 + n2 * 1000
        } else {
            val n3 = c.toLongOrNull() ?: return 0
            n1 * 3_600_000 + n2 * 60_000 + n3 * 1000
        }
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.CHINA, "%02d:%02d", s / 60, s % 60)
    }
}

package com.example.hongguo_adskipper

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 跨界面共享的运行状态与日志（B站监听 / 无障碍服务 / 主界面） */
object Stats {
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
    private val logs = ArrayDeque<String>()
    private const val MAX_LOGS = 40

    @Volatile var biliTitle: String = "-"
    @Volatile var biliBvid: String? = null
    @Volatile var biliSegments: Int = 0
    @Volatile var biliSegList: List<AdSegment> = emptyList()
    @Volatile var biliUiActiveAt: Long = 0
    @Volatile var biliPositionMs: Long = 0
    @Volatile var biliDurationMs: Long = 0
    @Volatile var biliLastSkip: String = "-"
    @Volatile var splashLastTarget: String = "-"
    @Volatile var lastError: String? = null

    fun log(msg: String) {
        val line = "${timeFmt.format(Date())}  $msg"
        Log.i("AdSkipper", msg)
        synchronized(logs) {
            logs.addFirst(line)
            while (logs.size > MAX_LOGS) logs.removeLast()
        }
    }

    fun logLines(): List<String> = synchronized(logs) { logs.toList() }

    fun clearLogs() {
        synchronized(logs) { logs.clear() }
    }
}

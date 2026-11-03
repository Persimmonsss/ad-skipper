package com.example.hongguo_adskipper

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 可导出的诊断日志：内存留最近 400 行，按 2 秒节流落盘，文件超过 256KB 自动截掉旧的一半。
 * 跳过检测的高频日志只进内存队列，不会每条都做磁盘 IO。
 */
object DiagLog {
    private const val FILE_NAME = "diag.log"
    private const val MAX_BYTES = 256 * 1024
    private const val KEEP_BYTES = 128 * 1024
    private const val MAX_MEMORY = 400
    private const val FLUSH_GAP_MS = 2000L

    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA)
    private val memory = ArrayDeque<String>()
    private val pending = ArrayList<String>()
    private var file: File? = null
    private var lastFlush = 0L
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "diag-log") }

    fun init(context: Context) {
        file = File(context.applicationContext.filesDir, FILE_NAME)
        writer.execute {
            try {
                trimIfNeeded(file!!)
            } catch (e: Exception) {
                Log.w("DiagLog", "初始化日志失败: ${e.message}")
            }
        }
    }

    fun log(msg: String) {
        val line = "${timeFmt.format(Date())}  $msg"
        synchronized(this) {
            memory.addLast(line)
            while (memory.size > MAX_MEMORY) memory.removeFirst()
            pending.add(line)
            val now = SystemClock.elapsedRealtime()
            if (now - lastFlush >= FLUSH_GAP_MS) {
                lastFlush = now
                flushLocked()
            }
        }
    }

    /** 导出前调用：等内存里还没落盘的行写完，避免和后台写线程同时改文件。 */
    fun flushNow() {
        val batch: List<String>
        synchronized(this) {
            batch = ArrayList(pending)
            pending.clear()
            lastFlush = SystemClock.elapsedRealtime()
        }
        if (batch.isEmpty()) return
        try {
            writer.submit {
                file?.let { append(it, batch) }
            }.get(2, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w("DiagLog", "刷新日志失败: ${e.message}")
        }
    }

    /** 导出文件用：返回内存里的最近日志（含尚未落盘的行）。 */
    fun recentLines(): List<String> = synchronized(this) { memory.toList() }

    /** 落盘日志的末尾若干行；文件不存在时返回空。 */
    fun fileTail(maxLines: Int = 500): List<String> {
        flushNow()
        val f = file ?: return emptyList()
        return try {
            if (!f.exists()) emptyList()
            else f.readLines().takeLast(maxLines)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun flushLocked() {
        if (pending.isEmpty()) return
        val batch = ArrayList(pending)
        pending.clear()
        val target = file ?: return
        writer.execute {
            try {
                append(target, batch)
            } catch (e: Exception) {
                Log.w("DiagLog", "写日志失败: ${e.message}")
            }
        }
    }

    private fun append(target: File, batch: List<String>) {
        trimIfNeeded(target)
        target.appendText(batch.joinToString("\n", postfix = "\n"))
    }

    private fun trimIfNeeded(target: File) {
        if (!target.exists() || target.length() <= MAX_BYTES) return
        val text = target.readText()
        val cut = (text.length - KEEP_BYTES).coerceAtLeast(0)
        val from = text.indexOf('\n', cut).let { if (it < 0) text.length else it + 1 }
        target.writeText(text.substring(from))
    }
}
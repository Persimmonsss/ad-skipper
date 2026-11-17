package com.example.hongguo_adskipper

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 精简广告台账：一条广告一行，从命中特征到复核结局全写在这行里。
 * 落点是应用外部 files 目录，adb 连上就能直接 pull，不用把电脑挂在手机旁边现场盯。
 * 只在广告收口时写一次，正常刷剧零 IO；行太多时只保留最近一半。
 */
object AdLog {
    private const val FILE_NAME = "ads.log"
    // 只查主包版本：AndroidManifest 的 <queries> 里声明的就是它
    private const val HONGGUO_PKG = "com.phoenix.read"
    private const val MAX_LINES = 400
    private const val KEEP_LINES = 200

    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "ads-log") }
    private var file: File? = null

    /** 一条广告从命中到收口往往跨几秒，先把这行挂着，复核结论回来才落盘。 */
    private var openLine: String? = null

    fun init(context: Context) {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        file = File(dir, FILE_NAME)
        val info = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val version = info?.versionName ?: "?"
        val dm = context.resources.displayMetrics
        // 红果自己的版本号：广告形态跟着它改版变，日后一条判据失灵，先看是不是版本对不上
        val hg = runCatching {
            context.packageManager.getPackageInfo(HONGGUO_PKG, 0).versionName
        }.getOrNull() ?: "未装"
        @Suppress("DEPRECATION")
        append("== ${stamp()} 跳跳狐 v$version(vc${info?.versionCode ?: 0}) / ${Build.MODEL}" +
            " ${dm.widthPixels}x${dm.heightPixels} 档位${Settings.speedTier} 红果 $hg 台账开始 ==")
    }

    fun start(detail: String) {
        val stale = openLine
        if (stale != null) append("$stale → 没等到复核结论（被下一条命中顶掉）")
        openLine = "${stamp()} $detail"
    }

    /** 中途补一段（出手时刻、复核时刻），最后随 end 一起落盘。 */
    fun mark(extra: String) {
        val line = openLine ?: return
        openLine = "$line $extra"
    }

    /**
     * 一条广告之外的取证行。两类：
     * · 候选未出手 —— 看着像广告但被某道闸挡掉了，只记不动手。这是没人现场挂 adb 时发现
     *   「擦边却没跳」的那条通道（完全不在判据射程里的，靠静默样本那行）。
     * · 环境事实（界面名、正片基线、取不到树、静默样本）—— 解释「这台机器为什么一条广告都没记到」。
     */
    fun note(detail: String) {
        append("${stamp()} $detail")
    }

    /**
     * 广告收口：把结局接在挂着的那行后面落盘。
     * 没有命中记录时不写：复核结论一定跟在命中之后，单独出现只能是被顶掉或重复到达的第二次收口。
     */
    fun end(outcome: String) {
        val line = openLine ?: return
        openLine = null
        append("$line → $outcome")
    }

    fun splash(detail: String) {
        append("${stamp()} $detail")
    }

    private fun stamp(): String = synchronized(timeFmt) { timeFmt.format(Date()) }

    private fun append(line: String) {
        val target = file ?: return
        // 同一行也进诊断日志：界面上的「导出诊断报告」是唯一不连电脑就能拿走记录的通道
        DiagLog.log("台账 $line")
        writer.execute {
            try {
                target.appendText("$line\n")
                trimIfNeeded(target)
            } catch (e: Exception) {
                android.util.Log.w("AdLog", "台账写入失败: ${e.message}")
            }
        }
    }

    private fun trimIfNeeded(target: File) {
        val lines = target.readLines()
        if (lines.size <= MAX_LINES) return
        target.writeText(lines.takeLast(KEEP_LINES).joinToString("\n", postfix = "\n"))
    }
}

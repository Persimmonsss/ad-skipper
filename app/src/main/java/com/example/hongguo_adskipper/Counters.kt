package com.example.hongguo_adskipper

import android.content.Context
import android.content.SharedPreferences

/** 三个模块统一口径：检测（认出广告）/ 跳过（下发了动作）/ 成功（复核生效）。 */
enum class SkipStat {
    BILI_DETECT, BILI_SKIP, BILI_SUCCESS, BILI_UNDO,
    SPLASH_DETECT, SPLASH_SKIP, SPLASH_SUCCESS,
    HG_DETECT, HG_SKIP, HG_SUCCESS, HG_FAIL
}

/** 累计计数落 SharedPreferences：服务重启、手机重启都不清零。 */
object Counters {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("counters", Context.MODE_PRIVATE)
    }

    fun inc(stat: SkipStat) {
        if (!::prefs.isInitialized) return
        prefs.edit().putInt(stat.name, of(stat) + 1).apply()
    }

    fun of(stat: SkipStat): Int =
        if (::prefs.isInitialized) prefs.getInt(stat.name, 0) else 0
}

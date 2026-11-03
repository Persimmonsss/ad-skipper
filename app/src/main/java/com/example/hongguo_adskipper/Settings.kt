package com.example.hongguo_adskipper

import android.content.Context

object Settings {
    private const val PREF = "ad_skipper_settings"
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun sp() = appContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun putBool(key: String, value: Boolean) {
        sp().edit().putBoolean(key, value).apply()
    }

    var biliEnabled: Boolean
        get() = sp().getBoolean("bili_enabled", true)
        set(v) { putBool("bili_enabled", v) }

    var bubbleEnabled: Boolean
        get() = sp().getBoolean("bubble_enabled", true)
        set(v) { putBool("bubble_enabled", v) }

    /** 跳过后的「已跳过广告 · 撤回」气泡；默认关，需要撤回时再开。 */
    var undoBubbleEnabled: Boolean
        get() = sp().getBoolean("undo_bubble_enabled", false)
        set(v) { putBool("undo_bubble_enabled", v) }

    var splashEnabled: Boolean
        get() = sp().getBoolean("splash_enabled", true)
        set(v) { putBool("splash_enabled", v) }

    var hongguoEnabled: Boolean
        get() = sp().getBoolean("hongguo_enabled", true)
        set(v) { putBool("hongguo_enabled", v) }

    /** 识别速度档：0=稳（省电），1=平衡（默认），2=快。旧版 0~100 进度条首次读到时自动折算。 */
    var speedTier: Int
        get() {
            val saved = sp().getInt("speed_tier", -1)
            if (saved in 0..2) return saved
            val legacy = sp().getInt("perf_level", 65)
            return when {
                legacy >= 85 -> 2
                legacy <= 35 -> 0
                else -> 1
            }
        }
        set(v) { sp().edit().putInt("speed_tier", v.coerceIn(0, 2)).apply() }
}

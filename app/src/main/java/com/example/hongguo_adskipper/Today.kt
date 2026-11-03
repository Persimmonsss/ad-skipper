package com.example.hongguo_adskipper

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 今日战果：成功跳过次数 + 估算省下的时长，跨天自动清零。 */
object Today {
    private const val PREF = "today_stats"
    private const val KEY_DATE = "date"
    private const val KEY_COUNT = "count"
    private const val KEY_SAVED_MS = "saved_ms"

    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    }

    fun record(savedMs: Long) {
        if (!::prefs.isInitialized) return
        synchronized(this) {
            val today = dayFmt.format(Date())
            val sameDay = prefs.getString(KEY_DATE, "") == today
            val count = if (sameDay) prefs.getInt(KEY_COUNT, 0) else 0
            val saved = if (sameDay) prefs.getLong(KEY_SAVED_MS, 0L) else 0L
            prefs.edit()
                .putString(KEY_DATE, today)
                .putInt(KEY_COUNT, count + 1)
                .putLong(KEY_SAVED_MS, saved + savedMs.coerceAtLeast(0L))
                .apply()
        }
    }

    val count: Int
        get() {
            if (!::prefs.isInitialized) return 0
            synchronized(this) {
                if (prefs.getString(KEY_DATE, "") != dayFmt.format(Date())) return 0
                return prefs.getInt(KEY_COUNT, 0)
            }
        }

    val savedMs: Long
        get() {
            if (!::prefs.isInitialized) return 0L
            synchronized(this) {
                if (prefs.getString(KEY_DATE, "") != dayFmt.format(Date())) return 0L
                return prefs.getLong(KEY_SAVED_MS, 0L)
            }
        }

    /** 省时按秒展示更实在：不到 1 分钟写成「45 秒」。 */
    fun savedText(): String {
        val sec = savedMs / 1000
        if (sec <= 0) return "0 秒"
        return if (sec < 60) "$sec 秒" else "${sec / 60} 分钟"
    }
}
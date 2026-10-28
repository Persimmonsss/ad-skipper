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

    var splashEnabled: Boolean
        get() = sp().getBoolean("splash_enabled", true)
        set(v) { putBool("splash_enabled", v) }

    var hongguoEnabled: Boolean
        get() = sp().getBoolean("hongguo_enabled", true)
        set(v) { putBool("hongguo_enabled", v) }
}

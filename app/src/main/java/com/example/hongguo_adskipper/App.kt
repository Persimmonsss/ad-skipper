package com.example.hongguo_adskipper

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        Counters.init(this)
        Today.init(this)
        DiagLog.init(this)
        DiagLog.log("App 启动 v${runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()}")
    }
}
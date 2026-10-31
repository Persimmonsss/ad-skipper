package com.example.hongguo_adskipper

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        Counters.init(this)
    }
}

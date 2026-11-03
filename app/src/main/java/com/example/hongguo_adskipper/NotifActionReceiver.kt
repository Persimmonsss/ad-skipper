package com.example.hongguo_adskipper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 常驻通知里的「暂停 / 恢复」按钮。 */
class NotifActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_TOGGLE_PAUSE = "com.example.hongguo_adskipper.TOGGLE_PAUSE"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_TOGGLE_PAUSE) return
        Settings.init(context)
        Settings.paused = !Settings.paused
        DiagLog.log(if (Settings.paused) "用户从通知栏暂停跳过" else "用户从通知栏恢复跳过")
        AdSkipAccessibilityService.instance?.refreshNotification()
    }
}
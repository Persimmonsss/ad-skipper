package com.example.hongguo_adskipper

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/** 跳过前的悬浮气泡提示，底部居中，不抢焦点 */
class SkipBubble(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: TextView? = null
    private var attached = false
    private var lastText = ""

    private fun params(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 260
        }

    fun show(text: String) {
        if (!Settings.canDrawOverlays(context)) return
        if (view == null) {
            view = TextView(context).apply {
                setTextColor(Color.WHITE)
                textSize = 15f
                setPadding(42, 22, 42, 22)
                background = GradientDrawable().apply {
                    cornerRadius = 999f
                    setColor(Color.parseColor("#E6212121"))
                    setStroke(1, Color.parseColor("#40FFFFFF"))
                }
            }
        }
        if (text != lastText) {
            view?.text = text
            lastText = text
        }
        if (!attached) {
            runCatching {
                wm?.addView(view, params())
                attached = true
            }.onFailure { Stats.log("气泡添加失败：${it.message}") }
        }
    }

    fun hide() {
        if (attached) {
            runCatching {
                view?.let { wm?.removeView(it) }
            }
            attached = false
            lastText = ""
        }
    }
}

package com.example.hongguo_adskipper

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** 底部悬浮气泡：跳过前倒计时提示，跳过后可点按钮撤回；不抢焦点 */
class SkipBubble(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var container: LinearLayout? = null
    private var label: TextView? = null
    private var action: TextView? = null
    private var attached = false
    private var lastKey = ""

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

    private fun build() {
        label = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
        }
        action = TextView(context).apply {
            setTextColor(Color.parseColor("#FFD54F"))
            textSize = 15f
            setPadding(30, 0, 0, 0)
            visibility = View.GONE
        }
        container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(42, 22, 42, 22)
            background = GradientDrawable().apply {
                cornerRadius = 999f
                setColor(Color.parseColor("#E6212121"))
                setStroke(1, Color.parseColor("#40FFFFFF"))
            }
            addView(label, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(action, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    fun show(text: String) = show(text, null, null)

    fun show(text: String, actionText: String?, onAction: (() -> Unit)?) {
        if (!Settings.canDrawOverlays(context)) return
        if (container == null) build()
        val key = "$text|$actionText"
        if (key != lastKey) {
            label?.text = text
            action?.text = actionText ?: ""
            action?.visibility = if (actionText == null) View.GONE else View.VISIBLE
            lastKey = key
        }
        action?.setOnClickListener { onAction?.invoke() }
        if (!attached) {
            runCatching {
                wm?.addView(container, params())
                attached = true
            }.onFailure { Stats.log("气泡添加失败：${it.message}") }
        }
    }

    fun hide() {
        if (attached) {
            runCatching {
                container?.let { wm?.removeView(it) }
            }
            attached = false
            lastKey = ""
        }
    }
}

package com.example.aiphone

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/** One visible, input-transparent window for all process-owned leases, on the phone display. */
internal class ScreenHoldOverlay(private val changed: () -> Unit) {
    private val handler=Handler(Looper.getMainLooper())
    private var service: AccessibilityService? = null
    private var windowManager: WindowManager? = null
    private var view: View? = null
    val available: Boolean get() = service?.getSystemService(DisplayManager::class.java)
        ?.getDisplay(Display.DEFAULT_DISPLAY) != null
    val attached: Boolean get() = view?.let { it.isAttachedToWindow && it.isShown } == true

    fun bind(owner: AccessibilityService) {
        if(service === owner) return
        remove()
        service=owner
    }
    fun unbind(owner: AccessibilityService) {
        if(service !== owner) return
        try { remove() } finally { service=null }
    }
    fun ensure() {
        if(view!=null) return
        val owner=service ?: throw IllegalStateException("Accessibility service is not connected")
        val display=owner.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            ?: throw IllegalStateException("Phone display 0 is unavailable")
        // AccessibilityService supplies the public overlay token for this display context.
        val displayContext=owner.createDisplayContext(display)
        val wm=displayContext.getSystemService(WindowManager::class.java)
        val size=(8 * displayContext.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val indicator=View(displayContext).apply {
            background=GradientDrawable().apply { shape=GradientDrawable.OVAL;setColor(0xff42a5f5.toInt()) }
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isFocusable=false
            isClickable=false
            addOnAttachStateChangeListener(object: View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { handler.post { changed() } }
                override fun onViewDetachedFromWindow(v: View) { handler.post { changed() } }
            })
        }
        val params=WindowManager.LayoutParams(size,size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT).apply {
                gravity=Gravity.TOP or Gravity.END
                title="AI-phone screen hold"
            }
        wm.addView(indicator,params)
        windowManager=wm
        view=indicator
    }
    fun remove() {
        val current=view ?: return
        windowManager!!.removeViewImmediate(current)
        view=null
        windowManager=null
    }
}

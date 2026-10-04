package com.example.strictmode

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Süre dolan uygulamanın ÜSTÜNE tam ekran yemin ekranı çizer (erişilebilirlik overlay'i).
 * Activity başlatmadığı için Android'in arka plandan activity açma kısıtına takılmaz.
 */
class GuardService : AccessibilityService() {
    private val h = Handler(Looper.getMainLooper())
    private var current: String? = null
    private var overlay: View? = null
    private var overlayPkg: String? = null
    private var ignored = setOf<String>()

    private val tick = object : Runnable {
        override fun run() {
            if (overlay == null) current?.let { check(it) }
            h.postDelayed(this, 20_000)
        }
    }

    override fun onServiceConnected() {
        val imm = getSystemService(InputMethodManager::class.java)
        val keyboards = imm?.enabledInputMethodList?.map { it.packageName }?.toSet() ?: emptySet()
        ignored = keyboards + setOf("com.android.systemui", packageName)
        h.post(tick)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        if (pkg in ignored) return
        if (overlay != null && pkg != overlayPkg) hideOverlay()
        current = pkg
        check(pkg)
    }

    private fun check(pkg: String) {
        if (overlay != null) return
        if (Prefs.blocked(this, pkg)) showOverlay(pkg)
    }

    private fun showOverlay(pkg: String) {
        val d = resources.displayMetrics.density
        val pad = (24 * d).toInt()

        val info = TextView(this).apply {
            textSize = 18f; setTextColor(Color.WHITE)
            text = "Bu uygulama için bugünkü süren doldu.\n\n" +
                "Yine de devam etmek istiyorsan şu cümleyi aynen yaz:\n\n“${Prefs.OATH}”"
        }
        val input = EditText(this).apply {
            hint = "Yemini buraya yaz"; setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE); isSingleLine = true
        }
        val go = Button(this).apply {
            text = "Yemin ediyorum, ${Prefs.BYPASS_MIN} dk ver"; isEnabled = false
        }
        val quit = Button(this).apply { text = "Vazgeç" }

        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                go.isEnabled = s.toString().trim().equals(Prefs.OATH, ignoreCase = true)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        go.setOnClickListener { Prefs.grantBypass(this, pkg); hideOverlay() }
        quit.setOnClickListener { hideOverlay(); performGlobalAction(GLOBAL_ACTION_HOME) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#FF111118")); setPadding(pad, pad, pad, pad)
            addView(info); addView(input); addView(go); addView(quit)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE
        ).apply { softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN }

        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(root, lp)
            overlay = root; overlayPkg = pkg
        } catch (t: Throwable) {
            // Overlay eklenemezse en azından uygulamayı kapat
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun hideOverlay() {
        overlay?.let {
            try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Throwable) {}
        }
        overlay = null; overlayPkg = null
    }

    override fun onInterrupt() {}
    override fun onDestroy() { h.removeCallbacksAndMessages(null); hideOverlay(); super.onDestroy() }
}

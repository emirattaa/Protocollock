package com.example.strictmode

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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

class GuardService : AccessibilityService() {
    private val h = Handler(Looper.getMainLooper())
    private var current: String? = null
    private var pending: String? = null
    private var overlay: View? = null
    private var overlayPkg: String? = null
    private var ignored = setOf<String>()

    // Her 2 sn'de bir ekrandaki gerçek uygulamaya bakar (olay kaçarsa bile yakalar)
    private val tick = object : Runnable {
        override fun run() {
            try { poll() } catch (_: Throwable) {}
            h.postDelayed(this, 2_000)
        }
    }

    override fun onServiceConnected() {
        val imm = getSystemService(InputMethodManager::class.java)
        val keyboards = imm?.enabledInputMethodList?.map { it.packageName }?.toSet() ?: emptySet()
        ignored = keyboards + setOf("com.android.systemui", packageName)
        h.removeCallbacks(tick)
        h.post(tick)
    }

    private fun poll() {
        val pkg = rootInActiveWindow?.packageName?.toString()
        if (pkg != null && pkg !in ignored) onForeground(pkg)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        if (pkg in ignored) return
        onForeground(pkg)
    }

    private fun onForeground(pkg: String) {
        if (overlay != null && pkg != overlayPkg) hideOverlay()
        current = pkg
        check(pkg)
    }

    private fun check(pkg: String) {
        if (overlay != null || pending == pkg) return
        if (!Prefs.blocked(this, pkg)) return
        pending = pkg
        notifyHarv(pkg)
        // Hemen kapatma yok: bildirim gelir, kısa bir süre sonra yemin ekranı açılır
        h.postDelayed({
            pending = null
            if (current == pkg && overlay == null && Prefs.blocked(this, pkg)) showOverlay(pkg)
        }, Prefs.GRACE_SEC * 1000L)
    }

    private fun notifyHarv(pkg: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel("harv") == null) {
            nm.createNotificationChannel(
                NotificationChannel("harv", "Süre uyarısı", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Throwable) { pkg }
        nm.notify(
            1,
            Notification.Builder(this, "harv")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(Prefs.NOTIF_TEXT)
                .setContentText("$label için bugünkü süren doldu")
                .setAutoCancel(true)
                .build()
        )
    }

    private fun showOverlay(pkg: String) {
        val pad = (24 * resources.displayMetrics.density).toInt()

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

package com.example.strictmode

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class GuardService : AccessibilityService() {
    companion object {
        @Volatile var lastTick = 0L     // servis canlı mı (ana ekran durum paneli için)
        @Volatile var rootOk = false    // ekran içeriğini okuyabiliyor mu
        private val BG = Color.parseColor("#0B0F14")
        private val CARD = Color.parseColor("#151B23")
        private val ACCENT = Color.parseColor("#7C5CFF")
        private val DANGER = Color.parseColor("#E5484D")
        private val MUTED = Color.parseColor("#8B95A5")
    }

    private val h = Handler(Looper.getMainLooper())
    private val pendingSet = mutableSetOf<String>()
    private var overlay: View? = null
    private var overlayPkg: String? = null
    private var ignored = setOf<String>()
    private var visibleNow = setOf<String>()
    private var rootEverOk = false
    private var lastPoll = 0L

    // Her saniye ekrandaki TÜM pencerelere bakar (son uygulamalardan dönüş, bölünmüş ekran, yüzen pencere)
    private val tick = object : Runnable {
        override fun run() {
            try { poll(null) } catch (_: Throwable) {}
            h.postDelayed(this, 1_000)
        }
    }

    override fun onServiceConnected() {
        val imm = getSystemService(InputMethodManager::class.java)
        val keyboards = imm?.enabledInputMethodList?.map { it.packageName }?.toSet() ?: emptySet()
        ignored = keyboards + setOf("com.android.systemui", packageName)
        h.removeCallbacks(tick)
        h.post(tick)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        try { poll(e.packageName?.toString()) } catch (_: Throwable) {}
    }

    private fun windowPackages(): List<String> = try {
        windows.mapNotNull { it.root?.packageName?.toString() }
    } catch (_: Throwable) { emptyList() }

    private fun poll(eventPkg: String?) {
        val now = SystemClock.uptimeMillis()
        if (eventPkg != null && now - lastPoll < 300) return
        lastPoll = now
        lastTick = System.currentTimeMillis()

        val activePkg = rootInActiveWindow?.packageName?.toString()
        if (activePkg != null) rootEverOk = true
        rootOk = activePkg != null
        // İçerik okuma izni hiç yoksa (güncelleme sonrası olabilir) kullanım kayıtlarından tahmin et
        val fallback = if (!rootEverOk) Prefs.usage(this).fg else null

        // Ayarlar'da Katı Mod'u kapatma / silme / izin kaldırma girişimi
        if (activePkg != null && tamper(activePkg)) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            Toast.makeText(this, "Koruma açık. Önce sınırları kaldır (${Prefs.LOOSEN_HOURS} saat bekler).", Toast.LENGTH_LONG).show()
            return
        }

        val visible = (listOfNotNull(activePkg, eventPkg, fallback) + windowPackages())
            .filter { it !in ignored }.toSet()
        visibleNow = visible

        val op = overlayPkg
        if (overlay != null && op != null && op !in visible && visible.isNotEmpty()) hideOverlay()
        visible.forEach { check(it) }
    }

    /** Sınır varken Ayarlar/yükleyici ekranında "Katı Mod" adı geçiyorsa (silme, devre dışı bırakma...) engelle. */
    private fun tamper(pkg: String): Boolean {
        val risky = pkg.contains("settings") || pkg.contains("packageinstaller") ||
            pkg.contains("permissioncontroller") || pkg.contains("securitycenter")
        if (!risky || Prefs.limits(this).isEmpty()) return false
        val root = rootInActiveWindow ?: return false
        return try {
            root.findAccessibilityNodeInfosByText(getString(R.string.app_name)).isNotEmpty()
        } catch (_: Throwable) { false }
    }

    private fun check(pkg: String) {
        if (overlay != null || pkg in pendingSet) return
        if (!Prefs.blocked(this, pkg)) return
        pendingSet.add(pkg)
        try { notifyHarv(pkg) } catch (_: Throwable) {}
        // Hemen kapatma yok: bildirim gelir, kısa süre sonra yemin ekranı açılır
        h.postDelayed({
            pendingSet.remove(pkg)
            if (pkg in visibleNow && overlay == null && Prefs.blocked(this, pkg)) showOverlay(pkg)
        }, Prefs.GRACE_SEC * 1000L)
    }

    private fun label(pkg: String) = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Throwable) { pkg }

    private fun notifyHarv(pkg: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) {
            Toast.makeText(this, Prefs.NOTIF_TEXT, Toast.LENGTH_LONG).show()
            return
        }
        if (nm.getNotificationChannel("harv") == null) {
            nm.createNotificationChannel(
                NotificationChannel("harv", "Süre uyarısı", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        nm.cancel(1)
        nm.notify(
            1,
            Notification.Builder(this, "harv")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(Prefs.NOTIF_TEXT)
                .setContentText("${label(pkg)} için bugünkü süren doldu")
                .setCategory(Notification.CATEGORY_ALARM)
                .setAutoCancel(true)
                .build()
        )
    }

    /** Ana ekrana atar, sonra uygulamanın arka plan işlemini öldürür. (Son uygulamalar listesinden silmek mümkün değil.) */
    private fun closeApp(pkg: String) {
        hideOverlay()
        performGlobalAction(GLOBAL_ACTION_HOME)
        for (delay in longArrayOf(600, 2_500)) {
            h.postDelayed({
                try { (getSystemService(ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(pkg) }
                catch (_: Throwable) {}
            }, delay)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun round(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
    private fun text(t: String, size: Float, color: Int, bold: Boolean = false, top: Int = 0) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); gravity = Gravity.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    }
    private fun button(label: String, color: Int) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 16f; setTextColor(Color.WHITE)
        background = round(color, 14); stateListAnimator = null
        layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12) }
    }

    private fun showOverlay(pkg: String) {
        val left = Prefs.bypassesLeft(this, pkg)
        val limit = Prefs.limits(this)[pkg]

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(BG); setPadding(dp(28), dp(28), dp(28), dp(28))
        }
        root.addView(text("🔒", 56f, Color.WHITE))
        root.addView(text("Süren doldu", 28f, Color.WHITE, bold = true, top = 8))
        root.addView(text("${label(pkg)}${limit?.let { " · günde $it dk" } ?: ""}", 14f, MUTED, top = 4))

        val close = button("Uygulamayı kapat", DANGER)
        close.setOnClickListener { closeApp(pkg) }

        if (left > 0) {
            root.addView(text("Devam etmek için şu cümleyi aynen yaz:", 14f, MUTED, top = 28))
            root.addView(text("“${Prefs.OATH}”", 17f, Color.parseColor("#B7A6FF"), bold = true, top = 10))
            val input = EditText(this).apply {
                hint = "Yemini buraya yaz"; setHintTextColor(MUTED); setTextColor(Color.WHITE)
                isSingleLine = true; imeOptions = EditorInfo.IME_ACTION_DONE
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                background = round(CARD, 14); setPadding(dp(16), dp(14), dp(16), dp(14))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) }
            }
            val go = button("Yemin ediyorum · ${Prefs.BYPASS_MIN} dk ver", ACCENT).apply { isEnabled = false; alpha = 0.4f }
            input.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    val ok = Prefs.oathOk(s.toString())
                    go.isEnabled = ok; go.alpha = if (ok) 1f else 0.4f
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
            go.setOnClickListener {
                if (Prefs.grantBypass(this, pkg)) hideOverlay() else closeApp(pkg)
            }
            root.addView(input); root.addView(go)
            root.addView(text("Bugünkü yemin hakkın: $left/${Prefs.BYPASS_MAX_PER_DAY}", 12f, MUTED, top = 10))
        } else {
            root.addView(text("Bugünkü yemin hakkın bitti.\nYarın tekrar dene.", 16f, Color.WHITE, top = 28))
        }
        root.addView(close)

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
            closeApp(pkg) // overlay çizilemezse en azından uygulamayı kapat
        }
    }

    private fun hideOverlay() {
        overlay?.let {
            try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Throwable) {}
        }
        overlay = null; overlayPkg = null
    }

    override fun onInterrupt() {}
    override fun onDestroy() {
        lastTick = 0L; h.removeCallbacksAndMessages(null); hideOverlay(); super.onDestroy()
    }
}

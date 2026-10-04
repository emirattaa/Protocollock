package com.example.strictmode

import android.app.AppOpsManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat

data class AppItem(val pkg: String, val name: String, val icon: Drawable)

class MainActivity : AppCompatActivity() {
    private val BG = Color.parseColor("#0B0F14")
    private val CARD = Color.parseColor("#151B23")
    private val SEL = Color.parseColor("#2A2150")
    private val ACCENT = Color.parseColor("#7C5CFF")
    private val LILAC = Color.parseColor("#B7A6FF")
    private val DANGER = Color.parseColor("#E5484D")
    private val MUTED = Color.parseColor("#8B95A5")

    private var all = listOf<AppItem>()
    private var shown = listOf<AppItem>()
    private val selected = mutableSetOf<String>()
    private var limitsNow = mapOf<String, Int>()
    private var pendNow = mapOf<String, Pair<Int, Long>>()
    private var onlyLimited = false
    private var query = ""

    private lateinit var list: ListView
    private lateinit var tabAll: TextView
    private lateinit var tabLim: TextView
    private lateinit var bar: LinearLayout
    private lateinit var btnSet: Button
    private lateinit var status: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun round(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
    private fun lp(w: Int = -1, h: Int = -2, wt: Float = 0f, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
        LinearLayout.LayoutParams(w, h, wt).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(i: Int) = shown[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, v: View?, p: ViewGroup?): View {
            val wrap = (v as? FrameLayout) ?: makeRow()
            val card = wrap.getChildAt(0) as LinearLayout
            val app = shown[i]
            val lim = limitsNow[app.pkg]
            val pend = pendNow[app.pkg]
            val sel = app.pkg in selected
            card.background = round(if (sel) SEL else CARD, 16)
            (card.getChildAt(0) as ImageView).setImageDrawable(app.icon)
            val texts = card.getChildAt(1) as LinearLayout
            (texts.getChildAt(0) as TextView).text = app.name
            (texts.getChildAt(1) as TextView).apply {
                text = when {
                    lim == null -> "Sınırsız"
                    pend != null -> {
                        val hrs = (pend.second - System.currentTimeMillis() + 3_599_999L) / 3_600_000L
                        "Günde $lim dk → ${if (pend.first == 0) "kalkacak" else "${pend.first} dk"} · $hrs sa sonra"
                    }
                    else -> "Günde $lim dk"
                }
                setTextColor(if (lim != null) LILAC else MUTED)
            }
            (card.getChildAt(2) as CheckBox).isChecked = sel
            return wrap
        }
    }

    private fun makeRow(): FrameLayout = FrameLayout(this).apply {
        setPadding(dp(12), dp(4), dp(12), dp(4))
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(ImageView(context), LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0)
                addView(TextView(context).apply { textSize = 16f; setTextColor(Color.WHITE); maxLines = 1 })
                addView(TextView(context).apply { textSize = 12f })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(CheckBox(context).apply {
                isFocusable = false; isClickable = false; buttonTintList = ColorStateList.valueOf(ACCENT)
            })
        }, FrameLayout.LayoutParams(-1, -2))
    }

    private fun tab(onClick: () -> Unit) = TextView(this).apply {
        gravity = Gravity.CENTER; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(10), 0, dp(10)); setOnClickListener { onClick() }
    }

    private fun pill(label: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setTextColor(Color.WHITE); background = round(color, 14)
        stateListAnimator = null; setOnClickListener { onClick() }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val title = TextView(this).apply {
            text = "Katı Mod"; textSize = 28f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        }
        status = TextView(this).apply { textSize = 12f; setTextColor(MUTED); setLineSpacing(0f, 1.25f) }
        val statusCard = LinearLayout(this).apply {
            background = round(CARD, 16); setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(status, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(context).apply { text = "İzinler ›"; textSize = 13f; setTextColor(LILAC) })
            gravity = Gravity.CENTER_VERTICAL; setOnClickListener { showPerms() }
        }
        tabAll = tab { onlyLimited = false; refresh() }
        tabLim = tab { onlyLimited = true; refresh() }
        val tabs = LinearLayout(this).apply {
            background = round(CARD, 14); setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(tabAll, LinearLayout.LayoutParams(0, -2, 1f))
            addView(tabLim, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val search = EditText(this).apply {
            hint = "Uygulama ara…"; isSingleLine = true; setHintTextColor(MUTED); setTextColor(Color.WHITE)
            background = round(CARD, 14); setPadding(dp(16), dp(12), dp(16), dp(12))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { query = s.toString().trim(); refresh() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        list = ListView(this).apply {
            divider = null; dividerHeight = 0; selector = round(Color.TRANSPARENT, 0)
            this.adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, i, _ ->
                val pkg = shown[i].pkg
                if (!selected.remove(pkg)) selected.add(pkg)
                this@MainActivity.adapter.notifyDataSetChanged(); updateBar()
            }
        }
        btnSet = pill("Süre ata", ACCENT) { askMinutes() }
        val btnClear = pill("Sınırı kaldır", DANGER) {
            var delayed = false
            selected.forEach { if (Prefs.requestLimit(this, it, 0)) delayed = true }
            selected.clear(); refresh()
            if (delayed) Toast.makeText(this, "Kaldırma ${Prefs.LOOSEN_HOURS} saat sonra uygulanacak", Toast.LENGTH_LONG).show()
        }
        bar = LinearLayout(this).apply {
            setPadding(dp(12), dp(8), dp(12), dp(12))
            addView(btnSet, lp(0, -2, 1f, r = 6))
            addView(btnClear, lp(0, -2, 1f, l = 6))
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(BG); setPadding(0, dp(16), 0, 0)
            addView(title, lp(l = 16, r = 16))
            addView(statusCard, lp(l = 16, t = 12, r = 16))
            addView(tabs, lp(l = 16, t = 12, r = 16))
            addView(search, lp(l = 16, t = 10, r = 16, b = 6))
            addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(bar)
        })
        refresh()

        Thread {
            val pm = packageManager
            val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val items = pm.queryIntentActivities(launch, 0)
                .filter { it.activityInfo.packageName != packageName }
                .map { AppItem(it.activityInfo.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm)) }
                .distinctBy { it.pkg }.sortedBy { it.name.lowercase() }
            runOnUiThread { all = items; refresh() }
        }.start()
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun showPerms() {
        AlertDialog.Builder(this).setTitle("İzinleri aç")
            .setItems(arrayOf(
                "1) Kullanım erişimi", "2) Erişilebilirlik → Katı Mod", "3) Bildirim erişimi → Katı Mod",
                "4) Pil optimizasyonu → Katı Mod'u kısıtlama"
            )) { _, i ->
                startActivity(Intent(when (i) {
                    0 -> Settings.ACTION_USAGE_ACCESS_SETTINGS
                    1 -> Settings.ACTION_ACCESSIBILITY_SETTINGS
                    2 -> Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
                    else -> Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                }))
            }.show()
    }

    private fun askMinutes() {
        val existing = if (selected.size == 1) limitsNow[selected.first()] else null
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; hint = "Günlük dakika (örn. 60)"
            existing?.let { setText(it.toString()) }
        }
        AlertDialog.Builder(this)
            .setTitle("${selected.size} uygulamaya günlük süre")
            .setMessage("Süreyi düşürmek hemen, artırmak veya kaldırmak ${Prefs.LOOSEN_HOURS} saat sonra geçerli olur.")
            .setView(et)
            .setPositiveButton("Kaydet") { _, _ ->
                val m = et.text.toString().toIntOrNull() ?: 0
                if (m <= 0) {
                    Toast.makeText(this, "Geçerli bir dakika gir", Toast.LENGTH_SHORT).show()
                } else {
                    var delayed = false
                    selected.forEach { if (Prefs.requestLimit(this, it, m)) delayed = true }
                    selected.clear(); refresh()
                    if (delayed) Toast.makeText(this, "Gevşetme ${Prefs.LOOSEN_HOURS} saat sonra uygulanacak", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("İptal", null).show()
    }

    private fun updateStatus() {
        fun mark(b: Boolean) = if (b) "✅" else "❌"
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        val alive = System.currentTimeMillis() - GuardService.lastTick < 5_000
        val ops = getSystemService(AppOpsManager::class.java)
        val usage = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) ==
            AppOpsManager.MODE_ALLOWED
        val listener = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        val notif = NotificationManagerCompat.from(this).areNotificationsEnabled()
        status.text = "Erişilebilirlik ${mark(enabled.contains(packageName, true))}  " +
            "Servis ${mark(alive)}  Ekranı okuyor ${mark(GuardService.rootOk)}\n" +
            "Kullanım ${mark(usage)}  Bildirim erişimi ${mark(listener)}  Bildirim izni ${mark(notif)}\n" +
            "Koruma: " + if (limitsNow.isNotEmpty()) "aktif 🔒" else "sınır koyunca aktif olur"
    }

    private fun refresh() {
        limitsNow = Prefs.limits(this)
        pendNow = Prefs.pendings(this)
        updateStatus()
        shown = all.filter {
            (!onlyLimited || limitsNow.containsKey(it.pkg)) && it.name.contains(query, ignoreCase = true)
        }
        tabAll.text = "Tümü (${all.size})"
        tabLim.text = "Sınırlananlar (${limitsNow.size})"
        for ((t, active) in listOf(tabAll to !onlyLimited, tabLim to onlyLimited)) {
            t.background = if (active) round(ACCENT, 11) else round(Color.TRANSPARENT, 11)
            t.setTextColor(if (active) Color.WHITE else MUTED)
        }
        adapter.notifyDataSetChanged()
        updateBar()
    }

    private fun updateBar() {
        bar.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
        btnSet.text = "Süre ata (${selected.size})"
    }
}

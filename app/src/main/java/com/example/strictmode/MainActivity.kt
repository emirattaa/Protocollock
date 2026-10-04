package com.example.strictmode

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
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

data class AppItem(val pkg: String, val name: String, val icon: Drawable)

class MainActivity : AppCompatActivity() {
    private var all = listOf<AppItem>()
    private var shown = listOf<AppItem>()
    private val selected = mutableSetOf<String>()
    private var onlyLimited = false
    private var query = ""

    private lateinit var list: ListView
    private lateinit var tabAll: Button
    private lateinit var tabLim: Button
    private lateinit var bar: LinearLayout
    private lateinit var btnSet: Button
    private lateinit var btnClear: Button

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(i: Int) = shown[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, v: View?, p: ViewGroup?): View {
            val row = (v as? LinearLayout) ?: makeRow()
            val app = shown[i]
            val limit = Prefs.limits(this@MainActivity)[app.pkg]
            (row.getChildAt(0) as ImageView).setImageDrawable(app.icon)
            val texts = row.getChildAt(1) as LinearLayout
            (texts.getChildAt(0) as TextView).text = app.name
            (texts.getChildAt(1) as TextView).text = limit?.let { "Günde $it dk" } ?: "Sınırsız"
            (row.getChildAt(2) as CheckBox).isChecked = app.pkg in selected
            return row
        }
    }

    private fun makeRow(): LinearLayout = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8))
        addView(ImageView(context), LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0)
            addView(TextView(context).apply { textSize = 16f })
            addView(TextView(context).apply { textSize = 12f; alpha = 0.7f })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(CheckBox(context).apply { isFocusable = false; isClickable = false })
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val perms = Button(this).apply {
            text = "İzinler (4 adım)"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle("İzinleri aç")
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
        }
        tabAll = Button(this).apply { setOnClickListener { onlyLimited = false; refresh() } }
        tabLim = Button(this).apply { setOnClickListener { onlyLimited = true; refresh() } }
        val tabs = LinearLayout(this).apply {
            addView(tabAll, LinearLayout.LayoutParams(0, -2, 1f))
            addView(tabLim, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val search = EditText(this).apply {
            hint = "Uygulama ara…"; isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { query = s.toString().trim(); refresh() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        list = ListView(this).apply {
            this.adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, i, _ ->
                val pkg = shown[i].pkg
                if (!selected.remove(pkg)) selected.add(pkg)
                this@MainActivity.adapter.notifyDataSetChanged(); updateBar()
            }
        }
        btnSet = Button(this).apply { setOnClickListener { askMinutes() } }
        btnClear = Button(this).apply {
            text = "Sınırı kaldır"
            setOnClickListener {
                selected.forEach { Prefs.setLimit(this@MainActivity, it, 0) }
                selected.clear(); refresh()
            }
        }
        bar = LinearLayout(this).apply {
            addView(btnSet, LinearLayout.LayoutParams(0, -2, 1f))
            addView(btnClear, LinearLayout.LayoutParams(0, -2, 1f))
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(perms); addView(tabs); addView(search)
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

    private fun askMinutes() {
        val existing = if (selected.size == 1) Prefs.limits(this)[selected.first()] else null
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Günlük dakika (örn. 60)"
            existing?.let { setText(it.toString()) }
        }
        AlertDialog.Builder(this)
            .setTitle("${selected.size} uygulamaya günlük süre")
            .setView(et)
            .setPositiveButton("Kaydet") { _, _ ->
                val m = et.text.toString().toIntOrNull() ?: 0
                if (m > 0) selected.forEach { Prefs.setLimit(this, it, m) }
                selected.clear(); refresh()
            }
            .setNegativeButton("İptal", null).show()
    }

    private fun refresh() {
        val limits = Prefs.limits(this)
        shown = all.filter {
            (!onlyLimited || limits.containsKey(it.pkg)) && it.name.contains(query, ignoreCase = true)
        }
        tabAll.text = "Tümü (${all.size})"
        tabLim.text = "Sınırlananlar (${limits.size})"
        tabAll.alpha = if (onlyLimited) 0.5f else 1f
        tabLim.alpha = if (onlyLimited) 1f else 0.5f
        adapter.notifyDataSetChanged()
        updateBar()
    }

    private fun updateBar() {
        bar.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
        btnSet.text = "Süre ata (${selected.size})"
    }
}

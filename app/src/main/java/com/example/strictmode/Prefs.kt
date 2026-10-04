package com.example.strictmode

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import java.util.Calendar
import java.util.Locale

object Prefs {
    const val OATH = "Önemli bir nedenim var, yemin ederim."
    const val BYPASS_MIN = 10          // yeminden sonra kaç dk izin
    const val BYPASS_MAX_PER_DAY = 3   // bir uygulama için günlük yemin hakkı
    const val GRACE_SEC = 3            // süre dolunca bildirim → kaç sn sonra yemin ekranı
    const val LOOSEN_HOURS = 24        // süreyi artırma/kaldırma kaç saat sonra geçerli olur
    const val NOTIF_TEXT = "Yo big Harv Wait for me"

    private fun p(c: Context) = c.getSharedPreferences("strict", 0)

    // ---------- yemin metni ----------
    /** Büyük/küçük harf, boşluk, noktalama ve otomatik düzeltmeye takılmadan karşılaştırır. */
    private fun norm(s: String) = s.lowercase(Locale("tr")).filter { it.isLetter() }
    fun oathOk(s: String) = norm(s) == norm(OATH)

    // ---------- limitler ----------
    private fun applyDue(c: Context) {
        val sp = p(c)
        val now = System.currentTimeMillis()
        val e = sp.edit()
        var changed = false
        for ((k, v) in sp.all) {
            if (!k.startsWith("pend:") || v !is String) continue
            val parts = v.split("|")
            val m = parts.getOrNull(0)?.toIntOrNull() ?: continue
            val at = parts.getOrNull(1)?.toLongOrNull() ?: continue
            if (at <= now) {
                val pkg = k.removePrefix("pend:")
                if (m <= 0) e.remove("lim:$pkg") else e.putInt("lim:$pkg", m)
                e.remove(k); changed = true
            }
        }
        if (changed) e.apply()
    }

    fun limits(c: Context): Map<String, Int> {
        applyDue(c)
        val out = HashMap<String, Int>()
        for ((k, v) in p(c).all) if (k.startsWith("lim:") && v is Int) out[k.removePrefix("lim:")] = v
        return out
    }

    /** pkg -> (yeni dakika, 0 = kaldırılacak; uygulanma zamanı) */
    fun pendings(c: Context): Map<String, Pair<Int, Long>> {
        val out = HashMap<String, Pair<Int, Long>>()
        for ((k, v) in p(c).all) {
            if (!k.startsWith("pend:") || v !is String) continue
            val parts = v.split("|")
            val m = parts.getOrNull(0)?.toIntOrNull() ?: continue
            val at = parts.getOrNull(1)?.toLongOrNull() ?: continue
            out[k.removePrefix("pend:")] = m to at
        }
        return out
    }

    /**
     * Süreyi düşürmek / yeni limit koymak anında geçerli; artırmak veya kaldırmak LOOSEN_HOURS sonra.
     * true dönerse değişiklik bekletildi.
     */
    fun requestLimit(c: Context, pkg: String, minutes: Int): Boolean {
        val cur = limits(c)[pkg]
        val e = p(c).edit()
        if (minutes <= 0 && cur == null) { e.remove("pend:$pkg").apply(); return false }
        if (minutes > 0 && (cur == null || minutes <= cur)) {
            e.putInt("lim:$pkg", minutes).remove("pend:$pkg").apply(); return false
        }
        val at = System.currentTimeMillis() + LOOSEN_HOURS * 3_600_000L
        e.putString("pend:$pkg", "${maxOf(minutes, 0)}|$at").apply()
        return true
    }

    // ---------- yemin hakkı ----------
    private fun dayKey() = Calendar.getInstance().let { it.get(Calendar.YEAR) * 1000 + it.get(Calendar.DAY_OF_YEAR) }

    fun bypassesLeft(c: Context, pkg: String): Int {
        val v = p(c).getString("bc:$pkg", null)?.split("|")
        val used = if (v != null && v.size == 2 && v[0].toIntOrNull() == dayKey()) v[1].toIntOrNull() ?: 0 else 0
        return (BYPASS_MAX_PER_DAY - used).coerceAtLeast(0)
    }

    fun grantBypass(c: Context, pkg: String): Boolean {
        val left = bypassesLeft(c, pkg)
        if (left <= 0) return false
        val used = BYPASS_MAX_PER_DAY - left + 1
        p(c).edit()
            .putString("bc:$pkg", "${dayKey()}|$used")
            .putLong("by:$pkg", System.currentTimeMillis() + BYPASS_MIN * 60_000L)
            .apply()
        return true
    }

    // ---------- kullanım süresi ----------
    class Usage(val totals: Map<String, Long>, val fg: String?)

    /**
     * Bugünkü kullanım. Android'in hazır toplamı açık oturumu saymadığı için olay kayıtlarından
     * hesaplanır; açık oturum "şimdi"ye kadar sayılır, ekran kapanınca biter.
     */
    fun usage(c: Context): Usage {
        val now = System.currentTimeMillis()
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val usm = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val ev = usm.queryEvents(start, now)
        val e = UsageEvents.Event()
        val totals = HashMap<String, Long>()
        var cur: String? = null
        var curStart = 0L
        fun close(ts: Long) {
            cur?.let { totals[it] = (totals[it] ?: 0L) + (ts - curStart) }
            cur = null
        }
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            when (e.eventType) {
                1 -> if (cur != e.packageName) { close(e.timeStamp); cur = e.packageName; curStart = e.timeStamp }
                2 -> if (cur == e.packageName) close(e.timeStamp)
                16, 17 -> close(e.timeStamp) // ekran kapandı / kilit
            }
        }
        cur?.let { totals[it] = (totals[it] ?: 0L) + (now - curStart) }
        return Usage(totals, cur)
    }

    /** Süre doldu ve yemin sonrası geçici izin de yok. */
    fun blocked(c: Context, pkg: String): Boolean {
        val lim = limits(c)[pkg] ?: return false
        if (System.currentTimeMillis() <= p(c).getLong("by:$pkg", 0L)) return false
        return (usage(c).totals[pkg] ?: 0L) >= lim * 60_000L
    }
}

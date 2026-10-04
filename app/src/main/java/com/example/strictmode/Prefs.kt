package com.example.strictmode

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import java.util.Calendar

object Prefs {
    const val OATH = "Önemli bir nedenim var, yemin ederim."
    const val BYPASS_MIN = 10          // yeminden sonra kaç dk izin
    const val GRACE_SEC = 3            // süre dolunca bildirim → kaç sn sonra yemin ekranı
    const val NOTIF_TEXT = "Yo big Harv Wait for me"

    private fun p(c: Context) = c.getSharedPreferences("strict", 0)

    fun limits(c: Context): Map<String, Int> =
        p(c).all.filter { it.key.startsWith("lim:") && it.value is Int }
            .map { it.key.removePrefix("lim:") to it.value as Int }.toMap()

    fun setLimit(c: Context, pkg: String, minutes: Int) {
        val e = p(c).edit()
        if (minutes <= 0) e.remove("lim:$pkg") else e.putInt("lim:$pkg", minutes)
        e.apply()
    }

    fun grantBypass(c: Context, pkg: String) =
        p(c).edit().putLong("by:$pkg", System.currentTimeMillis() + BYPASS_MIN * 60_000L).apply()

    class Usage(val totals: Map<String, Long>, val fg: String?)

    /**
     * Bugünkü kullanım süresi. Android'in toplam istatistiği o an AÇIK olan oturumu saymadığı için
     * (limit geç / bazen hiç tetiklenmiyordu) olay kayıtlarından kendimiz hesaplıyoruz;
     * açık oturum "şimdi"ye kadar sayılır, ekran kapanınca oturum biter.
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
                1 -> if (cur != e.packageName) { close(e.timeStamp); cur = e.packageName; curStart = e.timeStamp } // ön plana geldi
                2 -> if (cur == e.packageName) close(e.timeStamp)                                                  // arka plana gitti
                16, 17 -> close(e.timeStamp)                                                                       // ekran kapandı / kilit
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

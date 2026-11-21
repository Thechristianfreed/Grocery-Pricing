package com.freed.grocery

import org.json.JSONObject
import java.util.Locale

/** One advertised price for a staple at a store. */
data class Offer(
    val name: String,
    val price: Double,
    /** "lb" when the ad price is per pound, otherwise "each". */
    val per: String,
    /** Price in the staple's comparison unit, or null when the ad gave no usable size. */
    val unitPrice: Double?,
    val size: String?,
    val note: String,
    val from: String,
    val to: String,
    /** True while the ad is running; false for the last price seen in an older ad. */
    val current: Boolean,
    val more: Int = 0,
)

data class Staple(
    val id: String,
    val name: String,
    val category: String,
    val unit: String,
    val offers: Map<String, Offer>,
) {
    /** Running ads with a comparable unit price, cheapest first. */
    fun ranked(visible: Set<String>): List<Pair<String, Offer>> =
        offers.filter { it.key in visible && it.value.current && it.value.unitPrice != null }
            .toList().sortedBy { it.second.unitPrice }

    fun cheapest(visible: Set<String>): Pair<String, Offer>? = ranked(visible).firstOrNull()
}

data class Store(
    val id: String,
    val name: String,
    val hasAds: Boolean,
    val adTo: String?,
    val adItems: Int,
)

data class Deal(
    val store: String,
    val name: String,
    val price: Double,
    val per: String,
    val note: String,
    val to: String,
    val staple: String?,
)

data class Snapshot(
    val updated: String,
    val area: String,
    val source: String,
    val stores: List<Store>,
    val staples: List<Staple>,
    val deals: List<Deal>,
) {
    fun storeName(id: String) = stores.firstOrNull { it.id == id }?.name ?: id
}

/** A past ad for one staple at one store. */
data class HistoryEntry(
    val store: String,
    val from: String,
    val to: String,
    val name: String,
    val price: Double,
    val per: String,
    val unitPrice: Double?,
)

private fun JSONObject.str(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

private fun JSONObject.num(key: String): Double? =
    if (isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }

object Parser {
    fun snapshot(text: String): Snapshot {
        val root = JSONObject(text)
        val stores = root.getJSONArray("stores").let { arr ->
            List(arr.length()) { i ->
                val s = arr.getJSONObject(i)
                Store(s.getString("id"), s.getString("name"), s.optBoolean("has_ads"),
                    s.str("ad_to"), s.optInt("ad_items"))
            }
        }
        val staples = root.getJSONArray("items").let { arr ->
            List(arr.length()) { i ->
                val it = arr.getJSONObject(i)
                val per = it.getJSONObject("stores")
                val offers = per.keys().asSequence().associateWith { sid ->
                    val o = per.getJSONObject(sid)
                    Offer(
                        name = o.optString("name"), price = o.getDouble("price"),
                        per = o.optString("per", "each"), unitPrice = o.num("unit_price"),
                        size = o.str("size"), note = o.optString("note"),
                        from = o.optString("from"), to = o.optString("to"),
                        current = o.optBoolean("current"), more = o.optInt("more"),
                    )
                }
                Staple(it.getString("id"), it.getString("name"), it.optString("category"),
                    it.optString("unit", "each"), offers)
            }
        }
        val deals = root.getJSONArray("deals").let { arr ->
            List(arr.length()) { i ->
                val d = arr.getJSONObject(i)
                Deal(d.getString("store"), d.optString("name"), d.getDouble("price"),
                    d.optString("per", "each"), d.optString("note"), d.optString("to"), d.str("staple"))
            }
        }
        return Snapshot(root.optString("updated"), root.optString("area"), root.optString("source"),
            stores, staples, deals)
    }

    /** history.json is { stapleId: { storeId: [entries] } }. */
    fun history(text: String): Map<String, List<HistoryEntry>> {
        val root = JSONObject(text)
        return root.keys().asSequence().associateWith { stapleId ->
            val byStore = root.getJSONObject(stapleId)
            byStore.keys().asSequence().flatMap { storeId ->
                val arr = byStore.getJSONArray(storeId)
                (0 until arr.length()).asSequence().map { i ->
                    val e = arr.getJSONObject(i)
                    HistoryEntry(storeId, e.optString("from"), e.optString("to"), e.optString("name"),
                        e.getDouble("price"), e.optString("per", "each"), e.num("unit_price"))
                }
            }.sortedBy { it.from }.toList()
        }
    }
}

object Fmt {
    private val unitSuffix = mapOf(
        "lb" to "/lb", "oz" to "/oz", "floz" to "/fl oz", "gal" to "/gal",
        "doz" to "/doz", "ct" to "/ct", "each" to " ea",
    )

    fun money(v: Double): String =
        if (v < 1.0) String.format(Locale.US, "%.0f¢", v * 100)
        else String.format(Locale.US, "$%.2f", v)

    /** Unit prices under a dollar keep a decimal of a cent (12.5¢/oz) so close ones still differ. */
    fun unitPrice(v: Double, unit: String): String {
        val amount = if (v < 1.0 && unit in setOf("oz", "floz", "ct")) {
            String.format(Locale.US, "%.1f¢", v * 100)
        } else money(v)
        return amount + (unitSuffix[unit] ?: "")
    }

    fun shelf(price: Double, per: String): String = money(price) + if (per == "lb") "/lb" else " ea"

    /** "2026-11-26" -> "Nov 26". Falls back to the raw text. */
    fun day(iso: String): String {
        val p = iso.split("-")
        if (p.size != 3) return iso
        val m = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
            .getOrNull((p[1].toIntOrNull() ?: 0) - 1) ?: return iso
        return "$m ${p[2].trimStart('0')}"
    }
}

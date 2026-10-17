package app.shaqati.core.dedupe

import app.shaqati.core.model.Listing
import app.shaqati.core.model.Resolved
import app.shaqati.core.text.ArabicText
import app.shaqati.core.util.Geo
import kotlin.math.abs

/**
 * Links the same apartment advertised on several sites (or reposted on one).
 * Only strong evidence links listings: the same advertising licence, or the
 * same advertiser phone with the same layout, price and location.
 */
object Dedupe {
    data class Item(val listing: Listing, val resolved: Resolved)

    /** Returns listing id → group id (the id of the most complete listing in the group). */
    fun group(items: List<Item>): Map<String, String> {
        val parent = HashMap<String, String>()
        fun find(x: String): String {
            var r = x
            while (parent[r] != r) r = parent[r]!!
            var c = x
            while (parent[c] != r) { val n = parent[c]!!; parent[c] = r; c = n }
            return r
        }
        fun union(a: String, b: String) {
            val ra = find(a); val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }
        items.forEach { parent[it.listing.id] = it.listing.id }

        items.groupBy { licence(it.listing) }.forEach { (lic, group) ->
            if (lic != null && group.size > 1) group.zipWithNext { a, b -> union(a.listing.id, b.listing.id) }
        }
        val byPhone = items.filter { phone(it.listing) != null }.groupBy { phone(it.listing)!! }
        for ((_, group) in byPhone) {
            if (group.size < 2) continue
            for (i in group.indices) for (j in i + 1 until group.size) {
                if (sameUnit(group[i], group[j])) union(group[i].listing.id, group[j].listing.id)
            }
        }

        val groups = items.groupBy { find(it.listing.id) }
        val out = HashMap<String, String>()
        for ((_, members) in groups) {
            val best = members.maxBy { completeness(it.listing) }.listing.id
            members.forEach { out[it.listing.id] = best }
        }
        return out
    }

    private fun sameUnit(a: Item, b: Item): Boolean {
        if (a.listing.id == b.listing.id) return true
        val ra = a.resolved; val rb = b.resolved
        if (ra.bedrooms == null || ra.bedrooms != rb.bedrooms) return false
        if (ra.bathrooms != null && rb.bathrooms != null && ra.bathrooms != rb.bathrooms) return false
        val pa = ra.annualRent; val pb = rb.annualRent
        if (pa == null || pb == null || abs(pa - pb) > 0.03 * maxOf(pa, pb)) return false
        val la = a.listing.location; val lb = b.listing.location
        if (la != null && lb != null) return Geo.distanceKm(la, lb) <= 0.35
        val da = ArabicText.normalize(a.listing.district); val db = ArabicText.normalize(b.listing.district)
        return da.isNotEmpty() && da == db
    }

    fun licence(l: Listing): String? = l.licenseNumber?.filter { it.isDigit() }?.takeIf { it.length >= 6 }

    /** Saudi mobile numbers normalized to 9 digits starting with 5. */
    fun phone(l: Listing): String? {
        val d = ArabicText.asciiDigits(l.phone ?: l.whatsapp ?: return null).filter { it.isDigit() }
        val n = when {
            d.startsWith("9665") && d.length == 12 -> d.substring(3)
            d.startsWith("009665") && d.length == 14 -> d.substring(5)
            d.startsWith("05") && d.length == 10 -> d.substring(1)
            d.startsWith("5") && d.length == 9 -> d
            else -> return null
        }
        return n.takeIf { !it.contains("XXXX") }
    }

    private fun completeness(l: Listing): Int =
        l.photos.size.coerceAtMost(15) * 2 + (if (l.location != null) 10 else 0) +
            (if (l.phone != null) 5 else 0) + l.description.length.coerceAtMost(1500) / 100 +
            (if (l.bathrooms != null) 3 else 0) + (if (l.price != null) 3 else 0)
}

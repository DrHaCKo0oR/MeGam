package app.shaqati.core.sources

import app.shaqati.core.model.GeoPoint
import app.shaqati.core.model.RentPeriod
import app.shaqati.core.text.ArabicText
import app.shaqati.core.util.Geo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

internal val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** Null-safe navigation over kotlinx JSON trees. */
internal operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)
internal operator fun JsonElement?.get(index: Int): JsonElement? = (this as? JsonArray)?.getOrNull(index)
internal val JsonElement?.str: String?
    get() = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull?.takeIf { it.isNotBlank() && it != "\$undefined" }
internal val JsonElement?.dbl: Double? get() = (this as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.replace(",", "")?.toDoubleOrNull() }
internal val JsonElement?.int: Int? get() = dbl?.toInt()
internal val JsonElement?.lng: Long? get() = (this as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
internal val JsonElement?.bool: Boolean?
    get() = (this as? JsonPrimitive)?.let { p ->
        p.booleanOrNull ?: when (p.contentOrNull?.lowercase()) {
            "1", "yes", "true" -> true
            "0", "no", "false" -> false
            else -> null
        }
    }
internal val JsonElement?.arr: List<JsonElement> get() = (this as? JsonArray) ?: emptyList()

/** Next.js App Router pages stream their data as `self.__next_f.push([1,"…"])` chunks. */
object RscPayload {
    private const val PUSH = "self.__next_f.push([1,\""

    /** Concatenates and unescapes the string chunks (scanned by hand: a regex overflows the stack on large pages). */
    fun decode(html: String): String {
        val sb = StringBuilder()
        var i = html.indexOf(PUSH)
        while (i >= 0) {
            val start = i + PUSH.length
            var j = start
            while (j < html.length && html[j] != '"') {
                if (html[j] == '\\') j++
                j++
            }
            if (j >= html.length) break
            runCatching { lenientJson.decodeFromString<String>("\"" + html.substring(start, j) + "\"") }.onSuccess { sb.append(it) }
            i = html.indexOf(PUSH, j)
        }
        return sb.toString()
    }

    /**
     * Returns the JSON value that follows [key] (for example `"listings":`) in the payload,
     * using a bracket-aware scan so the rest of the stream is ignored.
     */
    fun valueAfter(payload: String, key: String, from: Int = 0): JsonElement? {
        val i = payload.indexOf(key, from)
        if (i < 0) return null
        val start = i + key.length
        val end = matchingEnd(payload, start) ?: return null
        return runCatching { lenientJson.parseToJsonElement(payload.substring(start, end)) }.getOrNull()
    }

    /** All `T`-records (`id:T<hex>,<text>`): text chunks referenced as `$id` elsewhere. */
    fun textRecords(payload: String): Map<String, String> {
        val out = HashMap<String, String>()
        val rec = Regex("""(?m)^([0-9a-f]+):T([0-9a-f]+),""")
        for (m in rec.findAll(payload)) {
            val id = m.groupValues[1]
            val len = m.groupValues[2].toInt(16)
            val start = m.range.last + 1
            // Length is in UTF-8 bytes; walk characters until the byte budget is spent.
            var bytes = 0
            var j = start
            while (j < payload.length && bytes < len) {
                val c = payload[j]
                bytes += when {
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    Character.isHighSurrogate(c) -> { j++; 4 }
                    else -> 3
                }
                j++
            }
            out[id] = payload.substring(start, j.coerceAtMost(payload.length))
        }
        return out
    }

    private fun matchingEnd(s: String, start: Int): Int? {
        var i = start
        while (i < s.length && s[i].isWhitespace()) i++
        if (i >= s.length) return null
        val open = s[i]
        if (open != '[' && open != '{') {
            // Scalar: read until a delimiter.
            var j = i
            if (s[j] == '"') {
                j++
                while (j < s.length && s[j] != '"') { if (s[j] == '\\') j++; j++ }
                return j + 1
            }
            while (j < s.length && s[j] !in ",}]") j++
            return j
        }
        var depth = 0
        var inStr = false
        var j = i
        while (j < s.length) {
            val c = s[j]
            if (inStr) {
                if (c == '\\') j++ else if (c == '"') inStr = false
            } else when (c) {
                '"' -> inStr = true
                '[', '{' -> depth++
                ']', '}' -> { depth--; if (depth == 0) return j + 1 }
            }
            j++
        }
        return null
    }
}

/** Pages Router data: `<script id="__NEXT_DATA__" type="application/json">…</script>`. */
object NextData {
    private val script = Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

    fun extract(html: String): JsonElement? =
        script.find(html)?.groupValues?.get(1)?.let { runCatching { lenientJson.parseToJsonElement(it) }.getOrNull() }
}

object Geohash {
    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

    fun decode(hash: String): GeoPoint? {
        if (hash.isBlank()) return null
        var latLo = -90.0; var latHi = 90.0
        var lngLo = -180.0; var lngHi = 180.0
        var even = true
        for (ch in hash.lowercase()) {
            val v = BASE32.indexOf(ch)
            if (v < 0) return null
            for (bit in 4 downTo 0) {
                val on = (v shr bit) and 1 == 1
                if (even) {
                    val mid = (lngLo + lngHi) / 2
                    if (on) lngLo = mid else lngHi = mid
                } else {
                    val mid = (latLo + latHi) / 2
                    if (on) latLo = mid else latHi = mid
                }
                even = !even
            }
        }
        return GeoPoint((latLo + latHi) / 2, (lngLo + lngHi) / 2)
    }
}

/** Prices written in ad text: "45 ألف سنوي", "الايجار : 2500 شهرياً", "٤٥٬٠٠٠ ريال". */
object PriceText {
    data class Found(val amount: Double, val period: RentPeriod)

    private val num = """(\d{1,3}(?:[,.]\d{3})+|\d+(?:\.\d+)?)"""
    private val thousand = Regex("""$num\s*(?:الف|ألف|آلاف|الاف|k\b)""")
    private val keyed = Regex("""(?:السعر|الايجار|الإيجار|ايجار|إيجار|سعر الوحده|سعر الوحدة|الاجار|المبلغ|price|rent)\s*(?:السنوي|الشهري)?\s*[:：\-]?\s*$num""")
    private val withCurrency = Regex("""$num\s*(?:ريال|رس|ر\.س|sar|﷼)""")
    private val afterPeriod = Regex("""(?:شهري|شهريا|سنوي|سنويا|سعرها|سعره|بسعر)\s*[:：\-]?\s*(\d{1,3}(?:[,.]\d{3})+|\d{4,6})(?!\d)""")
    private val attachedBa = Regex("""(?<![\p{L}\d])ب\s?(\d{1,3}(?:[,.]\d{3})+|\d{4,6})(?!\d)""")

    fun extract(raw: String?): Found? {
        if (raw.isNullOrBlank()) return null
        val t = ArabicText.normalize(raw)
        val candidates = mutableListOf<Pair<Double, Int>>() // amount, position
        thousand.findAll(t).forEach { m -> parseNum(m.groupValues[1])?.let { candidates += it * 1000 to m.range.first } }
        keyed.findAll(t).forEach { m -> parseNum(m.groupValues[1])?.let { candidates += it to m.range.first } }
        withCurrency.findAll(t).forEach { m -> parseNum(m.groupValues[1])?.let { candidates += it to m.range.first } }
        afterPeriod.findAll(t).forEach { m -> parseNum(m.groupValues[1])?.let { candidates += it to m.range.first } }
        attachedBa.findAll(t).forEach { m -> parseNum(m.groupValues[1])?.let { candidates += it to m.range.first } }
        val plausible = candidates.filter { it.first in 800.0..400_000.0 }
        val best = plausible.minByOrNull { it.second } ?: return null
        val around = t.substring((best.second - 25).coerceAtLeast(0), (best.second + 45).coerceAtMost(t.length))
        val period = when {
            Regex("شهري|شهريا|بالشهر|/ ?شهر|monthly").containsMatchIn(around) -> RentPeriod.MONTHLY
            Regex("سنوي|سنويا|بالسنه|/ ?سنه|yearly|annual").containsMatchIn(around) -> RentPeriod.YEARLY
            else -> RentPeriod.UNKNOWN
        }
        return Found(best.first, period)
    }

    private fun parseNum(s: String): Double? {
        val clean = if (Regex("""\d{1,3}(?:[,.]\d{3})+""").matches(s)) s.replace(",", "").replace(".", "") else s.replace(",", "")
        return clean.toDoubleOrNull()
    }
}

/** Google Maps links inside ad text ("maps.google.com/?q=24.85,46.83"). */
object MapLink {
    private val q = Regex("""(?:q=|@|ll=|query=)(-?\d{1,2}\.\d{3,}),\s*(-?\d{1,3}\.\d{3,})""")

    fun find(text: String?): GeoPoint? {
        val m = q.find(text ?: return null) ?: return null
        val p = runCatching { GeoPoint(m.groupValues[1].toDouble(), m.groupValues[2].toDouble()) }.getOrNull() ?: return null
        return p.takeIf { Geo.inRiyadh(it) }
    }
}

/** REGA licences count every room; one of them is usually the living room. */
object RegaRooms {
    fun toBedrooms(rooms: Int): Int? = when {
        rooms !in 1..12 -> null
        rooms == 1 -> 1
        else -> rooms - 1
    }
}

/** Ads that ask for an apartment rather than offer one. */
object WantedAd {
    private val wanted = Regex(
        "^\\s*(?:السلام عليكم(?: ورحمه الله(?: وبركاته)?)?[\\s,.،!]*)?(مطلوب|مطلوووب|ابحث|أبحث|احتاج|أحتاج|محتاج|محتاجه|ودي|نبي|ابغى|أبغى|ابي|wanted|looking for)"
    )
    fun matches(title: String?): Boolean = wanted.containsMatchIn(ArabicText.normalizeLetters(title ?: ""))
}

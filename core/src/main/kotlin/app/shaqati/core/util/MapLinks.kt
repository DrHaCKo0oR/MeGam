package app.shaqati.core.util

import app.shaqati.core.model.GeoPoint
import java.net.URLDecoder

/** Reads coordinates from Google Maps links ("Share" links, place pages, dropped pins). */
object MapLinks {
    private val num = """(-?\d{1,3}\.\d{3,})"""
    // Most precise first: the place itself, then query parameters, then the viewport centre.
    private val patterns = listOf(
        Regex("""!3d$num!4d$num"""),
        Regex("""[?&](?:q|query|ll|destination|daddr|center)=(?:loc:)?$num,\s*$num"""),
        Regex("""/search/$num,\s*\+?$num"""),
        Regex("""@$num,$num"""),
        Regex("""^\s*$num\s*,\s*$num\s*$"""),
    )

    fun parse(text: String?): GeoPoint? {
        if (text.isNullOrBlank()) return null
        val t = runCatching { URLDecoder.decode(text, "UTF-8") }.getOrDefault(text)
        for (p in patterns) {
            val m = p.find(t) ?: continue
            val lat = m.groupValues[1].toDoubleOrNull() ?: continue
            val lng = m.groupValues[2].toDoubleOrNull() ?: continue
            if (lat in -90.0..90.0 && lng in -180.0..180.0) return GeoPoint(lat, lng)
        }
        return null
    }

    /** Short links (maps.app.goo.gl, goo.gl/maps) must be opened to see the coordinates. */
    fun isShortLink(text: String): Boolean = Regex("""https?://(maps\.app\.goo\.gl|goo\.gl/maps)/""").containsMatchIn(text)

    fun firstUrl(text: String): String? = Regex("""https?://\S+""").find(text)?.value
}

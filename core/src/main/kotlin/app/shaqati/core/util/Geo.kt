package app.shaqati.core.util

import app.shaqati.core.model.GeoPoint
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val EARTH_RADIUS_KM = 6371.0088

    fun distanceKm(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).pow(2)
        return 2 * EARTH_RADIUS_KM * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Rounds to ~11 m so nearby points share a cache entry. */
    fun cellKey(p: GeoPoint, decimals: Int = 4): String =
        "%.${decimals}f,%.${decimals}f".format(java.util.Locale.ROOT, p.lat, p.lng)

    /** Riyadh city bounds, generous. Used to reject obviously wrong coordinates. */
    fun inRiyadh(p: GeoPoint): Boolean = p.lat in 24.25..25.25 && p.lng in 46.25..47.25
}

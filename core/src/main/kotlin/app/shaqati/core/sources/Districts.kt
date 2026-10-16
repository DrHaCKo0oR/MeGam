package app.shaqati.core.sources

import app.shaqati.core.model.GeoPoint
import app.shaqati.core.text.ArabicText
import app.shaqati.core.util.Geo
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
data class District(val ar: String, val en: String, val lat: Double, val lng: Double, val n: Int = 0) {
    val center: GeoPoint get() = GeoPoint(lat, lng)
}

/** Riyadh neighbourhood centroids (bundled), used to pick search areas and place ads that only name a district. */
object Districts {
    val all: List<District> by lazy {
        val text = Districts::class.java.getResourceAsStream("/shaqati/riyadh_districts.json")!!.bufferedReader().use { it.readText() }
        lenientJson.decodeFromString(ListSerializer(District.serializer()), text)
    }

    private val byKey: Map<String, District> by lazy { all.associateBy { key(it.ar) } }

    /** Normalized lookup key: no "حي" prefix, folded letters, no spaces or hamza variants. */
    fun key(name: String): String =
        ArabicText.normalize(name).removePrefix("حي ").removePrefix("حي-").replace("-", " ").replace(" ", "").replace("ء", "")

    fun find(name: String?): District? = name?.let { byKey[key(it)] }

    fun near(point: GeoPoint, radiusKm: Double): List<District> =
        all.map { it to Geo.distanceKm(point, it.center) }.filter { it.second <= radiusKm }.sortedBy { it.second }.map { it.first }
}

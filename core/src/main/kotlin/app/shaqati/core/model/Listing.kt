package app.shaqati.core.model

import kotlinx.serialization.Serializable

/** Listing sites the app reads. [key] is stable and used in listing ids. */
@Serializable
enum class Source(val key: String, val nameEn: String, val nameAr: String) {
    AQAR("aqar", "Aqar", "عقار"),
    BAYUT("bayut", "Bayut", "بيوت"),
    PROPERTY_FINDER("pf", "Property Finder", "بروبرتي فايندر"),
    WASALT("wasalt", "Wasalt", "وصلت"),
    HARAJ("haraj", "Haraj", "حراج"),
    DEAL("deal", "Deal", "ديل"),
    OPENSOOQ("opensooq", "OpenSooq", "السوق المفتوح");

    companion object {
        fun fromKey(key: String): Source? = entries.firstOrNull { it.key == key }
    }
}

@Serializable
data class GeoPoint(val lat: Double, val lng: Double) {
    init {
        require(lat in -90.0..90.0 && lng in -180.0..180.0) { "invalid coordinates $lat,$lng" }
    }

    override fun toString(): String = "%.6f,%.6f".format(java.util.Locale.ROOT, lat, lng)
}

@Serializable
enum class LocationPrecision { EXACT, APPROXIMATE, DISTRICT, UNKNOWN }

@Serializable
enum class RentPeriod { YEARLY, SEMI_ANNUAL, MONTHLY, DAILY, UNKNOWN }

@Serializable
enum class Furnishing { UNFURNISHED, SEMI_FURNISHED, FURNISHED, UNKNOWN }

@Serializable
enum class Audience { FAMILIES, SINGLES, ANY, UNKNOWN }

@Serializable
enum class PropertyKind { APARTMENT, FLOOR, ANNEX, VILLA, STUDIO, ROOM, OTHER }

/**
 * One advertisement as published by one site, normalized into a common shape.
 * Unknown values stay null/UNKNOWN; they are never guessed silently.
 */
@Serializable
data class Listing(
    val source: Source,
    val sourceId: String,
    val url: String,
    val title: String,
    val description: String = "",
    val kind: PropertyKind = PropertyKind.APARTMENT,
    /** Price as advertised, in SAR, for [period]. */
    val price: Double? = null,
    val period: RentPeriod = RentPeriod.UNKNOWN,
    val bedrooms: Int? = null,
    val livingRooms: Int? = null,
    val bathrooms: Int? = null,
    /** True when the ad lists a majlis / guest room in addition to bedrooms. */
    val hasMajlis: Boolean? = null,
    val kitchen: Boolean? = null,
    val kitchenFitted: Boolean? = null,
    val areaSqm: Double? = null,
    val floor: Int? = null,
    val buildingAgeYears: Int? = null,
    val furnishing: Furnishing = Furnishing.UNKNOWN,
    val audience: Audience = Audience.UNKNOWN,
    val city: String? = null,
    val district: String? = null,
    val location: GeoPoint? = null,
    val locationPrecision: LocationPrecision = LocationPrecision.UNKNOWN,
    val photos: List<String> = emptyList(),
    val videos: List<String> = emptyList(),
    val advertiserName: String? = null,
    val advertiserKind: String? = null,
    val phone: String? = null,
    val whatsapp: String? = null,
    /** REGA advertising licence number when published; links the same ad across sites. */
    val licenseNumber: String? = null,
    val postedAt: Long? = null,
    val updatedAt: Long? = null,
    val features: Set<String> = emptySet(),
) {
    val id: String get() = "${source.key}:$sourceId"
}

/** Feature tags used across sources. */
object Feature {
    const val ELEVATOR = "elevator"
    const val PARKING = "parking"
    const val AC = "ac"
    const val KITCHEN_FITTED = "kitchen_fitted"
    const val MAID_ROOM = "maid_room"
    const val DRIVER_ROOM = "driver_room"
    const val PRIVATE_ENTRANCE = "private_entrance"
    const val SEPARATE_METER = "separate_meter"
    const val ROOF = "roof"
    const val YARD = "yard"
    const val POOL = "pool"
    const val STORAGE = "storage"
    const val BALCONY = "balcony"
    const val SMART_HOME = "smart_home"
    const val NEW_BUILDING = "new_building"
}

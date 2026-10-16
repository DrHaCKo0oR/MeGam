package app.shaqati.core.filter

import app.shaqati.core.model.Audience
import app.shaqati.core.model.Furnishing
import app.shaqati.core.model.Listing
import app.shaqati.core.model.PropertyKind
import app.shaqati.core.model.Resolved
import app.shaqati.core.text.ArabicText
import kotlinx.serialization.Serializable

/** The user's hard requirements. Defaults are the agreed search. */
@Serializable
data class Criteria(
    val maxAnnualRent: Double = 50_000.0,
    /** Bedrooms + guest majlis: one bedroom plus at least one extra room. */
    val minRooms: Int = 2,
    val minLivingRooms: Int = 1,
    val minBathrooms: Int = 2,
    val requireKitchen: Boolean = true,
    val allowFurnished: Boolean = false,
    val maxMorningMinutes: Int = 40,
    val maxEveningMinutes: Int? = null,
    val excludeSinglesOnly: Boolean = true,
    val maxListingAgeDays: Int = 75,
    val allowedKinds: Set<PropertyKind> = setOf(PropertyKind.APARTMENT, PropertyKind.FLOOR, PropertyKind.ANNEX, PropertyKind.OTHER),
    val excludedDistricts: Set<String> = emptySet(),
    val city: String = "الرياض",
    /** When false, listings with an unknown bathroom/room count are kept but flagged. */
    val strictUnknowns: Boolean = false,
)

@Serializable
enum class Check { RENT, CITY, ROOMS, LIVING, BATHROOMS, KITCHEN, FURNISHING, AUDIENCE, COMMUTE_AM, COMMUTE_PM, AGE, KIND, DISTRICT, PRICE_PERIOD }

@Serializable
data class Evaluation(
    val passes: Boolean,
    val failed: List<Check>,
    /** Requirements the ad does not state; the listing is kept and labelled. */
    val unconfirmed: List<Check>,
)

object Evaluator {
    fun evaluate(
        listing: Listing,
        r: Resolved,
        c: Criteria,
        morningMinutes: Int?,
        eveningMinutes: Int?,
        now: Long,
    ): Evaluation {
        val failed = mutableListOf<Check>()
        val unknown = mutableListOf<Check>()

        // Budget: a price must be known and convertible to a yearly amount.
        when {
            r.annualRent == null && listing.price == null -> unknown += Check.RENT
            r.annualRent == null -> unknown += Check.PRICE_PERIOD
            r.annualRent > c.maxAnnualRent + 0.5 -> failed += Check.RENT
        }

        val city = ArabicText.normalize(listing.city)
        if (city.isNotEmpty() && !cityMatches(city, ArabicText.normalize(c.city))) failed += Check.CITY

        if (listing.kind !in c.allowedKinds) failed += Check.KIND

        val rooms = r.bedrooms?.let { it + if (r.hasMajlis && (r.livingRooms ?: 0) <= 1) 1 else 0 }
        when {
            rooms == null -> unknown += Check.ROOMS
            rooms < c.minRooms -> failed += Check.ROOMS
        }
        when {
            r.livingRooms == null -> unknown += Check.LIVING
            r.livingRooms < c.minLivingRooms -> failed += Check.LIVING
        }
        when {
            r.bathrooms == null -> unknown += Check.BATHROOMS
            r.bathrooms < c.minBathrooms -> failed += Check.BATHROOMS
        }
        // Practically every Riyadh apartment has a kitchen room (often empty); only an
        // explicit "no kitchen" fails, and an unstated kitchen is not worth a warning.
        if (c.requireKitchen && r.kitchen == false) failed += Check.KITCHEN
        when (r.furnishing) {
            Furnishing.FURNISHED -> if (!c.allowFurnished) failed += Check.FURNISHING
            Furnishing.UNKNOWN -> unknown += Check.FURNISHING
            else -> Unit
        }
        if (c.excludeSinglesOnly && r.audience == Audience.SINGLES) failed += Check.AUDIENCE

        when {
            morningMinutes == null -> unknown += Check.COMMUTE_AM
            morningMinutes > c.maxMorningMinutes -> failed += Check.COMMUTE_AM
        }
        val pmLimit = c.maxEveningMinutes
        if (pmLimit != null) {
            when {
                eveningMinutes == null -> unknown += Check.COMMUTE_PM
                eveningMinutes > pmLimit -> failed += Check.COMMUTE_PM
            }
        }

        val stamp = listing.updatedAt ?: listing.postedAt
        if (stamp != null && now - stamp > c.maxListingAgeDays * DAY_MS) failed += Check.AGE

        val district = ArabicText.normalize(listing.district)
        if (district.isNotEmpty() && c.excludedDistricts.any { ArabicText.normalize(it) == district }) failed += Check.DISTRICT

        if (c.strictUnknowns) {
            failed += unknown.filter { it in setOf(Check.ROOMS, Check.BATHROOMS, Check.RENT, Check.PRICE_PERIOD) }
        }
        return Evaluation(passes = failed.isEmpty(), failed = failed.distinct(), unconfirmed = unknown.distinct())
    }

    private fun cityMatches(city: String, wanted: String): Boolean =
        city.contains(wanted) || wanted.contains(city) ||
            (wanted == "الرياض" && (city.contains("riyadh") || city.contains("رياض")))

    const val DAY_MS = 86_400_000L
}

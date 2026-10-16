package app.shaqati.core.model

import app.shaqati.core.text.TextFacts
import app.shaqati.core.text.TextSignals
import kotlinx.serialization.Serializable

/**
 * A listing with every field resolved from structured data first and ad text
 * second, plus where each value came from.
 */
@Serializable
data class Resolved(
    val annualRent: Double?,
    val monthlyRent: Double?,
    val period: RentPeriod,
    val periodInferred: Boolean,
    val bedrooms: Int?,
    val livingRooms: Int?,
    val bathrooms: Int?,
    val extraRooms: Int?,
    val hasMajlis: Boolean,
    val kitchen: Boolean?,
    val kitchenFitted: Boolean?,
    val furnishing: Furnishing,
    val audience: Audience,
    val features: Set<String>,
    val text: TextFacts,
)

object Resolver {
    /** Riyadh rents: below this an unlabelled price is almost certainly monthly. */
    private const val MONTHLY_CEILING = 9_000.0

    /** Above this an unlabelled price is almost certainly yearly. */
    private const val YEARLY_FLOOR = 14_000.0

    /** No Riyadh apartment rents for less than this per month. */
    private const val MIN_PRICE = 700.0

    fun resolve(l: Listing): Resolved {
        val facts = TextSignals.analyze(l.title, l.description)

        var period = l.period
        var inferred = false
        if (period == RentPeriod.UNKNOWN) {
            period = facts.period
            inferred = period != RentPeriod.UNKNOWN
        }
        // Amounts under a few hundred riyals are typos or prices in thousands; treat them as missing.
        val price = l.price?.takeIf { it >= MIN_PRICE }
        if (period == RentPeriod.UNKNOWN && price != null) {
            period = when {
                price <= MONTHLY_CEILING -> RentPeriod.MONTHLY
                price >= YEARLY_FLOOR -> RentPeriod.YEARLY
                else -> RentPeriod.UNKNOWN
            }
            inferred = period != RentPeriod.UNKNOWN
        }
        // A "yearly" price under the monthly ceiling is a monthly price published in the wrong field,
        // and an apartment "monthly" price in the tens of thousands is a yearly price.
        if (period == RentPeriod.YEARLY && price != null && price <= MONTHLY_CEILING) {
            period = RentPeriod.MONTHLY
            inferred = true
        } else if (period == RentPeriod.MONTHLY && price != null && price >= 20_000.0) {
            period = RentPeriod.YEARLY
            inferred = true
        }
        val annual = price?.let {
            when (period) {
                RentPeriod.YEARLY -> it
                RentPeriod.SEMI_ANNUAL -> it * 2
                RentPeriod.MONTHLY -> it * 12
                RentPeriod.DAILY, RentPeriod.UNKNOWN -> null
            }
        }

        val bedrooms = l.bedrooms ?: facts.bedrooms
        val living = l.livingRooms ?: facts.livingRooms
        val baths = l.bathrooms ?: facts.bathrooms
        val majlis = l.hasMajlis ?: facts.hasMajlis
        val extra = bedrooms?.let { b -> (b - 1).coerceAtLeast(0) + if (majlis && (living ?: 0) <= 1) 1 else 0 }

        val kitchen = when {
            facts.noKitchen -> false
            l.kitchen != null -> l.kitchen
            facts.kitchenMentioned -> true
            else -> null
        }
        val fitted = l.kitchenFitted ?: facts.kitchenFitted

        val furnishing = if (l.furnishing != Furnishing.UNKNOWN) l.furnishing else facts.furnishing
        val audience = if (l.audience != Audience.UNKNOWN) l.audience else facts.audience

        return Resolved(
            annualRent = annual,
            monthlyRent = annual?.div(12),
            period = period,
            periodInferred = inferred,
            bedrooms = bedrooms,
            livingRooms = living,
            bathrooms = baths,
            extraRooms = extra,
            hasMajlis = majlis,
            kitchen = kitchen,
            kitchenFitted = fitted,
            furnishing = furnishing,
            audience = audience,
            features = l.features + facts.features,
            text = facts,
        )
    }
}

package app.shaqati.core

import app.shaqati.core.filter.Check
import app.shaqati.core.filter.Criteria
import app.shaqati.core.filter.Evaluator
import app.shaqati.core.model.Audience
import app.shaqati.core.model.Furnishing
import app.shaqati.core.model.Listing
import app.shaqati.core.model.RentPeriod
import app.shaqati.core.model.Resolver
import app.shaqati.core.model.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResolveEvaluateTest {
    private val now = 1_790_000_000_000L

    private fun listing(
        price: Double? = 45_000.0,
        period: RentPeriod = RentPeriod.YEARLY,
        bedrooms: Int? = 2,
        living: Int? = 1,
        baths: Int? = 2,
        title: String = "شقة للإيجار",
        description: String = "",
        furnishing: Furnishing = Furnishing.UNFURNISHED,
    ) = Listing(
        source = Source.AQAR, sourceId = "1", url = "https://example", title = title, description = description,
        price = price, period = period, bedrooms = bedrooms, livingRooms = living, bathrooms = baths,
        furnishing = furnishing, city = "الرياض", postedAt = now - 86_400_000L,
    )

    private fun eval(l: Listing, am: Int? = 25, c: Criteria = Criteria()) =
        Evaluator.evaluate(l, Resolver.resolve(l), c, am, am, now)

    @Test
    fun `monthly price is converted to yearly`() {
        val r = Resolver.resolve(listing(price = 3_800.0, period = RentPeriod.MONTHLY))
        assertEquals(45_600.0, r.annualRent)
        assertEquals(3_800.0, r.monthlyRent)
    }

    @Test
    fun `unlabelled small price is treated as monthly, large as yearly`() {
        assertEquals(RentPeriod.MONTHLY, Resolver.resolve(listing(price = 4_000.0, period = RentPeriod.UNKNOWN)).period)
        assertEquals(RentPeriod.YEARLY, Resolver.resolve(listing(price = 42_000.0, period = RentPeriod.UNKNOWN)).period)
        assertEquals(null, Resolver.resolve(listing(price = 11_000.0, period = RentPeriod.UNKNOWN)).annualRent)
    }

    @Test
    fun `mislabelled periods are corrected`() {
        assertEquals(RentPeriod.MONTHLY, Resolver.resolve(listing(price = 3_500.0, period = RentPeriod.YEARLY)).period)
        assertEquals(RentPeriod.YEARLY, Resolver.resolve(listing(price = 40_000.0, period = RentPeriod.MONTHLY)).period)
    }

    @Test
    fun `good listing passes`() {
        val e = eval(listing())
        assertTrue(e.passes, e.toString())
        assertTrue(e.unconfirmed.isEmpty(), e.toString())
    }

    @Test
    fun `budget is a hard limit at 50k`() {
        assertTrue(eval(listing(price = 50_000.0)).passes)
        assertEquals(listOf(Check.RENT), eval(listing(price = 52_000.0)).failed)
        assertEquals(listOf(Check.RENT), eval(listing(price = 4_400.0, period = RentPeriod.MONTHLY)).failed)
    }

    @Test
    fun `one bedroom without extra room fails, majlis counts as the extra room`() {
        assertEquals(listOf(Check.ROOMS), eval(listing(bedrooms = 1)).failed)
        assertTrue(eval(listing(bedrooms = 1, description = "غرفة نوم ومجلس وصالة")).passes)
    }

    @Test
    fun `needs two bathrooms`() {
        assertEquals(listOf(Check.BATHROOMS), eval(listing(baths = 1)).failed)
    }

    @Test
    fun `fully furnished is excluded, semi-furnished is fine`() {
        assertEquals(listOf(Check.FURNISHING), eval(listing(furnishing = Furnishing.FURNISHED)).failed)
        assertTrue(eval(listing(furnishing = Furnishing.SEMI_FURNISHED)).passes)
    }

    @Test
    fun `commute over 40 minutes fails, unknown commute is unconfirmed`() {
        assertEquals(listOf(Check.COMMUTE_AM), eval(listing(), am = 46).failed)
        val e = eval(listing(), am = null)
        assertTrue(e.passes)
        assertTrue(Check.COMMUTE_AM in e.unconfirmed)
    }

    @Test
    fun `missing details come from the description`() {
        val l = listing(bedrooms = null, baths = null, living = null, description = "3 غرف وصالة و 2 دورة مياه")
        val e = eval(l)
        assertTrue(e.passes, e.toString())
        assertFalse(Check.BATHROOMS in e.unconfirmed)
    }

    @Test
    fun `singles-only buildings are excluded`() {
        val r = Resolver.resolve(listing(description = "للعزاب فقط"))
        assertEquals(Audience.SINGLES, r.audience)
        assertEquals(listOf(Check.AUDIENCE), eval(listing(description = "للعزاب فقط")).failed)
    }

    @Test
    fun `stale listings are excluded`() {
        val old = listing().copy(postedAt = now - 120 * 86_400_000L)
        assertEquals(listOf(Check.AGE), eval(old).failed)
    }
}

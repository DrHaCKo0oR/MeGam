package app.shaqati.core

import app.shaqati.core.model.Audience
import app.shaqati.core.model.Feature
import app.shaqati.core.model.Furnishing
import app.shaqati.core.model.LocationPrecision
import app.shaqati.core.model.PropertyKind
import app.shaqati.core.model.RentPeriod
import app.shaqati.core.model.Resolver
import app.shaqati.core.sources.AqarSource
import app.shaqati.core.sources.BayutSource
import app.shaqati.core.sources.BotCheck
import app.shaqati.core.sources.DealSource
import app.shaqati.core.sources.Districts
import app.shaqati.core.sources.HarajSource
import app.shaqati.core.sources.OpenSooqSource
import app.shaqati.core.sources.PriceText
import app.shaqati.core.sources.PropertyFinderSource
import app.shaqati.core.sources.WantedAd
import app.shaqati.core.sources.WasaltSource
import app.shaqati.core.util.Geo
import app.shaqati.core.util.HttpResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Parsers against responses recorded from the live sites (phone numbers masked). */
class SourceFixturesTest {
    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/fixtures/$name")) { "missing fixture $name" }.readText()

    @Test
    fun `aqar search response`() {
        val (items, total) = AqarSource.parseSearch(fixture("aqar-search.json"))
        assertEquals(497, total)
        assertEquals(12, items.size)
        val a = items.first { it.sourceId == "6906410" }
        // 8,000 with a 12-payment total of 96,000 is a monthly price.
        assertEquals(RentPeriod.MONTHLY, a.period)
        assertEquals(96_000.0, Resolver.resolve(a).annualRent)
        assertEquals(3, a.bedrooms)
        assertEquals(1, a.livingRooms)
        assertEquals(4, a.bathrooms)
        assertEquals(Furnishing.UNFURNISHED, a.furnishing)
        assertEquals(Audience.FAMILIES, a.audience)
        assertEquals(true, a.kitchenFitted)
        assertEquals("الملقا", a.district)
        assertEquals(LocationPrecision.EXACT, a.locationPrecision)
        assertEquals(24.795934, a.location!!.lat, 1e-6)
        assertEquals(15, a.photos.size)
        assertTrue(a.photos.all { it.startsWith("https://images.aqar.fm/webp/750x0/props/") })
        assertTrue(a.url.startsWith("https://sa.aqar.fm/"))
        assertEquals(1_791_222_519_000L, a.postedAt)

        // A 2-payment total equal to the price makes it yearly.
        val b = items.first { it.sourceId == "6901271" }
        assertEquals(RentPeriod.YEARLY, b.period)
        assertEquals(45_000.0, Resolver.resolve(b).annualRent)

        // No payment plan: the resolver infers yearly from the amount.
        val c = items.first { it.sourceId == "6905404" }
        assertEquals(RentPeriod.UNKNOWN, c.period)
        assertEquals(35_000.0, Resolver.resolve(c).annualRent)
    }

    @Test
    fun `aqar query asks for rentals inside the polygon`() {
        val polygon = AqarSource.circle(Districts.all.first().let { app.shaqati.core.model.GeoPoint(it.lat, it.lng) }, 8.0)
        assertEquals(17, polygon.size)
        assertEquals(polygon.first(), polygon.last())
        val body = Json.parseToJsonElement(AqarSource.query(1, 50_000.0, polygon, 100, 100)).jsonObject
        val vars = body["variables"]!!.jsonObject
        assertEquals("100", vars["from"].toString())
        assertTrue(vars["where"].toString().contains("\"lte\":50000"))
        assertTrue(body["query"].toString().contains("findListings"))
    }

    @Test
    fun `haraj posts drop wanted ads, villas and keep licence data`() {
        val items = HarajSource.parse(fixture("haraj-posts.json"))
        val ids = items.map { it.sourceId }.toSet()
        // Wanted ads (title or first line of the text) are not offers.
        for (wanted in listOf("190684656", "190678600", "190675121", "190673702", "190673457", "190678518")) {
            assertFalse(wanted in ids, "wanted ad $wanted should be dropped")
        }
        // REGA licence type Villa; an unlicensed post with no price or pin ("lost bird").
        assertFalse("190682730" in ids)
        assertFalse("190676829" in ids)
        val tuwaiq = items.first { it.sourceId == "190680818" }
        assertEquals(3_100.0, tuwaiq.price)
        assertEquals(RentPeriod.MONTHLY, tuwaiq.period)
        val qadisiyah = items.first { it.sourceId == "190683833" }
        assertEquals("7201104944", qadisiyah.licenseNumber)
        // The licence block links the deed location on Google Maps.
        assertEquals(LocationPrecision.EXACT, qadisiyah.locationPrecision)
        assertEquals(24.810341719064, qadisiyah.location!!.lat, 1e-9)
        assertTrue(Geo.inRiyadh(qadisiyah.location!!))
        assertTrue(qadisiyah.photos.size >= 5)
        assertTrue(qadisiyah.url.startsWith("https://haraj.com.sa/"))
    }

    @Test
    fun `property finder search and ad page`() {
        val (items, pages) = PropertyFinderSource.parseSearch(fixture("pf-search.html"))
        assertEquals(19, pages)
        assertEquals(6, items.size)
        val a = items.first { it.sourceId == "51460177" }
        assertEquals(65_000.0, a.price)
        assertEquals(RentPeriod.YEARLY, a.period)
        assertEquals(1, a.bedrooms)
        assertEquals(Furnishing.FURNISHED, a.furnishing)
        assertEquals(LocationPrecision.DISTRICT, a.locationPrecision)
        assertEquals("7200960706", a.licenseNumber)
        assertEquals(3, a.photos.size)
        // EL in the list view is electricity, not an elevator.
        assertFalse(Feature.ELEVATOR in a.features)
        assertEquals(PropertyKind.VILLA, items.first { it.sourceId == "52289859" }.kind)

        val full = PropertyFinderSource.mergeDetails(a, fixture("pf-detail.html"))
        assertEquals(19, full.photos.size)
        assertEquals(5, full.buildingAgeYears)
        assertTrue(full.description.length > a.description.length / 2)
    }

    @Test
    fun `deal ad`() {
        val data = Json.parseToJsonElement(fixture("deal-detail.json")).jsonObject["data"]!!
        val l = assertNotNull(DealSource.toListing(data))
        assertEquals(70_000.0, l.price)
        assertEquals(RentPeriod.YEARLY, l.period)
        assertEquals(Audience.ANY, l.audience)
        assertEquals(0, l.buildingAgeYears)
        assertEquals("7100332020", l.licenseNumber)
        assertEquals("https://dealapp.sa/ar/ad-details/609747", l.url)
        // The main media is a video: its thumbnail is the photo, its preview the video.
        assertEquals(1, l.photos.size)
        assertTrue(l.videos.single().endsWith(".mp4"))
        // This ad is in Jeddah, so it has no Riyadh location.
        assertNull(l.location)
        assertNotNull(l.bedrooms)

        val riyadh = Json.parseToJsonElement(
            fixture("deal-detail.json").replace("[39.16469656725476,21.57420050532706]", "[46.6802,24.7655]")
        ).jsonObject["data"]!!
        val r = assertNotNull(DealSource.toListing(riyadh))
        assertEquals(LocationPrecision.EXACT, r.locationPrecision)
        assertEquals(24.7655, r.location!!.lat, 1e-6)
    }

    @Test
    fun `opensooq results keep riyadh apartments and read the attribute chips`() {
        val (items, pages) = OpenSooqSource.parseSearch(fixture("opensooq-search.html"))
        assertEquals(1, pages)
        // Only city 24 (Riyadh).
        assertEquals(setOf("288663068", "288585066", "287853128", "287816370"), items.map { it.sourceId }.toSet())
        val malqa = items.first { it.sourceId == "287853128" }
        assertEquals(75_000.0, malqa.price)
        assertEquals(RentPeriod.YEARLY, malqa.period)
        assertEquals(2, malqa.bedrooms)
        assertEquals(2, malqa.bathrooms)
        assertEquals(Furnishing.SEMI_FURNISHED, malqa.furnishing)
        assertEquals(3, malqa.floor)
        assertEquals(205.0, malqa.areaSqm)
        assertEquals(LocationPrecision.DISTRICT, malqa.locationPrecision)
        val tuwaiq = items.first { it.sourceId == "287816370" }
        assertEquals(0, tuwaiq.buildingAgeYears)
        assertEquals(Furnishing.FURNISHED, tuwaiq.furnishing)

        val first = items.first { it.sourceId == "288663068" }
        assertEquals(RentPeriod.MONTHLY, first.period)
        val full = OpenSooqSource.mergeDetails(first, fixture("opensooq-detail.html"))
        assertEquals(LocationPrecision.EXACT, full.locationPrecision)
        assertEquals(24.789667, full.location!!.lat, 1e-6)
        assertTrue(full.photos.size >= 20, "photos: ${full.photos.size}")
        assertTrue(full.photos.all { it.startsWith("https://opensooq-imagesv2.os-cdn.com/previews/700x0/") && it.endsWith(".jpg.webp") })
        assertTrue(full.description.contains("موطن تفانين"))
        assertTrue(Feature.ELEVATOR in full.features)
        assertEquals(2, full.bedrooms)
        assertEquals(2, full.floor)
    }

    @Test
    fun `bayut search page state`() {
        val page = assertNotNull(BayutSource.parseSearch(fixture("bayut-search.html")))
        assertEquals(12, page.total)
        assertEquals(1, page.pageCount)
        assertEquals(12, page.listings.size)
        val a = page.listings.first { it.sourceId == "88021423" }
        assertEquals("شقة استوديو للإيجار في السلطانة، الرياض", a.title)
        assertEquals(27_000.0, a.price)
        assertEquals(RentPeriod.YEARLY, a.period)
        assertEquals(3, a.bedrooms)
        assertEquals(2, a.bathrooms)
        assertEquals(150.0, a.areaSqm)
        assertEquals(Furnishing.UNFURNISHED, a.furnishing)
        // Titles say "Studio" for some multi-room ads; the room count wins.
        assertEquals(PropertyKind.APARTMENT, a.kind)
        assertEquals("الرياض", a.city)
        assertEquals("سلطانة", a.district)
        assertEquals(24.609768815889, a.location!!.lat, 1e-9)
        assertEquals(LocationPrecision.APPROXIMATE, a.locationPrecision)
        assertEquals(
            listOf(9587649, 9587692, 9587693).map { "https://images.bayut.sa/thumbnails/$it-800x600.jpeg" },
            a.photos,
        )
        assertEquals("https://www.bayut.sa/property/%D8%AA%D9%81%D8%A7%D8%B5%D9%8A%D9%84-88021423.html", a.url)
        assertEquals(1_782_401_524_000L, a.postedAt)
        assertEquals("شركة مساكن اضداد العقارية", a.advertiserName)

        // Zero rooms and zero baths means the advertiser left them out.
        val b = page.listings.first { it.sourceId == "88025241" }
        assertNull(b.bedrooms)
        assertNull(b.bathrooms)
        assertEquals(PropertyKind.STUDIO, b.kind)
    }

    @Test
    fun `bayut arabic search page and ad page`() {
        val page = assertNotNull(BayutSource.parseSearch(fixture("bayut-search-ar.html")))
        assertEquals(3, page.listings.size)
        val a = page.listings.first { it.sourceId == "88032420" }
        assertEquals("شقة من غرفة نوم واحدة للإيجار في الوشم، الرياض", a.title)
        assertEquals("الوشام", a.district)
        assertEquals(1, a.bedrooms)
        assertEquals(10, a.photos.size)

        val listing = a.copy(sourceId = "88001416", location = app.shaqati.core.model.GeoPoint(24.893282, 46.642971))
        val full = BayutSource.mergeDetails(listing, fixture("bayut-detail.html"))
        assertTrue(full.description.startsWith("Consists of:\n3 bedrooms - 4 bathrooms - Hall - Kitchen"), full.description)
        assertFalse(full.description.contains("<br"))
        assertEquals(21, full.photos.size)
        assertEquals("https://images.bayut.sa/thumbnails/9438720-800x600.jpeg", full.photos.first())
        assertEquals(3, full.bedrooms)
        assertEquals(4, full.bathrooms)
        assertEquals(LocationPrecision.EXACT, full.locationPrecision)
        assertEquals("7200996418", full.licenseNumber)
        assertTrue(setOf(Feature.AC, Feature.MAID_ROOM, Feature.BALCONY, Feature.ELEVATOR, Feature.SMART_HOME).all { it in full.features }, "${full.features}")
        // Details of a different ad are ignored.
        assertEquals(a, BayutSource.mergeDetails(a, fixture("bayut-detail.html")))
    }

    @Test
    fun `a bayut or wasalt page without listings data is a check`() = kotlinx.coroutines.runBlocking {
        val check = "<html><head><title>Bayut</title></head><body>please wait</body></html>"
        val ctx = app.shaqati.core.sources.SearchContext(
            http = { app.shaqati.core.util.HttpResponse(200, check, it.url) },
            area = app.shaqati.core.sources.SearchArea(app.shaqati.core.model.GeoPoint(24.7655, 46.6802), 9.0),
            criteria = app.shaqati.core.filter.Criteria(),
        )
        val bayut = BayutSource().search(ctx)
        assertTrue(bayut.blocked)
        assertEquals(1, bayut.pagesFetched)
        assertTrue(WasaltSource().search(ctx).blocked)
    }

    @Test
    fun `bayut search urls page newest first within the budget`() {
        assertEquals(
            "https://www.bayut.sa/en/to-rent/apartments/riyadh/?rent_frequency=yearly&price_max=50000&sort=date_desc",
            BayutSource.searchUrl(1, 50_000.0),
        )
        assertEquals(
            "https://www.bayut.sa/en/to-rent/apartments/riyadh/page-3/?rent_frequency=monthly&price_max=4166&sort=date_desc",
            BayutSource.searchUrl(3, 50_000.0 / 12, "monthly"),
        )
    }

    @Test
    fun `bot check pages are recognised`() {
        fun resp(status: Int, body: String) = HttpResponse(status, body, "https://www.bayut.sa/")
        assertTrue(BotCheck.blocked(resp(503, "<html><title>Security check</title></html>")))
        assertTrue(BotCheck.blocked(resp(200, "<html><title>Just a moment...</title><script src=\"https://challenges.cloudflare.com/x.js\"></script></html>")))
        assertTrue(BotCheck.blocked(resp(200, "<div class=\"h-captcha\" data-sitekey=\"x\"></div>")))
        // Bayut's check page is large (inline scripts) and answers 200.
        val bigCheck = "<html><head><title>Security check | Bayut</title></head><body>" + "x".repeat(120_000) + "</body></html>"
        assertTrue(BotCheck.blocked(resp(200, bigCheck)))
        assertTrue(BotCheck.blocked(resp(200, "<title>wasalt.sa</title><h2>Performing security verification</h2>" + "y".repeat(90_000) + "<div class=\"cf-turnstile\"></div>")))
        assertFalse(BotCheck.blocked(resp(200, fixture("bayut-search.html"))))
        assertFalse(BotCheck.blocked(resp(200, fixture("wasalt-search.html"))))
        assertFalse(BotCheck.blocked(resp(200, "<html><title>Wasalt</title></html>")))
    }

    @Test
    fun `wasalt list page data`() {
        val page = assertNotNull(WasaltSource.parseSearch(fixture("wasalt-search.html")))
        assertEquals(3380, page.total)
        assertEquals(106, page.pageCount)
        // One of the twelve entries is a project (several units), not an ad.
        assertEquals(11, page.listings.size)
        val a = page.listings.first { it.sourceId == "5847704" }
        assertEquals("شقة ب 3 غرف", a.title)
        assertEquals("حطين", a.district)
        assertEquals("الرياض", a.city)
        assertEquals(60_000.0, a.price)
        assertEquals(RentPeriod.YEARLY, a.period)
        assertEquals(3, a.bedrooms)
        assertNull(a.bathrooms)
        assertEquals(146.0, a.areaSqm)
        assertEquals(6, a.buildingAgeYears)
        assertEquals(Furnishing.UNKNOWN, a.furnishing)
        assertEquals(24.76250014749476, a.location!!.lat, 1e-9)
        assertEquals(LocationPrecision.APPROXIMATE, a.locationPrecision)
        assertEquals(9, a.photos.size)
        assertEquals(
            "https://imagedelivery.net/1DNKFJPRaeUdy_j8F7HT3w/production/properties/5847704/images/555960e7-017e-45bd-8038-ecb74201ae82.jpg/width=1000,quality=70,format=auto",
            a.photos.first(),
        )
        assertEquals("7200779583", a.licenseNumber)
        assertEquals(java.time.Instant.parse("2026-02-09T19:14:52.484Z").toEpochMilli(), a.postedAt)
        assertEquals(AqarSource.encode("https://wasalt.sa/property/rent/شقة-ب-3-غرف-5847704"), a.url)

        val b = page.listings.first { it.sourceId == "5846872" }
        assertEquals(Furnishing.UNFURNISHED, b.furnishing)
        assertEquals(3, b.bathrooms)
        assertEquals(0, b.buildingAgeYears)
    }

    @Test
    fun `wasalt list urls count pages from zero`() {
        val base = AqarSource.encode("https://wasalt.sa/شقق-للايجار-في-الرياض")
        assertEquals("$base?max_price=50000", WasaltSource.searchUrl(1, 50_000.0))
        assertEquals("$base?max_price=50000&page=2", WasaltSource.searchUrl(3, 50_000.0))
    }

    @Test
    fun `wasalt ad page adds the description and exact pin`() {
        val item = assertNotNull(WasaltSource.parseSearch(fixture("wasalt-search.html"))).listings.first()
        val listing = item.copy(sourceId = "5946174")
        val full = WasaltSource.mergeDetails(listing, fixture("wasalt-detail.html"))
        assertTrue(full.description.contains("سعرها 45000"), full.description)
        assertFalse(full.description.contains("<p"))
        assertEquals(listOf(WasaltSource.photo("5946174", "1aeb9809-24fd-4809-bf60-aee04f88b843.webp")), full.photos)
        assertEquals(3, full.bedrooms)
        assertEquals(280.0, full.areaSqm)
        assertEquals(4, full.buildingAgeYears)
        assertEquals(LocationPrecision.EXACT, full.locationPrecision)
        // Details of a different ad are ignored.
        assertEquals(item, WasaltSource.mergeDetails(item, fixture("wasalt-detail.html")))
    }

    @Test
    fun `prices written in ad text`() {
        assertEquals(PriceText.Found(45_000.0, RentPeriod.YEARLY), PriceText.extract("الايجار 45 ألف سنوي"))
        assertEquals(PriceText.Found(45_000.0, RentPeriod.UNKNOWN), PriceText.extract("السعر: ٤٥٬٠٠٠ ريال"))
        assertEquals(PriceText.Found(3_100.0, RentPeriod.MONTHLY), PriceText.extract("شقه بحي طويق مكيفات ومطبخ شهري 3100"))
        assertEquals(3_000.0, PriceText.extract("شقه في فيلا ب3000 شهري")?.amount)
        assertNull(PriceText.extract("شقة 3 غرف ودورتين مياه"))
    }

    @Test
    fun `wanted ads are recognised after a greeting`() {
        assertTrue(WantedAd.matches("مطلوب شقة للايجار"))
        assertTrue(WantedAd.matches("السلام عليكم مطلوب شقه للايجار من ثلاث غرف"))
        assertTrue(WantedAd.matches("أبحث عن شقة"))
        assertFalse(WantedAd.matches("شقة للايجار مطلوب عربون"))
    }
}

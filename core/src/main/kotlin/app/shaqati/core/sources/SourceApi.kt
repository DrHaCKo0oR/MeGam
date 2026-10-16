package app.shaqati.core.sources

import app.shaqati.core.filter.Criteria
import app.shaqati.core.model.GeoPoint
import app.shaqati.core.model.Listing
import app.shaqati.core.model.Source
import app.shaqati.core.util.Http

/** Where to look: a circle around work, plus named districts the sites understand. */
data class SearchArea(
    val center: GeoPoint,
    val radiusKm: Double,
    val districtsAr: List<String> = emptyList(),
)

data class SearchContext(
    val http: Http,
    val area: SearchArea,
    val criteria: Criteria,
    val maxPages: Int = 5,
    val now: Long = System.currentTimeMillis(),
    val log: (String) -> Unit = {},
)

data class SearchResult(
    val source: Source,
    val listings: List<Listing>,
    val pagesFetched: Int,
    val errors: List<String> = emptyList(),
    /** True when the site refused automated access (bot check, 401/403). */
    val blocked: Boolean = false,
)

interface ListingSource {
    val source: Source

    /** Fetches current rental apartments in the area, newest first where the site allows. */
    suspend fun search(ctx: SearchContext): SearchResult

    /** Adds detail-page data (full description, all photos, contact) when the list view lacks it. */
    suspend fun details(listing: Listing, ctx: SearchContext): Listing = listing
}

/** Al Masif and neighbouring districts within a 40-minute rush-hour drive. */
object RiyadhNorth {
    /** Centre of Al Masif; the exact workplace pin is set in the app. */
    val DEFAULT_WORK = GeoPoint(24.7655, 46.6802)

    /** Districts around Al Masif, Arabic names as the sites spell them. */
    val NEARBY_DISTRICTS = listOf(
        "المصيف", "المروج", "التعاون", "الازدهار", "الورود", "الغدير", "الوادي", "النخيل",
        "المرسلات", "النفل", "الواحة", "صلاح الدين", "الملك فهد", "المغرزات", "السليمانية",
        "العقيق", "الصحافة", "الربيع", "النزهة", "الندى", "الفلاح", "حطين", "الملقا", "العليا",
    )
}

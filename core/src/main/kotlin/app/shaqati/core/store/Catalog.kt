package app.shaqati.core.store

import app.shaqati.core.ai.AiAssessment
import app.shaqati.core.commute.CommuteEstimate
import app.shaqati.core.model.Listing
import app.shaqati.core.model.Source
import kotlinx.serialization.Serializable

/** A listing as tracked over time. */
@Serializable
data class Tracked(
    val listing: Listing,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    /** Set when a complete refresh of its source no longer returns it. */
    val goneAt: Long? = null,
    val detailsFetchedAt: Long? = null,
)

@Serializable
enum class HideReason { TOO_EXPENSIVE, TOO_FAR, OLD_FINISH, LAYOUT, OTHER }

@Serializable
data class UserState(
    val saved: Map<String, Long> = emptyMap(),
    val hidden: Map<String, HideReason?> = emptyMap(),
    val seen: Set<String> = emptySet(),
    val notes: Map<String, String> = emptyMap(),
)

@Serializable
data class SourceStatus(
    val source: Source,
    val lastRunAt: Long,
    val ok: Boolean,
    val found: Int,
    val pages: Int,
    val blocked: Boolean,
    val message: String? = null,
)

@Serializable
data class AiSpend(val month: String, val usd: Double, val assessed: Int)

@Serializable
data class PendingBatch(val batchId: String, val listingIds: List<String>, val photos: Map<String, List<String>>, val submittedAt: Long)

/** Everything the app persists about listings, as one serializable snapshot. */
@Serializable
data class CatalogSnapshot(
    val version: Int = 1,
    val tracked: Map<String, Tracked> = emptyMap(),
    val commute: Map<String, CommuteEstimate> = emptyMap(),
    val ai: Map<String, AiAssessment> = emptyMap(),
    val user: UserState = UserState(),
    val sources: Map<String, SourceStatus> = emptyMap(),
    val spend: List<AiSpend> = emptyList(),
    val batches: List<PendingBatch> = emptyList(),
    val lastRefreshAt: Long? = null,
)

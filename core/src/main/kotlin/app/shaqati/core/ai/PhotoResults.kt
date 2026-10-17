package app.shaqati.core.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Photo checks made by Claude Code on the owner's own Claude subscription (the photo-check
 * workflow), published in the repository as `photo-results/assessments.json` and read by the app.
 */
@Serializable
data class PhotoResults(
    val version: Int = 1,
    val updatedAt: Long = 0,
    val assessments: List<AiAssessment> = emptyList(),
) {
    /** Adds [fresh], keeping the newest assessment of each listing and dropping old ones. */
    fun merge(fresh: List<AiAssessment>, now: Long, keepDays: Int = 120, max: Int = 3_000): PhotoResults {
        val cutoff = now - keepDays * DAY
        val kept = (assessments + fresh)
            .filter { it.assessedAt >= cutoff }
            .groupBy { it.listingId }
            .map { (_, all) -> all.maxBy { it.assessedAt } }
            .sortedByDescending { it.assessedAt }
            .take(max)
        return PhotoResults(version, now, kept)
    }

    companion object {
        /** How these assessments are labelled; they cost nothing beyond the subscription. */
        const val MODEL = "claude-code sonnet (Claude subscription)"
        private const val DAY = 24 * 60 * 60 * 1000L

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

        fun decode(text: String): PhotoResults = json.decodeFromString(serializer(), text)

        fun encode(results: PhotoResults): String = json.encodeToString(serializer(), results)

        /**
         * The assessment in the output of `claude -p --output-format json`: the `structured_output`
         * object when a JSON schema was given, otherwise the JSON in the result text.
         */
        fun parseClaudeCodeOutput(text: String): AssessmentBody {
            val root = json.parseToJsonElement(text)
            // With --verbose the output is every message; the last one is the result.
            val result = when (root) {
                is JsonArray -> root.lastOrNull { (it as? JsonObject)?.get("type")?.str() == "result" } as? JsonObject
                is JsonObject -> root
                else -> null
            } ?: error("no result in Claude Code output")
            require(result["is_error"]?.jsonPrimitive?.booleanOrNull != true) {
                "Claude Code reported an error: ${result["result"]?.str()?.take(200)}"
            }
            val structured = result["structured_output"]
            val answer = if (structured is JsonObject) structured.toString() else result["result"]?.str() ?: error("empty result")
            return Prompt.parse(answer)
        }

        private fun JsonElement.str(): String? = runCatching { jsonPrimitive.contentOrNull }.getOrNull()
    }
}

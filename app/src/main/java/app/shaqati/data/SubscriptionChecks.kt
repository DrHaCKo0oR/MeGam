package app.shaqati.data

import android.content.SharedPreferences
import androidx.core.content.edit
import app.shaqati.core.ai.PhotoResults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Photo checks made by Claude Code on the owner's own Claude subscription. The photo-check
 * workflow in the app's GitHub repository runs them and publishes the results; the app only
 * downloads that file, so it never holds Claude credentials.
 */
class SubscriptionChecks(private val http: OkHttpClient, private val prefs: SharedPreferences) {
    /** [count] apartments in the published results, last changed at [updatedAt]; null before the first download. */
    data class State(val checkedAt: Long? = null, val count: Int = 0, val updatedAt: Long? = null, val failed: Boolean = false)

    private val _state = MutableStateFlow(
        State(
            checkedAt = prefs.getLong(KEY_CHECKED, 0).takeIf { it > 0 },
            count = prefs.getInt(KEY_COUNT, 0),
            updatedAt = prefs.getLong(KEY_UPDATED, 0).takeIf { it > 0 },
        )
    )
    val state: StateFlow<State> = _state.asStateFlow()

    /** The published results, an empty set before the first run, or null when they could not be read. */
    suspend fun fetch(): PhotoResults? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val results = runCatching {
            http.newCall(Request.Builder().url(RESULTS_URL).header("Cache-Control", "no-cache").build()).execute().use { resp ->
                when {
                    resp.code == 404 -> PhotoResults()
                    resp.isSuccessful -> PhotoResults.decode(resp.body?.string().orEmpty())
                    else -> null
                }
            }
        }.getOrNull()
        if (results == null) {
            _state.value = _state.value.copy(checkedAt = now, failed = true)
        } else {
            _state.value = State(now, results.assessments.size, results.updatedAt.takeIf { it > 0 })
            prefs.edit {
                putLong(KEY_CHECKED, now)
                putInt(KEY_COUNT, results.assessments.size)
                putLong(KEY_UPDATED, results.updatedAt)
            }
        }
        results
    }

    companion object {
        private const val REPO = "DrHaCKo0oR/MeGam"
        /** HEAD is the repository's default branch, where GitHub runs the scheduled workflow. */
        const val RESULTS_URL = "https://raw.githubusercontent.com/$REPO/HEAD/photo-results/assessments.json"
        const val SECRETS_URL = "https://github.com/$REPO/settings/secrets/actions/new"
        const val PULL_URL = "https://github.com/$REPO/pull/1"
        const val SETUP_COMMAND = "claude setup-token"
        const val SECRET_NAME = "CLAUDE_CODE_OAUTH_TOKEN"
        private const val KEY_CHECKED = "sub_checked_at"
        private const val KEY_COUNT = "sub_count"
        private const val KEY_UPDATED = "sub_updated_at"
    }
}

package app.shaqati.core.commute

import app.shaqati.core.model.GeoPoint
import app.shaqati.core.util.Http
import app.shaqati.core.util.HttpException
import app.shaqati.core.util.HttpRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.math.roundToInt

/** One origin→destination forecast. */
@Serializable
data class RouteForecast(val minutes: Int, val meters: Int?)

/**
 * Google Routes API (computeRouteMatrix) with traffic-aware routing and a
 * future departure time, which uses Google's typical-traffic forecast.
 */
class GoogleRoutesClient(
    private val http: Http,
    private val apiKey: String,
    private val endpoint: String = "https://routes.googleapis.com/distanceMatrix/v2:computeRouteMatrix",
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Forecasts origins[i] → destinations[j] departing at [departure].
     * Keep origins × destinations ≤ [MAX_ELEMENTS].
     */
    suspend fun matrix(origins: List<GeoPoint>, destinations: List<GeoPoint>, departure: Instant): Map<Pair<Int, Int>, RouteForecast> {
        require(origins.size * destinations.size <= MAX_ELEMENTS) { "too many elements" }
        if (origins.isEmpty() || destinations.isEmpty()) return emptyMap()
        val body = buildJsonObject {
            put("origins", waypoints(origins))
            put("destinations", waypoints(destinations))
            put("travelMode", "DRIVE")
            put("routingPreference", "TRAFFIC_AWARE_OPTIMAL")
            put("departureTime", departure.toString())
            put("languageCode", "en")
            put("units", "METRIC")
        }
        val resp = http.send(
            HttpRequest(
                url = endpoint,
                method = "POST",
                headers = mapOf(
                    "X-Goog-Api-Key" to apiKey,
                    "X-Goog-FieldMask" to "originIndex,destinationIndex,duration,distanceMeters,status,condition",
                    // Lets one key restricted to the in-app map's address serve these requests too.
                    "Referer" to APP_REFERER,
                ),
                body = body.toString(),
                contentType = "application/json",
            )
        )
        if (!resp.ok) throw HttpException("Routes API ${resp.status}: ${errorMessage(resp.body)}", resp.status)
        return parse(resp.body)
    }

    internal fun parse(body: String): Map<Pair<Int, Int>, RouteForecast> {
        val root = json.parseToJsonElement(body)
        val items: JsonArray = when (root) {
            is JsonArray -> root
            is JsonObject -> root["elements"]?.jsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        val out = mutableMapOf<Pair<Int, Int>, RouteForecast>()
        for (e in items) {
            val o = e.jsonObject
            val condition = o["condition"]?.jsonPrimitive?.contentOrNull
            if (condition != null && condition != "ROUTE_EXISTS") continue
            val status = o["status"]?.jsonObject
            if (status != null && (status["code"]?.jsonPrimitive?.intOrNull ?: 0) != 0) continue
            val seconds = o["duration"]?.jsonPrimitive?.contentOrNull?.removeSuffix("s")?.toDoubleOrNull() ?: continue
            val oi = o["originIndex"]?.jsonPrimitive?.intOrNull ?: 0
            val di = o["destinationIndex"]?.jsonPrimitive?.intOrNull ?: 0
            out[oi to di] = RouteForecast((seconds / 60.0).roundToInt(), o["distanceMeters"]?.jsonPrimitive?.intOrNull)
        }
        return out
    }

    private fun errorMessage(body: String): String = runCatching {
        val root = json.parseToJsonElement(body)
        val err = (if (root is JsonArray) root.firstOrNull() else root)?.jsonObject?.get("error")?.jsonObject
        err?.get("message")?.jsonPrimitive?.contentOrNull
    }.getOrNull() ?: body.take(200)

    private fun waypoints(points: List<GeoPoint>) = buildJsonArray {
        for (p in points) {
            add(buildJsonObject {
                put("waypoint", buildJsonObject {
                    put("location", buildJsonObject {
                        put("latLng", buildJsonObject {
                            put("latitude", p.lat)
                            put("longitude", p.lng)
                        })
                    })
                })
            })
        }
    }

    companion object {
        /** Address of the in-app map page; a key restricted to this website works for both. */
        const val APP_REFERER = "https://appassets.androidplatform.net/"

        /** Conservative element cap per request for traffic-aware matrices. */
        const val MAX_ELEMENTS = 25
    }
}

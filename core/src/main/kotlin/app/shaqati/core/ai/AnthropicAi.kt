package app.shaqati.core.ai

import app.shaqati.core.model.Listing
import app.shaqati.core.model.Resolved
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.anthropic.models.messages.batches.BatchCreateParams
import com.anthropic.models.messages.batches.MessageBatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Duration

/** A photo ready to send: base64 bytes and their media type (image/jpeg or image/webp). */
data class PreparedImage(val base64: String, val mediaType: String)

/** Downloads and downsizes one photo; returns null when it cannot be read. */
fun interface ImagePreparer {
    suspend fun prepare(url: String): PreparedImage?
}

data class BatchState(val id: String, val ended: Boolean, val succeeded: Long, val errored: Long, val processing: Long)

class AiException(message: String, val unauthorized: Boolean = false) : Exception(message)

/**
 * Claude Sonnet 5.5 interior-finish assessment through the official Anthropic Java SDK.
 * Photos are sent as small base64 images; the long instructions are a cached system block.
 */
class AnthropicAi(
    apiKey: String,
    private val model: String = Pricing.MODEL,
    baseUrl: String? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .timeout(Duration.ofSeconds(120))
        .maxRetries(2)
        .apply { if (baseUrl != null) baseUrl(baseUrl) }
        .build()

    private val outputConfig: OutputConfig = OutputConfig.builder()
        .effort(OutputConfig.Effort.LOW)
        .format(JsonOutputFormat.builder().schema(schema()).build())
        .build()

    private val system = listOf(
        TextBlockParam.builder()
            .text(Prompt.SYSTEM)
            .cacheControl(CacheControlEphemeral.builder().build())
            .build()
    )

    private fun schema(): JsonOutputFormat.Schema {
        val b = JsonOutputFormat.Schema.builder()
        for ((k, v) in Prompt.SCHEMA) b.putAdditionalProperty(k, JsonValue.from(toPlain(v)))
        return b.build()
    }

    private fun content(listing: Listing, resolved: Resolved, images: List<PreparedImage>): List<ContentBlockParam> {
        val blocks = mutableListOf<ContentBlockParam>()
        blocks += ContentBlockParam.ofText(TextBlockParam.builder().text(Prompt.listingText(listing, resolved, images.size)).build())
        images.forEachIndexed { i, img ->
            blocks += ContentBlockParam.ofText(TextBlockParam.builder().text("Photo ${i + 1}").build())
            blocks += ContentBlockParam.ofImage(
                ImageBlockParam.builder()
                    .source(
                        Base64ImageSource.builder()
                            .data(img.base64)
                            .mediaType(Base64ImageSource.MediaType.of(img.mediaType))
                            .build()
                    )
                    .build()
            )
        }
        return blocks
    }

    fun params(listing: Listing, resolved: Resolved, images: List<PreparedImage>): MessageCreateParams =
        MessageCreateParams.builder()
            .model(model)
            .maxTokens(MAX_TOKENS)
            .systemOfTextBlockParams(system)
            .thinking(ThinkingConfigAdaptive.builder().build())
            .outputConfig(outputConfig)
            .addUserMessageOfBlockParams(content(listing, resolved, images))
            .build()

    /** Assesses one listing now (full price). */
    suspend fun assess(listing: Listing, resolved: Resolved, images: List<PreparedImage>, photoUrls: List<String>): AiAssessment =
        withContext(Dispatchers.IO) {
            val message = try {
                client.messages().create(params(listing, resolved, images))
            } catch (e: UnauthorizedException) {
                throw AiException("The Anthropic API key was rejected.", unauthorized = true)
            } catch (e: AnthropicServiceException) {
                throw AiException("Anthropic API error ${e.statusCode()}: ${e.message}")
            }
            toAssessment(listing.id, message, photoUrls, batch = false)
        }

    fun toAssessment(listingId: String, message: Message, photoUrls: List<String>, batch: Boolean): AiAssessment {
        if (message.stopReason().orElse(null) == StopReason.REFUSAL) throw AiException("The model declined to assess this listing.")
        val text = message.content().firstNotNullOfOrNull { b -> b.text().orElse(null)?.text() }
            ?: throw AiException("The model returned no text.")
        val body = Prompt.parse(text)
        val u = message.usage()
        val input = u.inputTokens()
        val output = u.outputTokens()
        val cw = u.cacheCreationInputTokens().orElse(0L)
        val cr = u.cacheReadInputTokens().orElse(0L)
        return AiAssessment(
            listingId = listingId,
            body = body,
            model = model,
            photosUsed = photoUrls,
            inputTokens = input + cw + cr,
            outputTokens = output,
            costUsd = Pricing.cost(input, output, cw, cr, batch),
            assessedAt = clock(),
            viaBatch = batch,
        )
    }

    /** Submits listings to the Message Batches API (half price; results usually within minutes). */
    suspend fun submitBatch(items: List<Pair<String, MessageCreateParams>>): String = withContext(Dispatchers.IO) {
        val builder = BatchCreateParams.builder()
        for ((customId, p) in items) {
            val params = BatchCreateParams.Request.Params.builder()
                .model(p.model())
                .maxTokens(p.maxTokens())
                .messages(p.messages())
                .apply { p.system().ifPresent { s -> s.textBlockParams().ifPresent { systemOfTextBlockParams(it) } } }
                .apply { p.thinking().ifPresent { thinking(it) } }
                .apply { p.outputConfig().ifPresent { outputConfig(it) } }
                .build()
            builder.addRequest(BatchCreateParams.Request.builder().customId(customId).params(params).build())
        }
        try {
            client.messages().batches().create(builder.build()).id()
        } catch (e: UnauthorizedException) {
            throw AiException("The Anthropic API key was rejected.", unauthorized = true)
        } catch (e: AnthropicServiceException) {
            throw AiException("Anthropic API error ${e.statusCode()}: ${e.message}")
        }
    }

    suspend fun batchState(id: String): BatchState = withContext(Dispatchers.IO) {
        val b: MessageBatch = client.messages().batches().retrieve(id)
        val c = b.requestCounts()
        BatchState(id, b.processingStatus() == MessageBatch.ProcessingStatus.ENDED, c.succeeded(), c.errored() + c.expired() + c.canceled(), c.processing())
    }

    /** Results of an ended batch: custom id → message or error text. */
    suspend fun batchResults(id: String): Map<String, Result<Message>> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, Result<Message>>()
        client.messages().batches().resultsStreaming(id).use { stream ->
            stream.stream().forEach { r ->
                val res = r.result()
                out[r.customId()] = when {
                    res.isSucceeded() -> Result.success(res.asSucceeded().message())
                    res.isErrored() -> Result.failure(AiException("errored: ${res.asErrored().error()}"))
                    res.isExpired() -> Result.failure(AiException("expired"))
                    else -> Result.failure(AiException("canceled"))
                }
            }
        }
        out
    }

    /** Checks the key with a free call. */
    suspend fun verifyKey(): Boolean = withContext(Dispatchers.IO) {
        try {
            client.models().retrieve(model)
            true
        } catch (e: UnauthorizedException) {
            false
        }
    }

    companion object {
        const val MAX_TOKENS = 3000L

        /** kotlinx JSON → plain Java values accepted by the SDK's JsonValue.from. */
        fun toPlain(e: JsonElement): Any? = when (e) {
            is JsonNull -> null
            is JsonObject -> e.mapValues { toPlain(it.value) }
            is JsonArray -> e.map { toPlain(it) }
            is JsonPrimitive -> when {
                e.isString -> e.content
                e.booleanOrNull != null -> e.booleanOrNull
                e.longOrNull != null -> e.longOrNull
                else -> e.doubleOrNull
            }
        }
    }
}

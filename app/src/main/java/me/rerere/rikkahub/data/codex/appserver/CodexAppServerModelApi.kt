package me.rerere.rikkahub.data.codex.appserver

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class CodexReasoningEffortOption(val reasoningEffort: String, val description: String)
data class CodexModelServiceTier(val id: String, val name: String, val description: String)

data class CodexAppServerModel(
    val id: String,
    val model: String,
    val displayName: String,
    val description: String,
    val hidden: Boolean,
    val supportedReasoningEfforts: List<CodexReasoningEffortOption>,
    val defaultReasoningEffort: String,
    val supportsPersonality: Boolean,
    val isDefault: Boolean,
    val raw: JsonObject,
    /** Null means the server did not report this forward-compatible capability. */
    val inputModalities: List<String>? = null,
    val serviceTiers: List<CodexModelServiceTier>? = null,
    val defaultServiceTier: String? = null,
    val additionalSpeedTiers: List<String>? = null,
)

data class CodexAppServerModelListResult(
    val data: List<CodexAppServerModel>, val nextCursor: String?, val raw: JsonObject,
)

/** Modern metadata wins; legacy values are consulted only when modern metadata is absent/empty. */
fun CodexAppServerModel.serviceTierIds(): List<String> =
    serviceTiers?.takeIf { it.isNotEmpty() }?.map { it.id } ?: additionalSpeedTiers.orEmpty()

class CodexAppServerModelProtocolException(message: String) : SerializationException(message)

/** Typed model catalog client. It deliberately owns no process and uses the supplied connection. */
class CodexAppServerModelApi(private val connection: CodexAppServerConnection) {
    suspend fun list(
        cursor: String? = null, limit: Int? = null, includeHidden: Boolean? = null,
        timeout: Duration = 30.seconds,
    ): CodexAppServerModelListResult {
        require(limit == null || limit > 0) { "limit must be positive" }
        val params = buildJsonObject {
            cursor?.let { put("cursor", it) }
            limit?.let { put("limit", it) }
            includeHidden?.let { put("includeHidden", it) }
        }
        return decode(connection.sendRequestAfterReady("model/list", params, timeout))
    }

    suspend fun listAllVisible(maxPages: Int = 100): List<CodexAppServerModel> {
        require(maxPages > 0)
        val models = mutableListOf<CodexAppServerModel>()
        val seen = mutableSetOf<String>()
        var cursor: String? = null
        repeat(maxPages) {
            val page = list(cursor = cursor, includeHidden = false)
            models += page.data.filterNot { it.hidden }
            val next = page.nextCursor
            if (next == null) {
                val complete = models.toList()
                CodexModelCatalogKnowledge.replace(complete)
                return complete
            }
            if (!seen.add(next)) throw CodexAppServerModelProtocolException("model/list repeated cursor: $next")
            cursor = next
        }
        throw CodexAppServerModelProtocolException("model/list exceeded $maxPages pages")
    }

    private fun decode(value: JsonElement): CodexAppServerModelListResult {
        val raw = value as? JsonObject ?: fail("model/list result must be an object")
        val data = raw["data"] as? JsonArray ?: fail("model/list data must be an array")
        val models = data.mapIndexed { index, element ->
            val item = element as? JsonObject ?: fail("model/list data[$index] must be an object")
            fun string(name: String) = (item[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: fail("model/list data[$index].$name must be a string")
            fun bool(name: String) = (item[name] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                ?: fail("model/list data[$index].$name must be a boolean")
            val efforts = item["supportedReasoningEfforts"] as? JsonArray
                ?: fail("model/list data[$index].supportedReasoningEfforts must be an array")
            CodexAppServerModel(
                string("id"), string("model"), string("displayName"), string("description"), bool("hidden"),
                efforts.mapIndexed { effortIndex, effort ->
                    val option = effort as? JsonObject ?: fail("reasoning effort[$effortIndex] must be an object")
                    fun optionString(name: String) = (option[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: fail("reasoning effort[$effortIndex].$name must be a string")
                    CodexReasoningEffortOption(optionString("reasoningEffort"), optionString("description"))
                },
                string("defaultReasoningEffort"), bool("supportsPersonality"), bool("isDefault"),
                item, item["inputModalities"]?.let { modalities ->
                    val array = modalities as? JsonArray
                        ?: fail("model/list data[$index].inputModalities must be an array")
                    array.mapIndexed { modalityIndex, modality ->
                        (modality as? JsonPrimitive)?.takeIf { it.isString }?.content
                            ?: fail("model/list data[$index].inputModalities[$modalityIndex] must be a string")
                    }
                },
                item["serviceTiers"]?.let { tiers ->
                    val array = tiers as? JsonArray ?: fail("model/list data[$index].serviceTiers must be an array")
                    array.mapIndexed { tierIndex, tier ->
                        val option = tier as? JsonObject ?: fail("service tier[$tierIndex] must be an object")
                        fun tierString(name: String) = (option[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
                            ?: fail("service tier[$tierIndex].$name must be a string")
                        CodexModelServiceTier(tierString("id"), tierString("name"), tierString("description"))
                    }
                },
                item["defaultServiceTier"]?.let { value ->
                    (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: fail("model/list data[$index].defaultServiceTier must be a string")
                },
                item["additionalSpeedTiers"]?.let { tiers ->
                    val array = tiers as? JsonArray ?: fail("model/list data[$index].additionalSpeedTiers must be an array")
                    array.mapIndexed { tierIndex, tier ->
                        (tier as? JsonPrimitive)?.takeIf { it.isString }?.content
                            ?: fail("model/list data[$index].additionalSpeedTiers[$tierIndex] must be a string")
                    }
                },
            )
        }
        val next = raw["nextCursor"].let { element ->
            if (element == null || element is JsonNull) null
            else (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail("nextCursor must be a string or null")
        }
        return CodexAppServerModelListResult(models, next, raw)
    }

    private fun fail(message: String): Nothing = throw CodexAppServerModelProtocolException(message)
}

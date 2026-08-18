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
    /** Null means the server did not report this forward-compatible capability or reported null. */
    val inputModalities: List<String>? = null,
    val serviceTiers: List<CodexModelServiceTier>? = null,
    val defaultServiceTier: String? = null,
    val additionalSpeedTiers: List<String>? = null,
)

data class CodexAppServerModelListResult(
    val data: List<CodexAppServerModel>, val nextCursor: String?, val raw: JsonObject,
)

data class CodexServiceTierIds(val confirmed: List<String>?) {
    operator fun contains(id: String): Boolean = confirmed?.contains(id) ?: true
}

fun CodexAppServerModel.serviceTierIds(): CodexServiceTierIds = CodexServiceTierIds(
    confirmed = when {
        !serviceTiers.isNullOrEmpty() -> serviceTiers.map { it.id }
        additionalSpeedTiers != null -> additionalSpeedTiers
        serviceTiers != null -> emptyList()
        else -> null
    },
)

class CodexAppServerModelProtocolException(message: String) : SerializationException(message)

/** Typed model catalog client. Unknown future fields remain preserved in [CodexAppServerModel.raw]. */
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
                item,
                optionalStringArray(item, index, "inputModalities"),
                item["serviceTiers"].let { tiers ->
                    if (tiers == null || tiers is JsonNull) null else {
                        val array = tiers as? JsonArray ?: fail("model/list data[$index].serviceTiers must be an array or null")
                        array.mapIndexed { tierIndex, tier ->
                            val option = tier as? JsonObject ?: fail("service tier[$tierIndex] must be an object")
                            fun tierString(name: String) = (option[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
                                ?: fail("service tier[$tierIndex].$name must be a string")
                            CodexModelServiceTier(tierString("id"), tierString("name"), tierString("description"))
                        }
                    }
                },
                optionalNullableString(item, index, "defaultServiceTier"),
                optionalStringArray(item, index, "additionalSpeedTiers"),
            )
        }
        val next = raw["nextCursor"].let { element ->
            if (element == null || element is JsonNull) null
            else (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail("nextCursor must be a string or null")
        }
        return CodexAppServerModelListResult(models, next, raw)
    }

    private fun optionalNullableString(item: JsonObject, index: Int, name: String): String? = when (val value = item[name]) {
        null, JsonNull -> null
        is JsonPrimitive -> value.takeIf { it.isString }?.content
            ?: fail("model/list data[$index].$name must be a string or null")
        else -> fail("model/list data[$index].$name must be a string or null")
    }

    private fun optionalStringArray(item: JsonObject, index: Int, name: String): List<String>? = when (val value = item[name]) {
        null, JsonNull -> null
        is JsonArray -> value.mapIndexed { itemIndex, element ->
            (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: fail("model/list data[$index].$name[$itemIndex] must be a string")
        }
        else -> fail("model/list data[$index].$name must be an array or null")
    }

    private fun fail(message: String): Nothing = throw CodexAppServerModelProtocolException(message)
}

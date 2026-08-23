package me.rerere.rikkahub.data.codex.appserver

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * Provider-independent view of the subset of OpenAI Responses requests Codex emits when it is
 * using a custom model provider. This decoder is intentionally separate from Ktor so protocol
 * behavior can be covered with plain JVM tests.
 */
data class CodexHarnessTranslatedRequest(
    val requestedModel: String?,
    val messages: List<UIMessage>,
    val tools: List<Tool>,
    val stream: Boolean,
)

object CodexHarnessResponsesRequestTranslator {
    fun translate(request: JsonObject): CodexHarnessTranslatedRequest {
        val messages = mutableListOf<UIMessage>()

        request.string("instructions")
            ?.takeIf { it.isNotBlank() }
            ?.let { messages += UIMessage.system(it) }

        when (val input = request["input"]) {
            null -> Unit
            is JsonPrimitive -> {
                require(input.isString) { "Responses input primitive must be a string" }
                input.contentOrNull?.let { messages += UIMessage.user(it) }
            }
            is JsonArray -> input.forEachIndexed { index, element ->
                val item = element as? JsonObject
                    ?: throw IllegalArgumentException("Responses input[$index] must be an object")
                appendInputItem(messages, item, index)
            }
            else -> throw IllegalArgumentException("Responses input must be a string or array")
        }

        return CodexHarnessTranslatedRequest(
            requestedModel = request.string("model"),
            messages = messages,
            tools = decodeTools(request["tools"]),
            stream = request.boolean("stream") ?: false,
        )
    }

    private fun appendInputItem(
        messages: MutableList<UIMessage>,
        item: JsonObject,
        index: Int,
    ) {
        when (item.string("type")) {
            null, "message" -> {
                val role = decodeRole(item.string("role") ?: "user")
                val parts = decodeContent(item["content"], role)
                if (parts.isNotEmpty()) messages += UIMessage(role = role, parts = parts)
            }

            "function_call" -> {
                val callId = item.string("call_id")
                    ?: throw IllegalArgumentException("Responses input[$index] function_call is missing call_id")
                val name = item.string("name")
                    ?: throw IllegalArgumentException("Responses input[$index] function_call is missing name")
                val arguments = item.string("arguments") ?: "{}"
                messages += UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Tool(
                            toolCallId = callId,
                            toolName = name,
                            input = arguments,
                            approvalState = ToolApprovalState.Auto,
                        )
                    ),
                )
            }

            "function_call_output" -> {
                val callId = item.string("call_id")
                    ?: throw IllegalArgumentException("Responses input[$index] function_call_output is missing call_id")
                val output = decodeFunctionOutput(item["output"])
                val messageIndex = messages.indexOfLast { message ->
                    message.parts.any { part ->
                        part is UIMessagePart.Tool && part.toolCallId == callId
                    }
                }
                if (messageIndex < 0) {
                    // Some compatible gateways send only the output item on a follow-up request.
                    // Keep it visible to the model rather than dropping context silently.
                    messages += UIMessage(
                        role = MessageRole.TOOL,
                        parts = listOf(UIMessagePart.Text("[$callId] $output")),
                    )
                } else {
                    val original = messages[messageIndex]
                    messages[messageIndex] = original.copy(
                        parts = original.parts.map { part ->
                            if (part is UIMessagePart.Tool && part.toolCallId == callId) {
                                part.copy(output = listOf(UIMessagePart.Text(output)))
                            } else part
                        }
                    )
                }
            }

            // Reasoning and provider-owned server-tool items are not needed to reconstruct the
            // executable conversation for non-Responses providers. Do not turn their hidden
            // payloads into user text.
            "reasoning", "web_search_call", "file_search_call", "computer_call" -> Unit

            else -> {
                // Forward-compatible fallback for a content-like item that supplies role/content.
                if (item["role"] != null && item["content"] != null) {
                    val role = decodeRole(item.string("role") ?: "user")
                    val parts = decodeContent(item["content"], role)
                    if (parts.isNotEmpty()) messages += UIMessage(role = role, parts = parts)
                }
            }
        }
    }

    private fun decodeRole(value: String): MessageRole = when (value.lowercase()) {
        "system", "developer" -> MessageRole.SYSTEM
        "assistant" -> MessageRole.ASSISTANT
        "tool" -> MessageRole.TOOL
        else -> MessageRole.USER
    }

    private fun decodeContent(value: JsonElement?, role: MessageRole): List<UIMessagePart> = when (value) {
        null -> emptyList()
        is JsonPrimitive -> if (value.isString) {
            listOf(UIMessagePart.Text(value.contentOrNull.orEmpty()))
        } else emptyList()
        is JsonArray -> value.mapNotNull { element ->
            val part = element as? JsonObject ?: return@mapNotNull null
            when (part.string("type")) {
                "input_text", "output_text", "text" -> part.string("text")?.let(UIMessagePart::Text)
                "input_image" -> part.string("image_url")?.let(UIMessagePart::Image)
                else -> null
            }
        }
        else -> emptyList()
    }

    private fun decodeFunctionOutput(value: JsonElement?): String = when (value) {
        null -> ""
        is JsonPrimitive -> value.contentOrNull ?: value.toString()
        is JsonArray -> value.mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> element.contentOrNull
                is JsonObject -> element.string("text") ?: element.string("output_text") ?: element.toString()
                else -> element.toString()
            }
        }.joinToString("\n")
        else -> value.toString()
    }

    private fun decodeTools(value: JsonElement?): List<Tool> {
        val array = value as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val raw = element as? JsonObject ?: return@mapNotNull null
            if (raw.string("type") != "function") return@mapNotNull null
            val name = raw.string("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val description = raw.string("description").orEmpty()
            val schema = decodeInputSchema(raw["parameters"])
            Tool(
                name = name,
                description = description,
                parameters = { schema },
                // The Codex harness, not RikkaHub's normal GenerationHandler, executes these
                // calls. This lambda must never be invoked by the gateway translation layer.
                execute = { emptyList() },
            )
        }
    }

    private fun decodeInputSchema(value: JsonElement?): InputSchema? {
        val raw = value as? JsonObject ?: return null
        if (raw.string("type")?.lowercase() != "object") return null
        val properties = raw["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val required = (raw["required"] as? JsonArray)?.mapNotNull { element ->
            (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        }
        return InputSchema.Obj(properties = properties, required = required)
    }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.boolean(name: String): Boolean? =
        (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.contentOrNull?.let { raw ->
            when (raw) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }
}

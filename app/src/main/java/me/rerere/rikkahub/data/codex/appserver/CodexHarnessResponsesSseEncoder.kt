package me.rerere.rikkahub.data.codex.appserver

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.StreamChunk
import java.util.UUID

/** A single OpenAI-compatible server-sent event. */
data class CodexHarnessResponsesSseEvent(
    val type: String,
    val payload: JsonObject,
) {
    fun wireData(): String = "event: $type\ndata: $payload\n\n"
}

/**
 * Stateful adapter from RikkaHub's provider-independent [StreamChunk] stream to the Responses
 * event vocabulary consumed by Codex. One encoder instance is used for exactly one HTTP request.
 */
class CodexHarnessResponsesSseEncoder(
    private val model: String,
    private val responseId: String = "resp_rikkahub_${UUID.randomUUID().toString().replace("-", "")}",
) {
    private data class TextState(val outputIndex: Int, val text: StringBuilder = StringBuilder())
    private data class ToolState(
        val outputIndex: Int,
        val itemId: String,
        var name: String,
        val arguments: StringBuilder = StringBuilder(),
    )

    private val textStates = linkedMapOf<String, TextState>()
    private val toolStates = linkedMapOf<String, ToolState>()
    private val completedItems = mutableMapOf<Int, JsonObject>()
    private var nextOutputIndex = 0
    private var usage: TokenUsage? = null
    private var started = false
    private var completed = false

    fun startEvents(): List<CodexHarnessResponsesSseEvent> {
        if (started) return emptyList()
        started = true
        return listOf(
            event("response.created", "response" to responseShell("in_progress")),
            event("response.in_progress", "response" to responseShell("in_progress")),
        )
    }

    fun accept(chunk: StreamChunk): List<CodexHarnessResponsesSseEvent> = buildList {
        addAll(startEvents())
        when (chunk) {
            is StreamChunk.TextStart -> {
                if (textStates.containsKey(chunk.id)) return@buildList
                val index = nextOutputIndex++
                textStates[chunk.id] = TextState(index)
                val item = messageItem(chunk.id, "in_progress", "")
                add(event(
                    "response.output_item.added",
                    "output_index" to JsonPrimitive(index),
                    "item" to item,
                ))
                add(event(
                    "response.content_part.added",
                    "item_id" to JsonPrimitive(chunk.id),
                    "output_index" to JsonPrimitive(index),
                    "content_index" to JsonPrimitive(0),
                    "part" to outputTextPart(""),
                ))
            }

            is StreamChunk.TextDelta -> {
                val state = textStates.getOrPut(chunk.id) {
                    TextState(nextOutputIndex++).also { implicit ->
                        add(event(
                            "response.output_item.added",
                            "output_index" to JsonPrimitive(implicit.outputIndex),
                            "item" to messageItem(chunk.id, "in_progress", ""),
                        ))
                        add(event(
                            "response.content_part.added",
                            "item_id" to JsonPrimitive(chunk.id),
                            "output_index" to JsonPrimitive(implicit.outputIndex),
                            "content_index" to JsonPrimitive(0),
                            "part" to outputTextPart(""),
                        ))
                    }
                }
                state.text.append(chunk.text)
                add(event(
                    "response.output_text.delta",
                    "item_id" to JsonPrimitive(chunk.id),
                    "output_index" to JsonPrimitive(state.outputIndex),
                    "content_index" to JsonPrimitive(0),
                    "delta" to JsonPrimitive(chunk.text),
                ))
            }

            is StreamChunk.TextEnd -> {
                val state = textStates[chunk.id] ?: return@buildList
                val text = state.text.toString()
                add(event(
                    "response.output_text.done",
                    "item_id" to JsonPrimitive(chunk.id),
                    "output_index" to JsonPrimitive(state.outputIndex),
                    "content_index" to JsonPrimitive(0),
                    "text" to JsonPrimitive(text),
                ))
                add(event(
                    "response.content_part.done",
                    "item_id" to JsonPrimitive(chunk.id),
                    "output_index" to JsonPrimitive(state.outputIndex),
                    "content_index" to JsonPrimitive(0),
                    "part" to outputTextPart(text),
                ))
                val item = messageItem(chunk.id, "completed", text)
                completedItems[state.outputIndex] = item
                add(event(
                    "response.output_item.done",
                    "output_index" to JsonPrimitive(state.outputIndex),
                    "item" to item,
                ))
            }

            is StreamChunk.ToolCallStart -> {
                if (toolStates.containsKey(chunk.id)) return@buildList
                val index = nextOutputIndex++
                val itemId = "fc_${UUID.randomUUID().toString().replace("-", "")}"
                toolStates[chunk.id] = ToolState(index, itemId, chunk.toolName)
                add(event(
                    "response.output_item.added",
                    "output_index" to JsonPrimitive(index),
                    "item" to functionCallItem(
                        itemId = itemId,
                        callId = chunk.id,
                        name = chunk.toolName,
                        arguments = "",
                        status = "in_progress",
                    ),
                ))
            }

            is StreamChunk.ToolCallDelta -> {
                val state = toolStates.getOrPut(chunk.id) {
                    ToolState(
                        outputIndex = nextOutputIndex++,
                        itemId = "fc_${UUID.randomUUID().toString().replace("-", "")}",
                        name = "",
                    ).also { implicit ->
                        add(event(
                            "response.output_item.added",
                            "output_index" to JsonPrimitive(implicit.outputIndex),
                            "item" to functionCallItem(
                                implicit.itemId,
                                chunk.id,
                                "",
                                "",
                                "in_progress",
                            ),
                        ))
                    }
                }
                if (chunk.toolNameDelta.isNotEmpty()) state.name += chunk.toolNameDelta
                if (chunk.inputDelta.isNotEmpty()) {
                    state.arguments.append(chunk.inputDelta)
                    add(event(
                        "response.function_call_arguments.delta",
                        "item_id" to JsonPrimitive(state.itemId),
                        "output_index" to JsonPrimitive(state.outputIndex),
                        "delta" to JsonPrimitive(chunk.inputDelta),
                    ))
                }
            }

            is StreamChunk.ToolCallEnd -> {
                val state = toolStates[chunk.id] ?: return@buildList
                val arguments = state.arguments.toString()
                add(event(
                    "response.function_call_arguments.done",
                    "item_id" to JsonPrimitive(state.itemId),
                    "output_index" to JsonPrimitive(state.outputIndex),
                    "arguments" to JsonPrimitive(arguments),
                ))
                val item = functionCallItem(
                    itemId = state.itemId,
                    callId = chunk.id,
                    name = state.name,
                    arguments = arguments,
                    status = "completed",
                )
                completedItems[state.outputIndex] = item
                add(event(
                    "response.output_item.done",
                    "output_index" to JsonPrimitive(state.outputIndex),
                    "item" to item,
                ))
            }

            is StreamChunk.Usage -> usage = chunk.usage
            is StreamChunk.Finish -> addAll(complete(chunk.finishReason))

            // Raw provider reasoning is intentionally not surfaced to Codex for translated
            // non-Responses providers. Codex still receives the final answer and function calls;
            // exposing arbitrary chain-of-thought as a Responses reasoning item would be both
            // semantically wrong and incompatible across providers.
            is StreamChunk.ReasoningStart,
            is StreamChunk.ReasoningDelta,
            is StreamChunk.ReasoningEnd,
            is StreamChunk.ServerToolStart,
            is StreamChunk.ServerToolInputDelta,
            is StreamChunk.ServerToolInputEnd,
            is StreamChunk.ServerToolEnd,
            is StreamChunk.ImageStart,
            is StreamChunk.ImageDelta,
            is StreamChunk.ImageSnapshot,
            is StreamChunk.ImageEnd,
            is StreamChunk.Annotations -> Unit
        }
    }

    fun complete(finishReason: String? = null): List<CodexHarnessResponsesSseEvent> {
        if (completed) return emptyList()
        completed = true
        val response = responseShell(
            status = if (finishReason == null || finishReason == "stop" || finishReason == "tool_calls") {
                "completed"
            } else {
                "incomplete"
            },
            finishReason = finishReason,
        )
        val type = if (response["status"] == JsonPrimitive("completed")) {
            "response.completed"
        } else {
            "response.incomplete"
        }
        return listOf(event(type, "response" to response))
    }

    private fun responseShell(status: String, finishReason: String? = null): JsonObject = JsonObject(buildMap {
        put("id", JsonPrimitive(responseId))
        put("object", JsonPrimitive("response"))
        put("status", JsonPrimitive(status))
        put("model", JsonPrimitive(model))
        put("output", JsonArray(completedItems.toSortedMap().values.toList()))
        usage?.let { put("usage", usageJson(it)) }
        if (status == "incomplete") {
            put("incomplete_details", JsonObject(mapOf(
                "reason" to JsonPrimitive(finishReason ?: "unknown")
            )))
        }
    })

    private fun messageItem(id: String, status: String, text: String): JsonObject = JsonObject(mapOf(
        "id" to JsonPrimitive(id),
        "type" to JsonPrimitive("message"),
        "status" to JsonPrimitive(status),
        "role" to JsonPrimitive("assistant"),
        "content" to JsonArray(listOf(outputTextPart(text))),
    ))

    private fun outputTextPart(text: String): JsonObject = JsonObject(mapOf(
        "type" to JsonPrimitive("output_text"),
        "text" to JsonPrimitive(text),
        "annotations" to JsonArray(emptyList()),
    ))

    private fun functionCallItem(
        itemId: String,
        callId: String,
        name: String,
        arguments: String,
        status: String,
    ): JsonObject = JsonObject(mapOf(
        "id" to JsonPrimitive(itemId),
        "type" to JsonPrimitive("function_call"),
        "status" to JsonPrimitive(status),
        "call_id" to JsonPrimitive(callId),
        "name" to JsonPrimitive(name),
        "arguments" to JsonPrimitive(arguments),
    ))

    private fun usageJson(usage: TokenUsage): JsonObject = JsonObject(mapOf(
        "input_tokens" to JsonPrimitive(usage.promptTokens),
        "output_tokens" to JsonPrimitive(usage.completionTokens),
        "total_tokens" to JsonPrimitive(usage.totalTokens),
        "input_tokens_details" to JsonObject(mapOf(
            "cached_tokens" to JsonPrimitive(usage.cachedTokens)
        )),
    ))

    private fun event(type: String, vararg values: Pair<String, JsonElement>): CodexHarnessResponsesSseEvent {
        val payload = JsonObject(buildMap {
            put("type", JsonPrimitive(type))
            values.forEach { (key, value) -> put(key, value) }
        })
        return CodexHarnessResponsesSseEvent(type, payload)
    }
}

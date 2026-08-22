package me.rerere.rikkahub.data.codex.appserver

import java.io.Closeable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.util.KeyRoulette
import me.rerere.common.http.await
import me.rerere.rikkahub.data.datastore.SettingsStore
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

interface CodexHarnessRawResponsesBackend {
    suspend fun open(
        bearerToken: String?,
        request: JsonObject,
    ): CodexHarnessRawResponsesProxy.OpenedResponse
}

/**
 * Credential-brokered raw Responses proxy for configured OpenAI-compatible providers that already
 * speak the Responses API.
 *
 * The Codex subprocess authenticates only to the app-private loopback gateway. This class resolves
 * the opaque token back to the current RikkaHub provider/model, rewrites the client-controlled
 * model field to the session-authoritative wire model, injects the configured provider credential
 * in-process, and otherwise leaves the Responses payload/stream untouched.
 */
class CodexHarnessRawResponsesProxy(
    private val sessionRegistry: CodexHarnessGatewaySessionRegistry,
    private val settingsStore: SettingsStore,
    private val client: OkHttpClient,
    private val keyRoulette: KeyRoulette = KeyRoulette.default(),
) : CodexHarnessRawResponsesBackend {
    override suspend fun open(
        bearerToken: String?,
        request: JsonObject,
    ): OpenedResponse {
        val session = sessionRegistry.resolve(bearerToken)
            ?: throw CodexHarnessGatewayUnauthorizedException()
        if (session.mode != CodexHarnessExecutionPlan.GatewayMode.RESPONSES_PASSTHROUGH) {
            throw CodexHarnessGatewayRouteException("Selected provider requires Responses translation")
        }

        val settings = settingsStore.settingsFlow.value
        val provider = settings.providers.firstOrNull { it.id == session.providerId }
            ?: throw CodexHarnessGatewayRouteException("Selected provider no longer exists")
        if (!provider.enabled) {
            throw CodexHarnessGatewayRouteException("Selected provider is disabled")
        }
        val openAi = provider as? ProviderSetting.OpenAI
            ?: throw CodexHarnessGatewayRouteException("Selected provider no longer supports raw Responses passthrough")
        if (!openAi.useResponseApi) {
            throw CodexHarnessGatewayRouteException("Selected provider no longer uses the Responses API")
        }
        val model = openAi.models.firstOrNull { it.id == session.modelId }
            ?: throw CodexHarnessGatewayRouteException("Selected model no longer exists")
        if (model.modelId != session.wireModel) {
            throw CodexHarnessGatewayRouteException(
                "Selected model changed after the Codex gateway session was created; reset the Codex session"
            )
        }

        val body = prepareRawResponsesBody(request, model, session.wireModel)
        val upstreamUrl = openAi.baseUrl.trimEnd('/') + "/responses"
        val requestBuilder = Request.Builder()
            .url(upstreamUrl)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header(
                "Authorization",
                "Bearer ${keyRoulette.next(openAi.apiKey, openAi.id.toString())}",
            )

        rawResponsesCustomHeaders(model).forEach { (name, value) ->
            requestBuilder.header(name, value)
        }

        val call = client.newCall(requestBuilder.build())
        return try {
            OpenedResponse(call, call.await())
        } catch (failure: Throwable) {
            call.cancel()
            throw failure
        }
    }

    class OpenedResponse internal constructor(
        private val call: Call,
        val response: Response,
    ) : Closeable {
        val statusCode: Int get() = response.code
        val contentType: String? get() = response.header("Content-Type")

        override fun close() {
            response.close()
            call.cancel()
        }
    }
}

internal fun prepareRawResponsesBody(
    request: JsonObject,
    model: Model,
    authoritativeWireModel: String,
): JsonObject {
    val fields = request.toMutableMap()
    model.customBodies.forEach { custom -> fields[custom.key] = custom.value }
    fields["model"] = JsonPrimitive(authoritativeWireModel)
    return JsonObject(fields)
}

internal fun rawResponsesCustomHeaders(model: Model): List<Pair<String, String>> =
    model.customHeaders.mapNotNull { header ->
        val name = header.name.trim()
        val value = header.value.trim()
        if (name.isEmpty() || value.isEmpty()) return@mapNotNull null
        if (name.equals("authorization", ignoreCase = true) ||
            name.equals("content-type", ignoreCase = true) ||
            name.equals("content-length", ignoreCase = true) ||
            name.equals("host", ignoreCase = true)
        ) {
            return@mapNotNull null
        }
        name to value
    }

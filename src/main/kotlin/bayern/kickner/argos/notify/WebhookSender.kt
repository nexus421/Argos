package bayern.kickner.argos.notify

import bayern.kickner.argos.config.WebhookConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import kotlinx.coroutines.withTimeoutOrNull

/** Upper bound for one webhook delivery including a stalled response body (CIO only bounds connect + headers). */
const val WEBHOOK_TIMEOUT_MS = 15_000L

/**
 * Dispatches an HTTP webhook notification with payload populated from [placeholders].
 * Values are JSON-escaped when the configured `Content-Type` header is `application/json`.
 *
 * @param config Webhook target URL, method, headers, and body template.
 * @param httpClient Ktor HTTP client to use for the call.
 * @param placeholders Map of placeholder key-value pairs to interpolate into [WebhookConfig.bodyTemplate].
 * @param timeoutMillis Delivery timeout covering connect, request and response.
 * @throws IllegalStateException when the endpoint answers with a non-2xx status or does not answer in time.
 */
suspend fun sendWebhook(
    config: WebhookConfig,
    httpClient: HttpClient,
    placeholders: Map<String, String>,
    timeoutMillis: Long = WEBHOOK_TIMEOUT_MS
) {
    val body = renderTemplate(config.bodyTemplate, placeholders, escapeJson = config.sendsJson())

    val response = withTimeoutOrNull(timeoutMillis) {
        httpClient.request(config.url) {
            method = HttpMethod.parse(config.method)
            config.headers.forEach { (key, value) -> header(key, value) }
            setBody(body)
        }
    } ?: throw IllegalStateException("Webhook '${config.id}' timed out after $timeoutMillis ms")

    val ok = response.status.isSuccess()
    if (ok.not()) throw IllegalStateException("Webhook '${config.id}' responded with ${response.status.value} ${response.status.description}")
}

private fun WebhookConfig.sendsJson(): Boolean =
    headers.entries
        .firstOrNull { it.key.equals(HttpHeaders.ContentType, ignoreCase = true) }
        ?.value?.trim()?.startsWith("application/json", ignoreCase = true) ?: false

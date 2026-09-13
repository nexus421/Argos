package bayern.kickner.argos.checks

import bayern.kickner.argos.config.HttpCheckConfig
import bayern.kickner.argos.rethrowCancellation
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.http.charset
import io.ktor.http.contentType
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.io.readByteArray
import java.util.concurrent.ConcurrentHashMap

/** Upper bound of response bytes inspected by `bodyRegex`; protects the heap against huge responses. */
const val HTTP_BODY_LIMIT_BYTES = 1024 * 1024

private val clientWithRedirects by lazy { HttpClient(CIO) { followRedirects = true } }
private val clientWithoutRedirects by lazy { HttpClient(CIO) { followRedirects = false } }
private val compiledRegexes = ConcurrentHashMap<String, Regex>()

/**
 * Executes an HTTP/HTTPS request according to [config] and validates status code and body regex.
 * The body is streamed and only read (up to [HTTP_BODY_LIMIT_BYTES]) when a `bodyRegex` is configured.
 *
 * @param config HTTP check configuration.
 * @param timeoutSeconds Request timeout in seconds.
 * @param client HTTP client to use; defaults to a shared client matching [HttpCheckConfig.followRedirects].
 * @return [CheckResult] indicating whether HTTP status and body criteria were met.
 */
suspend fun executeHttpCheck(
    config: HttpCheckConfig,
    timeoutSeconds: Long,
    client: HttpClient = if (config.followRedirects) clientWithRedirects else clientWithoutRedirects
): CheckResult {
    val startNanos = System.nanoTime()

    return runCatching {
        withTimeout(timeoutSeconds.secondsToMillis()) {
            client.prepareRequest(config.url) {
                method = HttpMethod.parse(config.method)
                config.headers.forEach { (key, value) -> header(key, value) }
            }.execute { response -> evaluate(config, response) }
        }
    }.rethrowCancellation().fold(
        onSuccess = { problems ->
            CheckResult(
                success = problems.isEmpty(),
                responseTimeMs = elapsedMillisSince(startNanos),
                message = problems.joinToString("; ").ifEmpty { null }
            )
        },
        onFailure = {
            val message = if (it is TimeoutCancellationException) "Request timed out after $timeoutSeconds s" else it.message ?: it::class.simpleName
            CheckResult(false, elapsedMillisSince(startNanos), message)
        }
    )
}

/**
 * Returns the list of unmet expectations for [response]; empty means the check passed.
 */
private suspend fun evaluate(config: HttpCheckConfig, response: HttpResponse): List<String> {
    val problems = mutableListOf<String>()

    val statusOk = config.expectedStatusCodes.contains(response.status.value)
    if (statusOk.not()) problems += "HTTP ${response.status.value} not in expected ${config.expectedStatusCodes}"

    val pattern = config.bodyRegex ?: return problems
    val regex = compiledRegexes.getOrPut(pattern) { Regex(pattern) }
    val bodyOk = regex.containsMatchIn(readBodyPrefix(response))
    if (bodyOk.not()) problems += "bodyRegex '$pattern' did not match (first $HTTP_BODY_LIMIT_BYTES bytes inspected)"

    return problems
}

private suspend fun readBodyPrefix(response: HttpResponse): String {
    val bytes = response.bodyAsChannel().readRemaining(HTTP_BODY_LIMIT_BYTES.toLong()).readByteArray()
    val charset = response.contentType()?.charset() ?: Charsets.UTF_8
    return String(bytes, charset)
}

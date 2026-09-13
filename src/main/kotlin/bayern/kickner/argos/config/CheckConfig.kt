package bayern.kickner.argos.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Polymorphic configuration definition for monitor checks.
 */
@Serializable
sealed interface CheckConfig

/**
 * HTTP/HTTPS check configuration.
 *
 * @property url The target endpoint URL to query.
 * @property method The HTTP method to use (e.g. "GET", "POST").
 * @property headers Custom request headers.
 * @property expectedStatusCodes List of accepted HTTP status codes.
 * @property bodyRegex Optional regex pattern expected to match the response body.
 * @property followRedirects Whether HTTP redirects should be automatically followed.
 */
@Serializable
@SerialName("http")
data class HttpCheckConfig(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val expectedStatusCodes: List<Int> = listOf(200),
    val bodyRegex: String? = null,
    val followRedirects: Boolean = true
) : CheckConfig

/**
 * TCP check configuration.
 *
 * @property host The target host or IP address.
 * @property port The target port number.
 */
@Serializable
@SerialName("tcp")
data class TcpCheckConfig(
    val host: String,
    val port: Int
) : CheckConfig

/**
 * ICMP Ping check configuration.
 *
 * @property host The target host or IP address to ping.
 */
@Serializable
@SerialName("ping")
data class PingCheckConfig(
    val host: String
) : CheckConfig

/**
 * DNS resolution check configuration.
 *
 * @property hostname The hostname to resolve.
 * @property expectedIp Optional expected resolved IP address.
 */
@Serializable
@SerialName("dns")
data class DnsCheckConfig(
    val hostname: String,
    val expectedIp: String? = null
) : CheckConfig

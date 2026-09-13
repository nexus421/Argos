package bayern.kickner.argos.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Configuration for a single monitored target.
 *
 * @property id Unique identifier for the monitor.
 * @property name Human-readable name of the monitor.
 * @property intervalSeconds Execution interval in seconds.
 * @property timeoutSeconds Timeout in seconds before a check attempt fails.
 * @property check Specific check definition (HTTP, TCP, Ping, DNS).
 * @property notificationChannelIds Optional list of channel IDs to notify. If null (or omitted), all channels are
 * notified; an empty list deliberately silences the monitor (e.g. a status-page-only monitor).
 */
@Serializable
data class MonitorConfig(
    val id: String,
    val name: String,
    val intervalSeconds: Long,
    val timeoutSeconds: Long,
    val check: CheckConfig,
    val notificationChannelIds: List<String>? = null
)

/**
 * Transport encryption mode for an SMTP channel. The server certificate's hostname is always verified when TLS is used.
 */
@Serializable
enum class SmtpTls {
    /** STARTTLS is required; the connection fails if the server does not offer it (typically port 587). */
    @SerialName("starttls") STARTTLS,

    /** Implicit TLS from the first byte (typically port 465). */
    @SerialName("ssl") SSL,

    /** No encryption at all — credentials travel in plaintext. Only for trusted internal relays. */
    @SerialName("none") NONE
}

/**
 * SMTP notification channel configuration.
 *
 * @property id Unique channel identifier.
 * @property host SMTP server hostname.
 * @property port SMTP server port.
 * @property username Authentication username. Blank disables SMTP AUTH.
 * @property password Authentication password.
 * @property from Sender email address.
 * @property to List of recipient email addresses.
 * @property systemEvents Whether general system lifecycle events should be sent to this channel.
 * @property tls Transport encryption mode, defaults to required STARTTLS.
 */
@Serializable
data class SmtpConfig(
    val id: String,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val from: String,
    val to: List<String>,
    val systemEvents: Boolean = true,
    val tls: SmtpTls = SmtpTls.STARTTLS
)

/**
 * Webhook notification channel configuration.
 *
 * @property id Unique channel identifier.
 * @property url Target webhook URL.
 * @property method HTTP method (defaults to POST).
 * @property headers Custom request headers.
 * @property bodyTemplate Body template with placeholder support (e.g. {{monitorName}}).
 * @property systemEvents Whether general system lifecycle events should be sent to this channel.
 */
@Serializable
data class WebhookConfig(
    val id: String,
    val url: String,
    val method: String = "POST",
    val headers: Map<String, String> = emptyMap(),
    val bodyTemplate: String,
    val systemEvents: Boolean = true
)

/**
 * HTTP Basic Authentication credentials for protecting a status page.
 *
 * @property username Allowed username.
 * @property passwordHash Argon2 hash of the allowed password.
 */
@Serializable
data class BasicAuthConfig(
    val username: String,
    val passwordHash: String
)

/**
 * Configuration for a public or protected status page.
 *
 * @property id URL path identifier (e.g. "public" for /status/public).
 * @property name Page title displayed to visitors.
 * @property monitorIds List of monitor IDs included on this status page.
 * @property basicAuth Optional credentials to require authentication.
 */
@Serializable
data class StatusPageConfig(
    val id: String,
    val name: String,
    val monitorIds: List<String>,
    val basicAuth: BasicAuthConfig? = null
)

/**
 * Root application configuration.
 *
 * @property monitors List of configured monitors.
 * @property smtpChannels Configured SMTP notification channels.
 * @property webhookChannels Configured webhook notification channels.
 * @property statusPages Configured status pages.
 * @property retentionDays Number of days to keep historical check results in SQLite.
 * @property flappingThreshold Number of consecutive check failures required before sending a DOWN alert.
 * @property heartbeatGapMinutesThreshold Maximum expected downtime in minutes before reporting an unplanned restart.
 * @property dataDir Directory where SQLite databases and files are stored.
 * @property webHost Interface the HTTP server binds to. Use 127.0.0.1 when a local reverse proxy is in front.
 * @property webPort Port the HTTP server listens on.
 */
@Serializable
data class AppConfig(
    val monitors: List<MonitorConfig> = emptyList(),
    val smtpChannels: List<SmtpConfig> = emptyList(),
    val webhookChannels: List<WebhookConfig> = emptyList(),
    val statusPages: List<StatusPageConfig> = emptyList(),
    val retentionDays: Int = 365,
    val flappingThreshold: Int = 3,
    val heartbeatGapMinutesThreshold: Int = 2,
    val dataDir: String = "./data",
    val webHost: String = "0.0.0.0",
    val webPort: Int = 8080
)

package bayern.kickner.argos.notify

import bayern.kickner.argos.config.AppConfig
import bayern.kickner.argos.config.SmtpConfig
import bayern.kickner.argos.config.WebhookConfig
import bayern.kickner.argos.rethrowCancellation
import bayern.kickner.klogger.KLogger
import bayern.kickner.klogger.staticLog
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val TAG = "NotificationDispatcher"

/** Pauses between attempts: four tries spread over ~100 s ride out a short SMTP or webhook hiccup. */
val DEFAULT_RETRY_DELAYS_MILLIS: List<Long> = listOf(10_000L, 30_000L, 60_000L)

/** Jakarta Mail blocks a thread per send; an own IO view keeps those threads away from checks and database reads. */
private val mailDispatcher = Dispatchers.IO.limitedParallelism(4)

/**
 * Kind of monitor state change; rendered into the `{{status}}` placeholder.
 */
enum class MonitorEvent { DOWN, UP }

/**
 * Routes system-level and monitor-specific notifications to configured SMTP and Webhook channels.
 * Channels are addressed in parallel; every channel gets `retryDelaysMillis.size + 1` attempts.
 *
 * @property config Application configuration containing notification channels.
 * @property httpClient HTTP client used for dispatching webhooks.
 * @property mailSender SMTP delivery function, replaceable for tests.
 * @property retryDelaysMillis Waits between attempts; empty means a single attempt (tests).
 */
class NotificationDispatcher(
    private val config: AppConfig,
    private val httpClient: HttpClient,
    private val mailSender: (SmtpConfig, String, String) -> Unit = ::sendMail,
    private val retryDelaysMillis: List<Long> = DEFAULT_RETRY_DELAYS_MILLIS
) {
    /** IDs of every configured channel — what `notificationChannelIds = null` resolves to. */
    val allChannelIds: List<String> = config.smtpChannels.map { it.id } + config.webhookChannels.map { it.id }

    /**
     * Dispatches a global system lifecycle notification (e.g. startup or unplanned downtime alert)
     * to every channel with `systemEvents` enabled.
     *
     * @param subject Notification subject line.
     * @param body Notification message body.
     * @return IDs of the channels that could not be reached even after retries; empty means fully delivered.
     */
    suspend fun sendSystemNotification(subject: String, body: String): Set<String> {
        val placeholders = mapOf("status" to "SYSTEM", "subject" to subject, "body" to body)
        return deliver(
            smtpTargets = config.smtpChannels.filter { it.systemEvents },
            webhookTargets = config.webhookChannels.filter { it.systemEvents },
            subject = subject, body = body, placeholders = placeholders, context = "system notification"
        )
    }

    /**
     * Dispatches a monitor-specific status change alert (DOWN or RECOVERY) to matching channels.
     *
     * @param monitorId Unique ID of the monitored target.
     * @param monitorName Display name of the monitored target.
     * @param subject Notification subject line.
     * @param body Notification message body with error details or recovery message.
     * @param event Whether the monitor went DOWN or came back UP.
     * @param channelIds Channel IDs to restrict dispatch to; null means all channels, an empty list means none.
     * @return IDs of the channels that could not be reached even after retries; empty means fully delivered.
     */
    suspend fun sendMonitorNotification(
        monitorId: String,
        monitorName: String,
        subject: String,
        body: String,
        event: MonitorEvent,
        channelIds: List<String>?
    ): Set<String> {
        val placeholders = mapOf(
            "status" to event.name, "monitorId" to monitorId, "monitorName" to monitorName, "subject" to subject, "body" to body
        )
        return deliver(
            smtpTargets = config.smtpChannels.filter { channelIds == null || it.id in channelIds },
            webhookTargets = config.webhookChannels.filter { channelIds == null || it.id in channelIds },
            subject = subject, body = body, placeholders = placeholders, context = "for $monitorName"
        )
    }

    private suspend fun deliver(
        smtpTargets: List<SmtpConfig>,
        webhookTargets: List<WebhookConfig>,
        subject: String,
        body: String,
        placeholders: Map<String, String>,
        context: String
    ): Set<String> = coroutineScope {
        val mails = smtpTargets.map { smtp ->
            async {
                smtp.id to withRetry("SMTP '${smtp.id}' $context") {
                    withContext(mailDispatcher) { mailSender(smtp, subject, body) }
                }
            }
        }
        val hooks = webhookTargets.map { webhook ->
            async {
                webhook.id to withRetry("Webhook '${webhook.id}' $context") {
                    sendWebhook(webhook, httpClient, placeholders)
                }
            }
        }
        (mails + hooks).awaitAll()
            .filterNot { (_, delivered) -> delivered }
            .map { (channelId, _) -> channelId }
            .toSet()
    }

    /**
     * Runs [attempt] until it succeeds or the retry budget is spent. Cancellation propagates immediately and
     * is not logged as a failure.
     */
    private suspend fun withRetry(what: String, attempt: suspend () -> Unit): Boolean {
        val attempts = retryDelaysMillis.size + 1
        for (attemptNo in 1..attempts) {
            val failure = runCatching { attempt() }.rethrowCancellation().exceptionOrNull() ?: return true
            val last = attemptNo == attempts
            if (last) {
                staticLog(KLogger.Level.ERROR, TAG) { "$what failed after $attempts attempt(s), giving up: ${failure.message}" }
            } else {
                val wait = retryDelaysMillis[attemptNo - 1]
                staticLog(KLogger.Level.WARN, TAG) { "$what failed (attempt $attemptNo/$attempts), retrying in ${wait / 1000} s: ${failure.message}" }
                delay(wait)
            }
        }
        return false
    }
}

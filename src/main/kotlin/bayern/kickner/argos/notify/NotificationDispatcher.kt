package bayern.kickner.argos.notify

import bayern.kickner.argos.config.AppConfig
import bayern.kickner.argos.config.SmtpConfig
import bayern.kickner.argos.config.WebhookConfig
import bayern.kickner.argos.rethrowCancellation
import bayern.kickner.klogger.KLogger
import bayern.kickner.klogger.staticLog
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "NotificationDispatcher"

/**
 * Kind of monitor state change; rendered into the `{{status}}` placeholder.
 */
enum class MonitorEvent { DOWN, UP }

/**
 * Routes system-level and monitor-specific notifications to configured SMTP and Webhook channels.
 *
 * @property config Application configuration containing notification channels.
 * @property httpClient HTTP client used for dispatching webhooks.
 * @property mailSender SMTP delivery function, replaceable for tests.
 */
class NotificationDispatcher(
    private val config: AppConfig,
    private val httpClient: HttpClient,
    private val mailSender: (SmtpConfig, String, String) -> Unit = ::sendMail
) {
    /**
     * Dispatches a global system lifecycle notification (e.g. startup or unplanned downtime alert)
     * to every channel with `systemEvents` enabled.
     *
     * @param subject Notification subject line.
     * @param body Notification message body.
     */
    suspend fun sendSystemNotification(subject: String, body: String) {
        val placeholders = mapOf("status" to "SYSTEM", "subject" to subject, "body" to body)
        deliver(
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
     */
    suspend fun sendMonitorNotification(
        monitorId: String,
        monitorName: String,
        subject: String,
        body: String,
        event: MonitorEvent,
        channelIds: List<String>?
    ) {
        val placeholders = mapOf(
            "status" to event.name, "monitorId" to monitorId, "monitorName" to monitorName, "subject" to subject, "body" to body
        )
        deliver(
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
    ) {
        smtpTargets.forEach { smtp ->
            runCatching { withContext(Dispatchers.IO) { mailSender(smtp, subject, body) } }
                .rethrowCancellation()
                .onFailure { staticLog(KLogger.Level.ERROR, TAG) { "SMTP '${smtp.id}' $context failed: ${it.message}" } }
        }
        webhookTargets.forEach { webhook ->
            runCatching { sendWebhook(webhook, httpClient, placeholders) }
                .rethrowCancellation()
                .onFailure { staticLog(KLogger.Level.ERROR, TAG) { "Webhook '${webhook.id}' $context failed: ${it.message}" } }
        }
    }
}

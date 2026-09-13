package bayern.kickner.argos.notify

import bayern.kickner.argos.config.AppConfig
import bayern.kickner.argos.config.SmtpConfig
import bayern.kickner.argos.config.WebhookConfig
import bayern.kickner.klogger.KLogger
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun smtp(id: String, systemEvents: Boolean = true) =
    SmtpConfig(id, "h", 25, "u", "p", "a@b", listOf("x@y"), systemEvents = systemEvents)

private fun webhook(id: String, systemEvents: Boolean = true) =
    WebhookConfig(id, "https://hooks.example/$id", bodyTemplate = "{{status}}|{{subject}}|{{monitorId}}", systemEvents = systemEvents)

private val config = AppConfig(
    smtpChannels = listOf(smtp("mail-a"), smtp("mail-sys-off", systemEvents = false)),
    webhookChannels = listOf(webhook("hook-a"), webhook("hook-sys-off", systemEvents = false))
)

private class Recorder(delayMs: Long = 0) {
    val mails = mutableListOf<String>()
    val hooks = mutableListOf<Pair<String, String>>()
    val client = HttpClient(MockEngine { request ->
        if (delayMs > 0) delay(delayMs)
        hooks += request.url.encodedPath.trimStart('/') to String(request.body.toByteArray())
        respond("", HttpStatusCode.OK)
    })
    val mailSender: (SmtpConfig, String, String) -> Unit = { smtp, subject, _ -> mails += "${smtp.id}:$subject" }
    fun dispatcher() = NotificationDispatcher(config, client, mailSender)
}

class NotificationDispatcherTest : FunSpec({

    test("null channel list notifies every channel, including systemEvents=false ones") {
        val recorder = Recorder()
        recorder.dispatcher().sendMonitorNotification("m1", "API", "API is DOWN", "detail", MonitorEvent.DOWN, channelIds = null)

        recorder.mails shouldContainExactly listOf("mail-a:API is DOWN", "mail-sys-off:API is DOWN")
        recorder.hooks.map { it.first } shouldContainExactly listOf("hook-a", "hook-sys-off")
    }

    test("empty channel list notifies nobody") {
        val recorder = Recorder()
        recorder.dispatcher().sendMonitorNotification("m1", "API", "API is DOWN", "detail", MonitorEvent.DOWN, channelIds = emptyList())

        recorder.mails.shouldBeEmpty()
        recorder.hooks.shouldBeEmpty()
    }

    test("explicit channel list restricts delivery to those channels") {
        val recorder = Recorder()
        recorder.dispatcher().sendMonitorNotification("m1", "API", "API is UP again", "Recovered", MonitorEvent.UP, channelIds = listOf("hook-a"))

        recorder.mails.shouldBeEmpty()
        recorder.hooks.map { it.first } shouldContainExactly listOf("hook-a")
    }

    test("status placeholder carries DOWN, UP or SYSTEM") {
        val recorder = Recorder()
        val dispatcher = recorder.dispatcher()
        dispatcher.sendMonitorNotification("m1", "API", "s", "b", MonitorEvent.DOWN, listOf("hook-a"))
        dispatcher.sendMonitorNotification("m1", "API", "s", "b", MonitorEvent.UP, listOf("hook-a"))
        dispatcher.sendSystemNotification("Argos started", "b")

        recorder.hooks.map { it.second } shouldContainExactly listOf("DOWN|s|m1", "UP|s|m1", "SYSTEM|Argos started|{{monitorId}}")
    }

    test("system notifications only go to channels with systemEvents enabled") {
        val recorder = Recorder()
        recorder.dispatcher().sendSystemNotification("Argos started", "b")

        recorder.mails shouldContainExactly listOf("mail-a:Argos started")
        recorder.hooks.map { it.first } shouldContainExactly listOf("hook-a")
    }

    test("a failing channel does not prevent delivery to the others") {
        val recorder = Recorder()
        val failingMail: (SmtpConfig, String, String) -> Unit = { _, _, _ -> throw IllegalStateException("smtp down") }
        NotificationDispatcher(config, recorder.client, failingMail).sendSystemNotification("Argos started", "b")

        recorder.hooks.map { it.first } shouldContainExactly listOf("hook-a")
    }

    test("cancellation stops delivery and is not logged as a channel failure") {
        val logged = mutableListOf<String>()
        KLogger.configure { logToCustom { _, _, message -> logged += message } }
        val recorder = Recorder(delayMs = 5000)

        coroutineScope {
            val job = launch { recorder.dispatcher().sendSystemNotification("Argos started", "b") }
            delay(200)
            job.cancelAndJoin()
        }

        logged.filter { it.contains("failed") }.shouldBeEmpty()
        recorder.hooks.size shouldBe 0
    }
})

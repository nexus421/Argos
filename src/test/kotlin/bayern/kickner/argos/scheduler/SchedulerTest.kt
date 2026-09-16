package bayern.kickner.argos.scheduler

import bayern.kickner.argos.config.AppConfig
import bayern.kickner.argos.config.MonitorConfig
import bayern.kickner.argos.config.TcpCheckConfig
import bayern.kickner.argos.config.WebhookConfig
import bayern.kickner.argos.db.AppDatabase
import bayern.kickner.argos.db.connectDatabase
import bayern.kickner.argos.db.latestResults
import bayern.kickner.argos.db.pendingAlerts
import bayern.kickner.argos.notify.NotificationDispatcher
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.net.ServerSocket
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/** A closed port every TCP check fails on quickly; the socket can be re-opened to simulate a recovery. */
private fun freePort(): Int = ServerSocket(0).use { it.localPort }

private fun config(port: Int) = AppConfig(
    monitors = listOf(MonitorConfig("m1", "M", intervalSeconds = 1, timeoutSeconds = 1, check = TcpCheckConfig("127.0.0.1", port))),
    webhookChannels = listOf(WebhookConfig("hook", "https://hooks.example/x", bodyTemplate = "{{status}}|{{body}}")),
    flappingThreshold = 1
)

private class Harness(port: Int = freePort()) {
    val config = config(port)
    val deliveries: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val endpointDown = AtomicBoolean(false)
    val client = HttpClient(MockEngine { request ->
        if (endpointDown.get()) respond("", HttpStatusCode.ServiceUnavailable)
        else {
            deliveries += String(request.body.toByteArray())
            respond("", HttpStatusCode.OK)
        }
    })
    val db: AppDatabase = connectDatabase(Files.createTempDirectory("argos-scheduler").toFile().absolutePath)
    val scope = CoroutineScope(SupervisorJob())
    val notificationScope = CoroutineScope(SupervisorJob())
    val scheduler = Scheduler(config, db.database, scope, notificationScope, NotificationDispatcher(config, client, retryDelaysMillis = emptyList()))

    suspend fun start() {
        scheduler.restoreState()
        scheduler.start()
    }

    fun close() {
        scope.cancel()
        notificationScope.cancel()
        db.close()
    }
}

class SchedulerTest : FunSpec({

    test("a DOWN alert is queued with the result, delivered once, then removed; the monitor is released for the next tick") {
        val harness = Harness()
        harness.start()

        eventually(6.seconds) {
            harness.deliveries.map { it.substringBefore('|') } shouldBe listOf("DOWN") // exactly one although the monitor stays DOWN
            pendingAlerts(harness.db.database).shouldBeEmpty()                        // confirmed -> row removed
            latestResults(harness.db.database, "m1", 10).size shouldBeGreaterThan 1   // next ticks ran: inFlight was released
        }
        // A redelivered DOWN would otherwise look like a fresh one; webhooks carry no timestamp of their own
        harness.deliveries.single().substringAfter('|') shouldStartWith "Detected at 20"
        harness.close()
    }

    test("an alert whose delivery failed stays queued and is delivered by the next redelivery pass") {
        val harness = Harness()
        harness.endpointDown.set(true)
        harness.start()

        eventually(6.seconds) {
            pendingAlerts(harness.db.database).single().channelIds shouldBe listOf("hook")
        }
        harness.deliveries.shouldBeEmpty()

        harness.endpointDown.set(false)
        // The failed delivery releases its claim on the alert only after narrowing the row, and a pass that arrives
        // in that window skips the alert by design — so keep triggering passes, as the periodic retry does.
        eventually(6.seconds) {
            harness.scheduler.redeliverPendingAlerts()
            harness.deliveries.map { it.substringBefore('|') } shouldBe listOf("DOWN")
            pendingAlerts(harness.db.database).shouldBeEmpty()
        }
        harness.close()
    }

    test("concurrent redelivery passes over the same queued alert deliver it exactly once") {
        val harness = Harness()
        harness.endpointDown.set(true)
        harness.start()
        eventually(6.seconds) { pendingAlerts(harness.db.database).single() }
        harness.scope.cancel() // stop the ticker so only the explicit passes below touch the queue

        harness.endpointDown.set(false)
        coroutineScope { repeat(5) { launch { harness.scheduler.redeliverPendingAlerts() } } }

        eventually(6.seconds) {
            pendingAlerts(harness.db.database).shouldBeEmpty()
            harness.deliveries.map { it.substringBefore('|') } shouldBe listOf("DOWN")
        }
        harness.close()
    }

    test("the recovery alert names the start and length of the outage") {
        val port = freePort()
        val harness = Harness(port)
        harness.start()
        eventually(6.seconds) { harness.deliveries.map { it.substringBefore('|') } shouldBe listOf("DOWN") }

        ServerSocket(port).use {
            eventually(6.seconds) {
                harness.deliveries.map { it.substringBefore('|') } shouldBe listOf("DOWN", "UP")
            }
        }

        val recovery = harness.deliveries.last().substringAfter('|')
        recovery shouldContain "Down since"
        recovery shouldContain " s)"
        harness.close()
    }
})

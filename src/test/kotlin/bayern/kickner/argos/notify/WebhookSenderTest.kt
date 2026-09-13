package bayern.kickner.argos.notify

import bayern.kickner.argos.config.WebhookConfig
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay

private fun clientRecording(status: HttpStatusCode = HttpStatusCode.OK, sink: MutableList<Pair<HttpRequestData, String>>): HttpClient =
    HttpClient(MockEngine { request ->
        sink += request to String(request.body.toByteArray())
        respond("", status)
    })

class WebhookSenderTest : FunSpec({

    test("escapes placeholder values when the template is sent as JSON") {
        val requests = mutableListOf<Pair<HttpRequestData, String>>()
        val config = WebhookConfig(
            id = "slack", url = "https://hooks.example/x",
            headers = mapOf("content-type" to "application/json; charset=utf-8"),
            bodyTemplate = """{"text": "{{subject}}: {{body}}"}"""
        )

        sendWebhook(config, clientRecording(sink = requests), mapOf("subject" to "API is DOWN", "body" to "Got \"503\"\nretry"))

        requests.single().second shouldBe """{"text": "API is DOWN: Got \"503\"\nretry"}"""
    }

    test("leaves placeholder values raw for non-JSON templates") {
        val requests = mutableListOf<Pair<HttpRequestData, String>>()
        val config = WebhookConfig(id = "text", url = "https://hooks.example/x", bodyTemplate = "{{body}}")

        sendWebhook(config, clientRecording(sink = requests), mapOf("body" to "Got \"503\"\nretry"))

        requests.single().second shouldBe "Got \"503\"\nretry"
    }

    test("applies method and headers") {
        val requests = mutableListOf<Pair<HttpRequestData, String>>()
        val config = WebhookConfig(
            id = "put", url = "https://hooks.example/x", method = "PUT",
            headers = mapOf("Authorization" to "Bearer t"), bodyTemplate = "x"
        )

        sendWebhook(config, clientRecording(sink = requests), emptyMap())

        requests.single().first.method.value shouldBe "PUT"
        requests.single().first.headers["Authorization"] shouldBe "Bearer t"
    }

    test("fails loudly when the webhook endpoint responds with a non-2xx status") {
        val config = WebhookConfig(id = "broken", url = "https://hooks.example/x", bodyTemplate = "x")

        val error = shouldThrowAny { sendWebhook(config, clientRecording(HttpStatusCode.BadRequest, mutableListOf()), emptyMap()) }

        error.message shouldContain "broken"
        error.message shouldContain "400"
    }

    test("fails with a timeout when the endpoint stalls") {
        val stalling = HttpClient(MockEngine { delay(5000); respond("", HttpStatusCode.OK) })
        val config = WebhookConfig(id = "slow", url = "https://hooks.example/x", bodyTemplate = "x")

        val error = shouldThrowAny { sendWebhook(config, stalling, emptyMap(), timeoutMillis = 200) }

        error.message shouldContain "slow"
        error.message shouldContain "timed out"
    }
})

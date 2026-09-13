package bayern.kickner.argos.checks

import bayern.kickner.argos.config.HttpCheckConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.kotest.matchers.nulls.shouldBeNull
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun client(status: HttpStatusCode = HttpStatusCode.OK, body: String = "ok", delayMs: Long = 0, seen: MutableList<HttpRequestData> = mutableListOf()) =
    HttpClient(MockEngine { request ->
        seen += request
        if (delayMs > 0) delay(delayMs)
        respond(body, status, headersOf("Content-Type", "text/plain; charset=utf-8"))
    })

class HttpCheckTest : FunSpec({

    val base = HttpCheckConfig(url = "https://svc.example/health")

    test("succeeds when the status code is expected") {
        executeHttpCheck(base, timeoutSeconds = 2, client = client()).success shouldBe true
    }

    test("fails with the actual status code in the message when it is unexpected") {
        val result = executeHttpCheck(base, timeoutSeconds = 2, client = client(HttpStatusCode.ServiceUnavailable))

        result.success shouldBe false
        result.message shouldContain "503"
        result.message shouldContain "200"
    }

    test("checks bodyRegex against the response body") {
        val config = base.copy(bodyRegex = "\"status\"\\s*:\\s*\"UP\"")

        executeHttpCheck(config, 2, client(body = """{"status": "UP"}""")).success shouldBe true
        val mismatch = executeHttpCheck(config, 2, client(body = """{"status": "DOWN"}"""))
        mismatch.success shouldBe false
        mismatch.message shouldContain "bodyRegex"
    }

    test("only the first HTTP_BODY_LIMIT_BYTES of the body are inspected") {
        val filler = "x".repeat(HTTP_BODY_LIMIT_BYTES)
        val config = base.copy(bodyRegex = "MARKER")

        executeHttpCheck(config, 2, client(body = "MARKER$filler")).success shouldBe true
        executeHttpCheck(config, 2, client(body = "${filler}MARKER")).success shouldBe false
    }

    test("sends configured method and headers") {
        val seen = mutableListOf<HttpRequestData>()
        val config = base.copy(method = "HEAD", headers = mapOf("X-Token" to "abc"))

        executeHttpCheck(config, 2, client(seen = seen))

        seen.single().method.value shouldBe "HEAD"
        seen.single().headers["X-Token"] shouldBe "abc"
    }

    test("fails with a timeout message when the request exceeds timeoutSeconds") {
        val result = executeHttpCheck(base, timeoutSeconds = 1, client = client(delayMs = 5000))

        result.success shouldBe false
        result.message shouldContain "timed out"
    }

    test("cancellation of the caller is not reported as a failed check") {
        var result: CheckResult? = null
        coroutineScope {
            val job = launch { result = executeHttpCheck(base, timeoutSeconds = 10, client = client(delayMs = 5000)) }
            delay(200)
            job.cancelAndJoin()
        }
        result.shouldBeNull()
    }
})

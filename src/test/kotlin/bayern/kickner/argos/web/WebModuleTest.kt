package bayern.kickner.argos.web

import bayern.kickner.argos.config.AppConfig
import bayern.kickner.argos.config.BasicAuthConfig
import bayern.kickner.argos.config.ConfigError
import bayern.kickner.argos.config.MonitorConfig
import bayern.kickner.argos.config.StatusPageConfig
import bayern.kickner.argos.config.TcpCheckConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.time.Instant

private val config = AppConfig(
    monitors = listOf(
        MonitorConfig("m1", "Public API", 60, 10, TcpCheckConfig("h", 1)),
        MonitorConfig("m2", "Database", 60, 10, TcpCheckConfig("h", 2))
    ),
    statusPages = listOf(
        StatusPageConfig("public", "Public Status", listOf("m1", "m2")),
        StatusPageConfig("private", "Private Status", listOf("m1"), BasicAuthConfig("admin", hashPassword("secret")))
    )
)

private val allStatuses = listOf(
    MonitorStatus("m1", "Public API", MonitorState.UP, Instant.parse("2026-01-01T12:00:00Z"), 0.036, null),
    MonitorStatus("m2", "Database", MonitorState.DOWN, Instant.parse("2026-01-01T12:00:30Z"), 5000.0, "Connection refused <script>")
)

private val statusSource = StatusSource { page -> allStatuses.filter { it.id in page.monitorIds } }

class WebModuleTest : FunSpec({

    test("status page shows monitor names, state, latency and last check time") {
        testApplication {
            application { configureWeb(config, statusSource) }

            val html = client.get("/status/public").bodyAsText()

            html shouldContain "Public API"
            html shouldContain "Database"
            html shouldContain "UP"
            html shouldContain "DOWN"
            html shouldContain "0.04 ms"
            html shouldContain "5000 ms"
            html shouldContain "2026-01-01T12:00:30Z"
        }
    }

    test("protected status page HTML-escapes error messages") {
        val protectedConfig = config.copy(statusPages = listOf(StatusPageConfig("both", "Both", listOf("m1", "m2"), BasicAuthConfig("admin", hashPassword("secret")))))
        testApplication {
            application { configureWeb(protectedConfig, statusSource) }

            val html = client.get("/status/both") { basicAuth("admin", "secret") }.bodyAsText()

            html shouldContain "&lt;script&gt;"
            html shouldNotContain "<script>"
        }
    }

    test("public status page shows state but hides error details, protected page shows them") {
        val protectedConfig = config.copy(statusPages = config.statusPages + StatusPageConfig("both", "Both", listOf("m1", "m2"), BasicAuthConfig("admin", hashPassword("secret"))))
        testApplication {
            application { configureWeb(protectedConfig, statusSource) }

            val public = client.get("/status/public").bodyAsText()
            public shouldContain "DOWN"
            public shouldNotContain "Connection refused"

            val protected = client.get("/status/both") { basicAuth("admin", "secret") }.bodyAsText()
            protected shouldContain "Connection refused"
        }
    }

    test("status page only lists the monitors assigned to it") {
        testApplication {
            application { configureWeb(config, statusSource) }

            val html = client.get("/status/private") { basicAuth("admin", "secret") }.bodyAsText()

            html shouldContain "Public API"
            html shouldNotContain "Database"
        }
    }

    test("protected status page requires valid Basic Auth credentials") {
        testApplication {
            application { configureWeb(config, statusSource) }

            client.get("/status/private").status shouldBe HttpStatusCode.Unauthorized
            client.get("/status/private") { basicAuth("admin", "wrong") }.status shouldBe HttpStatusCode.Unauthorized
            client.get("/status/private") { basicAuth("other", "secret") }.status shouldBe HttpStatusCode.Unauthorized
            client.get("/status/private") { basicAuth("admin", "secret") }.status shouldBe HttpStatusCode.OK
        }
    }

    test("without a config the root answers 503 with the reason and the setup page is served") {
        testApplication {
            application { configureWeb(null, statusSource, ConfigError.ValidationError(listOf("Monitor 'x': intervalSeconds must be positive"))) }

            val root = client.get("/")
            root.status shouldBe HttpStatusCode.ServiceUnavailable
            root.bodyAsText() shouldContain "/setup"
            root.bodyAsText() shouldContain "intervalSeconds must be positive"
            client.get("/setup").status shouldBe HttpStatusCode.OK
            client.get("/status/public").status shouldBe HttpStatusCode.NotFound
        }
    }

    test("a missing config file is reported without exposing the path") {
        testApplication {
            application { configureWeb(null, statusSource, ConfigError.FileNotFound("/opt/argos/config.json")) }

            val root = client.get("/")
            root.status shouldBe HttpStatusCode.ServiceUnavailable
            root.bodyAsText() shouldContain "not found"
            root.bodyAsText() shouldNotContain "/opt/argos"
        }
    }

    test("with a valid config the root answers 200") {
        testApplication {
            application { configureWeb(config, statusSource) }
            client.get("/").status shouldBe HttpStatusCode.OK
        }
    }
})

package bayern.kickner.argos.web

import bayern.kickner.argos.config.AppConfig
import bayern.kickner.argos.config.BasicAuthConfig
import bayern.kickner.argos.config.ConfigError
import bayern.kickner.argos.config.MonitorConfig
import bayern.kickner.argos.config.StatusPageConfig
import bayern.kickner.argos.config.TcpCheckConfig
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.net.URI
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

    test("without a config the root answers 503 with the category only — details stay in the log — and the setup page is served") {
        testApplication {
            application { configureWeb(null, statusSource, ConfigError.ValidationError(listOf("Monitor 'x': intervalSeconds must be positive", "Monitor 'db': host 'db.internal.corp' must be a hostname"))) }

            val root = client.get("/")
            root.status shouldBe HttpStatusCode.ServiceUnavailable
            root.bodyAsText() shouldContain "/setup"
            root.bodyAsText() shouldContain "invalid (2 issue(s))"
            root.bodyAsText() shouldContain "server log"
            root.bodyAsText() shouldNotContain "intervalSeconds"
            root.bodyAsText() shouldNotContain "db.internal.corp"
            client.get("/setup").status shouldBe HttpStatusCode.OK
            client.get("/status/public").status shouldBe HttpStatusCode.NotFound
        }
    }

    test("parse errors, unreadable files and an unusable data directory are reported without their details") {
        testApplication {
            application { configureWeb(null, statusSource, ConfigError.ParseError("Failed to parse type 'Int' for input 'geheim'")) }
            client.get("/").bodyAsText().let { it shouldContain "could not be parsed"; it shouldNotContain "geheim" }
        }
        testApplication {
            application { configureWeb(null, statusSource, ConfigError.Unreadable("/opt/argos/config.json (Permission denied)")) }
            client.get("/").bodyAsText().let { it shouldContain "could not be read"; it shouldNotContain "/opt/argos" }
        }
        testApplication {
            application { configureWeb(null, statusSource, ConfigError.DataDirUnusable("'/opt/argos/data' is not a writable directory")) }
            val root = client.get("/")
            root.status shouldBe HttpStatusCode.ServiceUnavailable
            root.bodyAsText().let { it shouldContain "data directory"; it shouldNotContain "/opt/argos" }
        }
    }

    test("every response carries nosniff; only authenticated status pages are marked no-store") {
        testApplication {
            application { configureWeb(config, statusSource) }

            client.get("/").headers["X-Content-Type-Options"] shouldBe "nosniff"
            client.get("/setup").headers["X-Content-Type-Options"] shouldBe "nosniff"
            client.get("/status/public").headers[HttpHeaders.CacheControl].shouldBeNull()
            client.get("/status/private") { basicAuth("admin", "secret") }.headers[HttpHeaders.CacheControl] shouldBe "no-store"
        }
    }

    test("every script and stylesheet the setup page references resolves from /setup, with and without a config") {
        listOf(config, null).forEach { current ->
            testApplication {
                application { configureWeb(current, statusSource) }

                val html = client.get("/setup").bodyAsText()
                val references = Regex("""(?:src|href)="([^"]+)"""").findAll(html).map { it.groupValues[1] }.toList()
                references shouldContain "/setup/config-model.js"
                references shouldContain "/setup/editor.js"
                references shouldContain "/setup/editor.css"
                references.forEach { reference ->
                    val resolved = URI("http://localhost/setup").resolve(reference).path
                    withClue("$reference resolved from /setup as $resolved") { client.get(resolved).status shouldBe HttpStatusCode.OK }
                }
            }
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

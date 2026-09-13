package bayern.kickner.argos.web

import bayern.kickner.argos.config.AppConfig
import bayern.kickner.argos.config.ConfigError
import bayern.kickner.argos.config.StatusPageConfig
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.basic
import io.ktor.http.HttpStatusCode
import io.ktor.server.html.respondHtml
import io.ktor.server.http.content.staticResources
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.html.body
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.style
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.title
import kotlinx.html.tr
import kotlinx.html.unsafe
import kotnexlib.crypto.Argon2Helper

/**
 * Argon2 verification costs ~64 MiB and ~100 ms each. Bounding the parallelism caps the memory an
 * unauthenticated burst can pin and keeps the work off the scheduler's Default dispatcher.
 */
private val argonDispatcher = Dispatchers.IO.limitedParallelism(2)

private const val STATUS_PAGE_CSS = """
:root { color-scheme: light dark; --bg: #fff; --fg: #222; --line: #ddd; --muted: #6b7280; --up: #1a7f37; --down: #b91c1c; }
@media (prefers-color-scheme: dark) { :root { --bg: #1e1e1e; --fg: #ddd; --line: #444; --muted: #9ca3af; --up: #4ade80; --down: #f87171; } }
body { font-family: sans-serif; max-width: 900px; margin: 2rem auto; padding: 0 1rem; background: var(--bg); color: var(--fg); }
table { border-collapse: collapse; width: 100%; }
th, td { text-align: left; padding: 0.5rem; border-bottom: 1px solid var(--line); vertical-align: top; }
.UP { color: var(--up); font-weight: bold; }
.DOWN { color: var(--down); font-weight: bold; }
.UNKNOWN { color: var(--muted); font-weight: bold; }
.muted { color: var(--muted); font-size: 0.9em; }
"""

/**
 * Configures the Ktor web server routes, status pages, authentication, and static setup UI.
 *
 * @param config Application configuration or null if running in empty bootstrap state.
 * @param statusSource Provider of the rows shown on status pages.
 * @param configError Why no configuration is loaded; rendered on `/` so a health check notices the dead state.
 */
fun Application.configureWeb(config: AppConfig?, statusSource: StatusSource, configError: ConfigError? = null) {
    if (config != null) {
        installBasicAuthProviders(config)
    }

    routing {
        staticResources("/setup", "static")

        if (config == null) {
            // 503 on purpose: the process is up but monitoring is not, and only a non-2xx makes that visible to systemd-external checks
            get("/") {
                call.respondText(
                    "No valid configuration loaded — monitoring is NOT running. ${describe(configError)} Open /setup to generate a JSON configuration.",
                    status = HttpStatusCode.ServiceUnavailable
                )
            }
            return@routing
        }

        get("/") {
            call.respondText("Argos is running. Monitored targets: ${config.monitors.size}. Status pages: ${config.statusPages.size}.")
        }

        config.statusPages.forEach { page ->
            if (page.basicAuth != null) {
                authenticate("auth-${page.id}") {
                    get("/status/${page.id}") { call.respondStatusPage(page, statusSource.statusesFor(page), showDetails = true) }
                }
            } else {
                get("/status/${page.id}") { call.respondStatusPage(page, statusSource.statusesFor(page), showDetails = false) }
            }
        }
    }
}

/**
 * Human-readable reason for the bootstrap state. The file path is deliberately omitted: `/` is public.
 */
private fun describe(error: ConfigError?): String = when (error) {
    null -> "No configuration file was given."
    is ConfigError.FileNotFound -> "The configuration file was not found."
    is ConfigError.ParseError -> "The configuration could not be parsed: ${error.message}."
    is ConfigError.ValidationError -> "The configuration is invalid: ${error.issues.joinToString("; ")}."
}

/**
 * Registers HTTP Basic Authentication providers for status pages requiring credentials.
 * The hash is always verified (also for a wrong username) so response timing does not reveal valid usernames.
 */
private fun Application.installBasicAuthProviders(config: AppConfig) {
    install(Authentication) {
        config.statusPages.forEach { page ->
            val auth = page.basicAuth ?: return@forEach
            basic("auth-${page.id}") {
                realm = "Argos Status Page: ${page.name}"
                validate { credentials ->
                    val hashOk = withContext(argonDispatcher) {
                        Argon2Helper.verify(credentials.password.toCharArray(), auth.passwordHash).getOrDefault(false)
                    }
                    if (credentials.name == auth.username && hashOk) UserIdPrincipal(credentials.name) else null
                }
            }
        }
    }
}

/**
 * Renders the HTML response for a status page. kotlinx.html escapes all text nodes.
 *
 * @param showDetails Whether error messages are rendered; they name internal hosts and ports, so only
 * authenticated pages show them.
 */
private suspend fun RoutingCall.respondStatusPage(page: StatusPageConfig, statuses: List<MonitorStatus>, showDetails: Boolean) {
    respondHtml {
        head {
            meta(charset = "UTF-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1")
            meta { httpEquiv = "refresh"; content = "30" }
            title { +page.name }
            style { unsafe { raw(STATUS_PAGE_CSS) } }
        }
        body {
            h1 { +page.name }
            table {
                thead {
                    tr {
                        th { +"Service" }
                        th { +"Status" }
                        th { +"Latency" }
                        th { +"Last check" }
                    }
                }
                tbody {
                    statuses.forEach { status ->
                        tr {
                            td {
                                +status.name
                                if (showDetails) status.message?.let { p(classes = "muted") { +it } }
                            }
                            td(classes = status.state.name) { +status.state.name }
                            td { +(status.responseTimeMs?.let { formatLatency(it) } ?: "–") }
                            td { +(status.lastCheck?.toString() ?: "never") }
                        }
                    }
                }
            }
            p(classes = "muted") { +"Refreshes every 30 s." }
        }
    }
}

package bayern.kickner.argos.web

import bayern.kickner.argos.config.AppConfig
import bayern.kickner.argos.config.ConfigError
import bayern.kickner.argos.config.StatusPageConfig
import bayern.kickner.argos.formatUtc
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.html.*
import io.ktor.server.http.content.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.html.*
import java.security.MessageDigest

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
tr.history td { padding-top: 0; }
svg.history { display: block; width: 100%; height: 40px; }
.bar-ok { fill: var(--up); }
.bar-failed { fill: var(--down); }
.bar-nodata { fill: var(--line); }
.brand { display: flex; align-items: center; gap: 0.75rem; margin-bottom: 0.5rem; }
.brand h1 { margin: 0; }
.nav-icon { border-radius: 6px; flex-shrink: 0; }
"""

/**
 * Configures the Ktor web server routes, status pages, authentication, and static setup UI.
 *
 * @param config Application configuration or null if running in empty bootstrap state.
 * @param statusSource Provider of the rows shown on status pages.
 * @param configError Why no configuration is loaded; `/` then answers 503 so a health check notices the dead state.
 */
fun Application.configureWeb(config: AppConfig?, statusSource: StatusSource, configError: ConfigError? = null) {
    // `/` and `/setup` are public; nothing served here should ever be sniffed into another content type
    intercept(ApplicationCallPipeline.Plugins) {
        call.response.header("X-Content-Type-Options", "nosniff")
    }

    if (config != null) {
        installBasicAuthProviders(config)
    }

    routing {
        staticResources("/setup", "static")

        get("/favicon.ico") {
            val favicon = javaClass.getResourceAsStream("/static/favicon.svg")?.readBytes()
            if (favicon != null) {
                call.respondBytes(favicon, ContentType.Image.SVG)
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }
        get("/favicon.svg") {
            val favicon = javaClass.getResourceAsStream("/static/favicon.svg")?.readBytes()
            if (favicon != null) {
                call.respondBytes(favicon, ContentType.Image.SVG)
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }

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
 * Category of the bootstrap reason for the public `/`. Details (paths, hostnames, offending values) stay in the log.
 */
private fun describe(error: ConfigError?): String {
    val reason = when (error) {
        null -> "No configuration file was given."
        is ConfigError.FileNotFound -> "The configuration file was not found."
        is ConfigError.Unreadable -> "The configuration file could not be read."
        is ConfigError.ParseError -> "The configuration could not be parsed."
        is ConfigError.ValidationError -> "The configuration is invalid (${error.issues.size} issue(s))."
        is ConfigError.DataDirUnusable -> "The data directory could not be opened."
    }
    return "$reason Details are in the server log."
}

/**
 * Registers HTTP Basic Authentication providers for status pages requiring credentials.
 * Both fields are compared in constant time and always both (non-short-circuit `and`), so response timing reveals
 * neither a valid username nor how many password characters matched.
 */
private fun Application.installBasicAuthProviders(config: AppConfig) {
    install(Authentication) {
        config.statusPages.forEach { page ->
            val auth = page.basicAuth ?: return@forEach
            basic("auth-${page.id}") {
                realm = "Argos Status Page: ${page.name}"
                validate { credentials ->
                    val nameOk = MessageDigest.isEqual(credentials.name.toByteArray(), auth.username.toByteArray())
                    val passwordOk =
                        MessageDigest.isEqual(credentials.password.toByteArray(), auth.password.toByteArray())
                    if (nameOk and passwordOk) UserIdPrincipal(credentials.name) else null
                }
            }
        }
    }
}

/**
 * Renders the HTML response for a status page. kotlinx.html escapes all text nodes.
 *
 * @param showDetails Whether error messages are rendered; they name internal hosts and ports, so only
 * authenticated pages show them — and those must not linger in a browser cache.
 */
private suspend fun RoutingCall.respondStatusPage(page: StatusPageConfig, statuses: List<MonitorStatus>, showDetails: Boolean) {
    if (showDetails) response.header(HttpHeaders.CacheControl, "no-store")
    respondHtml {
        head {
            meta(charset = "UTF-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1")
            meta { httpEquiv = "refresh"; content = "30" }
            title { +page.name }
            link(rel = "icon", href = "/setup/favicon.svg", type = "image/svg+xml")
            style { unsafe { raw(STATUS_PAGE_CSS) } }
        }
        body {
            div(classes = "brand") {
                img(src = "/setup/favicon.svg", alt = "Argos", classes = "nav-icon") {
                    attributes["width"] = "32"
                    attributes["height"] = "32"
                }
                h1 { +page.name }
            }
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
                            td { +(status.lastCheck?.let { formatUtc(it) } ?: "never") }
                        }
                        // One bar per day of the last HISTORY_DAYS; numbers only, so `raw` is safe on public pages
                        tr(classes = "history") {
                            td { attributes["colspan"] = "4"; unsafe { raw(renderHistorySvg(status.history)) } }
                        }
                    }
                }
            }
            p(classes = "muted") { +"Refreshes every 30 s." }
        }
    }
}

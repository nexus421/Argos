package bayern.kickner.argos

import bayern.kickner.argos.checks.clientWithRedirects
import bayern.kickner.argos.checks.defaultPingBinary
import bayern.kickner.argos.checks.probeIcmp
import bayern.kickner.argos.config.AppConfig
import bayern.kickner.argos.config.ConfigError
import bayern.kickner.argos.config.PingCheckConfig
import bayern.kickner.argos.config.loadConfig
import bayern.kickner.argos.db.connectDatabase
import bayern.kickner.argos.notify.NotificationDispatcher
import bayern.kickner.argos.scheduler.Scheduler
import bayern.kickner.argos.selfmonitor.hasUnplannedGap
import bayern.kickner.argos.selfmonitor.readLastHeartbeat
import bayern.kickner.argos.selfmonitor.writeHeartbeat
import bayern.kickner.argos.web.StatusService
import bayern.kickner.argos.web.configureWeb
import bayern.kickner.argos.web.hashPassword
import bayern.kickner.klogger.KLogger
import bayern.kickner.klogger.staticLog
import io.ktor.server.engine.*
import kotlinx.coroutines.*
import kotnexlib.ArgsInterpreter
import kotnexlib.ResultOf2
import java.time.Instant
import java.util.*
import kotlin.time.Duration.Companion.milliseconds
import io.ktor.server.cio.CIO as ServerCIO

private const val TAG = "Main"

/** How long a shutdown waits for alert deliveries in flight. Whatever is not confirmed by then is re-delivered at the next start. */
private const val NOTIFICATION_DRAIN_MILLIS = 30_000L

/**
 * Argos main application entry point.
 *
 * Disables JVM DNS caching, parses arguments, loads configuration, initializes database and self-monitoring,
 * launches the scheduler loop, and binds the HTTP web server. Shutdown order: HTTP server, checks, alert
 * deliveries (bounded wait), heartbeat, database.
 *
 * @param args Command-line arguments: `configPath=<path>` (default `./config.json`) or
 * `hashPassword=<password>` to print an Argon2 hash for `statusPages[].basicAuth.passwordHash` and exit.
 */
fun main(args: Array<String>) {
    // Exposed writes SQLite timestamps as text in the JVM default zone. UTC keeps them sortable across DST changes
    // and makes the status page's daily history UTC days. Must run before the first database access.
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    // Disable JVM DNS caching to ensure DNS changes are picked up immediately
    java.security.Security.setProperty("networkaddress.cache.ttl", "0")
    java.security.Security.setProperty("networkaddress.cache.negative.ttl", "0")
    // Ktor/Hikari/Exposed log through SLF4J; keep only warnings so the journal stays readable
    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
    // Ktor would register its own JVM shutdown hook; Argos stops the server itself so the order below holds
    System.setProperty("io.ktor.server.engine.ShutdownHook", "false")
    // Klogger drops every message until a destination is configured; stdout ends up in the journal under systemd
    KLogger.configure {
        logToConsole()
        minLevel = KLogger.Level.INFO
    }

    val parsedArgs = ArgsInterpreter(args)
    val passwordToHash = parsedArgs.getValue("hashPassword")
    if (passwordToHash != null) {
        println(hashPassword(passwordToHash))
        return
    }

    val configPath = parsedArgs.getValue("configPath") ?: "./config.json"
    val config = when (val result = loadConfig(configPath)) {
        is ResultOf2.Success -> result.value
        is ResultOf2.Failure -> {
            staticLog(KLogger.Level.ERROR, TAG) { "Could not load configuration (${result.value}) — server starting in empty state." }
            AppConfig().let { defaults -> serveBootstrap(defaults.webHost, defaults.webPort, result.value) }
            return
        }
    }

    val appDatabase = runCatching { connectDatabase(config.dataDir) }.getOrElse { failure ->
        staticLog(KLogger.Level.ERROR, TAG) { "Could not open the database in '${config.dataDir}': ${failure.message} — server starting in empty state." }
        serveBootstrap(config.webHost, config.webPort, ConfigError.DataDirUnusable(failure.message ?: failure::class.simpleName ?: "unknown"))
        return
    }
    val database = appDatabase.database
    // Same client as the HTTP checks: only withTimeoutOrNull in sendWebhook ends a delivery; CIO's own timeout messages carry the URL (token)
    val httpClient = clientWithRedirects
    val notificationDispatcher = NotificationDispatcher(config, httpClient)

    val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        staticLog(KLogger.Level.ERROR, TAG) { "Unhandled error in background job: ${throwable::class.simpleName}: ${throwable.message}" }
    }
    val appScope = CoroutineScope(SupervisorJob() + exceptionHandler)
    // Deliveries live outside appScope: a shutdown cancels checks at once but lets alerts in flight finish (bounded below)
    val notificationScope = CoroutineScope(SupervisorJob() + exceptionHandler)

    // Gap detection against the last heartbeat, then an immediate heartbeat so a crash loop cannot re-report the same gap
    val startedAt = Instant.now()
    runBlocking {
        val lastHeartbeat = readLastHeartbeat(database)
        val gap = hasUnplannedGap(lastHeartbeat, startedAt, config.heartbeatGapMinutesThreshold)

        if (gap) {
            staticLog(KLogger.Level.WARN, TAG) { "Unexpected restart detected. Last heartbeat: $lastHeartbeat" }
            notificationScope.launch {
                notificationDispatcher.sendSystemNotification(
                    subject = "Argos: unexpected offline period",
                    body = "Last active: ${lastHeartbeat?.let { formatUtc(it) }}, now started: ${formatUtc(startedAt)}"
                )
            }
        }
        writeHeartbeat(database, startedAt)
    }
    notificationScope.launch { notificationDispatcher.sendSystemNotification(subject = "Argos started", body = "Started at ${formatUtc(startedAt)}") }

    warnIfIcmpUnavailable(config)
    val scheduler = Scheduler(config, database, appScope, notificationScope, notificationDispatcher).also { runBlocking { it.restoreState() } }
    scheduler.start()

    val server = embeddedServer(ServerCIO, host = config.webHost, port = config.webPort) {
        configureWeb(config, StatusService(config, database, scheduler::stateOf))

    }

    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching { server.stop(1_000, 5_000) }
        appScope.cancel()
        runCatching {
            runBlocking {
                val drained = withTimeoutOrNull(NOTIFICATION_DRAIN_MILLIS.milliseconds) {
                    notificationScope.coroutineContext.job.children.toList().joinAll()
                }
                if (drained == null) staticLog(KLogger.Level.WARN, TAG) { "Shutdown: alert deliveries still running after ${NOTIFICATION_DRAIN_MILLIS / 1000} s; queued alerts are re-delivered at the next start." }
            }
        }
        notificationScope.cancel()
        runCatching { runBlocking { writeHeartbeat(database, Instant.now()) } }
        runCatching { appDatabase.close() }
    })

    server.start(wait = true)
}

/**
 * Bootstrap state: `/setup` and a 503 on `/`, nothing else. No database and no heartbeat either — monitoring is
 * not running, so the gap is real and gets reported once a valid configuration starts. systemd sees no crash loop.
 */
private fun serveBootstrap(host: String, port: Int, error: ConfigError) {
    embeddedServer(ServerCIO, host = host, port = port) {
        configureWeb(null, { emptyList() }, error)
    }.start(wait = true)
}

/**
 * Logs an error when ping monitors cannot work: no `ping` binary, or one that cannot reach loopback.
 */
private fun warnIfIcmpUnavailable(config: AppConfig) {
    val pingMonitors = config.monitors.filter { it.check is PingCheckConfig }
    if (pingMonitors.isEmpty()) return
    val binary = defaultPingBinary
    if (binary == null) {
        staticLog(KLogger.Level.ERROR, TAG) { "No ping binary found — ping monitors ${pingMonitors.map { it.id }} will fail; install iputils-ping." }
        return
    }
    staticLog(KLogger.Level.INFO, TAG) { "Ping binary: $binary" }
    val reason = probeIcmp(binary) ?: return
    staticLog(KLogger.Level.ERROR, TAG) { "ICMP is not available — ping monitors ${pingMonitors.map { it.id }} will not deliver meaningful results: $reason" }
}

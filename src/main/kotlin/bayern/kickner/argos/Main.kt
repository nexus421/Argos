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
import bayern.kickner.argos.web.StatusSource
import bayern.kickner.argos.web.configureWeb
import bayern.kickner.argos.web.hashPassword
import bayern.kickner.klogger.KLogger
import bayern.kickner.klogger.staticLog
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotnexlib.ArgsInterpreter
import kotnexlib.ResultOf2
import java.time.Instant

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
    val configResult = loadConfig(configPath)
    val appConfig = (configResult as? ResultOf2.Success)?.value
    val configError = (configResult as? ResultOf2.Failure)?.value

    if (appConfig == null) {
        staticLog(KLogger.Level.ERROR, TAG) { "Could not load configuration ($configResult) — server starting in empty state." }
    }

    val webHost = appConfig?.webHost ?: "0.0.0.0"
    val webPort = appConfig?.webPort ?: 8080

    val dataDir = appConfig?.dataDir ?: "./data"
    val appDatabase = runCatching { connectDatabase(dataDir) }.getOrElse { failure ->
        // Same degradation as an invalid config: `/` answers 503 with the category, systemd sees no crash loop
        staticLog(KLogger.Level.ERROR, TAG) { "Could not open the database in '$dataDir': ${failure.message} — server starting in empty state." }
        val error = ConfigError.DataDirUnusable(failure.message ?: failure::class.simpleName ?: "unknown")
        embeddedServer(ServerCIO, host = webHost, port = webPort) {
            configureWeb(null, StatusSource { emptyList() }, error)
        }.start(wait = true)
        return
    }
    val database = appDatabase.database
    // Same client as the HTTP checks: only withTimeoutOrNull in sendWebhook ends a delivery; CIO's own timeout messages carry the URL (token)
    val httpClient = clientWithRedirects
    val notificationDispatcher = NotificationDispatcher(appConfig ?: AppConfig(), httpClient)

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
        val gap = hasUnplannedGap(lastHeartbeat, startedAt, appConfig?.heartbeatGapMinutesThreshold ?: 2)

        if (gap) {
            staticLog(KLogger.Level.WARN, TAG) { "Unexpected restart detected. Last heartbeat: $lastHeartbeat" }
            notificationScope.launch {
                notificationDispatcher.sendSystemNotification(
                    subject = "Argos: unexpected offline period",
                    body = "Last active: $lastHeartbeat, now started: $startedAt"
                )
            }
        }
        writeHeartbeat(database, startedAt)
    }
    notificationScope.launch { notificationDispatcher.sendSystemNotification(subject = "Argos started", body = "Started at $startedAt") }

    val scheduler = appConfig?.let { config ->
        warnIfIcmpUnavailable(config)
        Scheduler(config, database, appScope, notificationScope, notificationDispatcher).also { runBlocking { it.restoreState() } }
    }
    scheduler?.start()

    val statusSource: StatusSource = appConfig?.let { StatusService(it, database) { id -> scheduler?.stateOf(id) } }
        ?: StatusSource { emptyList() }

    val server = embeddedServer(ServerCIO, host = webHost, port = webPort) {
        configureWeb(appConfig, statusSource, configError)
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching { server.stop(1_000, 5_000) }
        appScope.cancel()
        runCatching {
            runBlocking {
                val drained = withTimeoutOrNull(NOTIFICATION_DRAIN_MILLIS) {
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
 * Sends one real ICMP echo to loopback with the selected backend. Without ICMP the JDK fallback degrades to a
 * TCP-port-7 probe whose result only reflects whether the target answers that port with RST — meaningless.
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

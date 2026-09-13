package bayern.kickner.argos

import bayern.kickner.argos.checks.defaultPingBackend
import bayern.kickner.argos.checks.probeIcmp
import bayern.kickner.argos.config.AppConfig
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
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotnexlib.ArgsInterpreter
import kotnexlib.ResultOf2
import java.time.Instant

private const val TAG = "Main"

/**
 * Argos main application entry point.
 *
 * Disables JVM DNS caching, parses arguments, loads configuration, initializes database and self-monitoring,
 * launches the scheduler loop, and binds the HTTP web server.
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

    val appDatabase = connectDatabase(appConfig?.dataDir ?: "./data")
    val database = appDatabase.database
    val httpClient = HttpClient(ClientCIO)
    val notificationDispatcher = NotificationDispatcher(appConfig ?: AppConfig(), httpClient)

    val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        staticLog(KLogger.Level.ERROR, TAG) { "Unhandled error in background job: ${throwable::class.simpleName}: ${throwable.message}" }
    }
    val appScope = CoroutineScope(SupervisorJob() + exceptionHandler)

    // Gap detection against the last heartbeat, then an immediate heartbeat so a crash loop cannot re-report the same gap
    val startedAt = Instant.now()
    runBlocking {
        val lastHeartbeat = readLastHeartbeat(database)
        val gap = hasUnplannedGap(lastHeartbeat, startedAt, appConfig?.heartbeatGapMinutesThreshold ?: 2)

        if (gap) {
            staticLog(KLogger.Level.WARN, TAG) { "Unexpected restart detected. Last heartbeat: $lastHeartbeat" }
            appScope.launch {
                notificationDispatcher.sendSystemNotification(
                    subject = "Argos: unexpected offline period",
                    body = "Last active: $lastHeartbeat, now started: $startedAt"
                )
            }
        }
        writeHeartbeat(database, startedAt)
    }
    appScope.launch { notificationDispatcher.sendSystemNotification(subject = "Argos started", body = "Started at $startedAt") }

    val scheduler = appConfig?.let { config ->
        warnIfIcmpUnavailable(config)
        Scheduler(config, database, appScope, notificationDispatcher).also { runBlocking { it.restoreState() } }
    }
    scheduler?.start()

    Runtime.getRuntime().addShutdownHook(Thread {
        appScope.cancel()
        runCatching { runBlocking { writeHeartbeat(database, Instant.now()) } }
        runCatching { appDatabase.close() }
    })

    val statusSource: StatusSource = appConfig?.let { StatusService(it, database) { id -> scheduler?.stateOf(id) } }
        ?: StatusSource { emptyList() }

    embeddedServer(ServerCIO, host = appConfig?.webHost ?: "0.0.0.0", port = appConfig?.webPort ?: 8080) {
        configureWeb(appConfig, statusSource, configError)
    }.start(wait = true)
}

/**
 * Sends one real ICMP echo to loopback with the selected backend. Without ICMP the JDK fallback degrades to a
 * TCP-port-7 probe whose result only reflects whether the target answers that port with RST — meaningless.
 */
private fun warnIfIcmpUnavailable(config: AppConfig) {
    val pingMonitors = config.monitors.filter { it.check is PingCheckConfig }
    if (pingMonitors.isEmpty()) return
    val backend = defaultPingBackend
    staticLog(KLogger.Level.INFO, TAG) { "Ping backend: $backend" }
    val reason = probeIcmp(backend) ?: return
    staticLog(KLogger.Level.ERROR, TAG) { "ICMP is not available — ping monitors ${pingMonitors.map { it.id }} will not deliver meaningful results: $reason" }
}

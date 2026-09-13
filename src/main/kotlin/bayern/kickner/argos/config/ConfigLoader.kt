package bayern.kickner.argos.config

import kotlinx.serialization.json.Json
import kotnexlib.ResultOf2
import java.io.File

/**
 * Represents failure reasons when loading application configuration.
 */
sealed interface ConfigError {
    /** JSON syntax or structural decoding error. */
    data class ParseError(val message: String) : ConfigError

    /** Business logic validation error with details. */
    data class ValidationError(val issues: List<String>) : ConfigError

    /** The configuration file does not exist at the given path. */
    data class FileNotFound(val path: String) : ConfigError
}

private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    classDiscriminator = "type"
}

/** Allowed shape for monitor, channel and status page IDs — they end up in DB columns, routes and log lines. */
private val idPattern = Regex("[A-Za-z0-9_.-]{1,64}")

/** Hostnames and IPv4/IPv6 literals (with optional zone ID); must not start with '-' as hosts become process arguments. */
private val hostPattern = Regex("[A-Za-z0-9:][A-Za-z0-9._:%-]{0,253}")

/**
 * Loads and validates configuration from the provided JSON file path.
 *
 * @param path Path to the JSON configuration file.
 * @return [ResultOf2.Success] with [AppConfig] or [ResultOf2.Failure] with [ConfigError].
 */
fun loadConfig(path: String): ResultOf2<AppConfig, ConfigError> {
    val file = File(path)
    if (file.exists().not()) return ResultOf2.Failure(ConfigError.FileNotFound(path))

    val parsed = runCatching { json.decodeFromString<AppConfig>(file.readText()) }
        .getOrElse { return ResultOf2.Failure(ConfigError.ParseError(sanitizeParseMessage(it.message))) }

    val issues = validate(parsed)
    if (issues.isNotEmpty()) return ResultOf2.Failure(ConfigError.ValidationError(issues))

    return ResultOf2.Success(parsed)
}

/**
 * kotlinx.serialization appends the offending JSON document ("JSON input: ...") to its messages.
 * The config contains SMTP passwords and webhook tokens, so that part must never reach the log.
 */
private fun sanitizeParseMessage(message: String?): String =
    message?.substringBefore("\nJSON input")?.substringBefore("JSON input:")?.trim().takeUnless { it.isNullOrBlank() } ?: "unknown"

/**
 * Validates IDs, numeric ranges, and cross references. Every issue is a hard error: a config that
 * passes validation must not be able to crash the scheduler or silently drop notifications.
 */
private fun validate(config: AppConfig): List<String> {
    val issues = mutableListOf<String>()

    fun checkId(kind: String, id: String) {
        val valid = idPattern.matches(id)
        if (valid.not()) issues += "$kind ID '$id' must match [A-Za-z0-9_.-] and be 1-64 characters long"
    }

    fun checkDuplicates(kind: String, ids: List<String>) {
        val duplicates = ids.groupBy { it }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) issues += "Duplicate $kind IDs: $duplicates"
    }

    fun checkHost(owner: String, host: String) {
        val valid = hostPattern.matches(host)
        if (valid.not()) issues += "$owner: host '$host' must be a hostname or IP address (letters, digits, '.', '-', ':', '%', not starting with '-')"
    }

    fun checkPort(owner: String, port: Int) {
        val valid = port in 1..65535
        if (valid.not()) issues += "$owner: port $port is out of range 1-65535"
    }

    checkDuplicates("monitor", config.monitors.map { it.id })
    checkDuplicates("channel", config.smtpChannels.map { it.id } + config.webhookChannels.map { it.id })
    checkDuplicates("status page", config.statusPages.map { it.id })

    val monitorIds = config.monitors.map { it.id }.toSet()
    val channelIds = (config.smtpChannels.map { it.id } + config.webhookChannels.map { it.id }).toSet()

    config.monitors.forEach { monitor ->
        val owner = "Monitor '${monitor.id}'"
        checkId("Monitor", monitor.id)
        if (monitor.intervalSeconds <= 0) issues += "$owner: intervalSeconds must be positive"
        if (monitor.timeoutSeconds <= 0) issues += "$owner: timeoutSeconds must be positive"
        val tooSlow = monitor.timeoutSeconds >= monitor.intervalSeconds
        if (tooSlow) issues += "$owner: timeoutSeconds must be smaller than intervalSeconds"

        val unknownChannels = monitor.notificationChannelIds.orEmpty().filterNot { it in channelIds }
        if (unknownChannels.isNotEmpty()) issues += "$owner: unknown notificationChannelIds $unknownChannels"

        when (val check = monitor.check) {
            is HttpCheckConfig -> {
                if (check.url.isBlank()) issues += "$owner: url must not be blank"
                if (check.expectedStatusCodes.isEmpty()) issues += "$owner: expectedStatusCodes must not be empty"
                val regexError = check.bodyRegex?.let { pattern -> runCatching { Regex(pattern) }.exceptionOrNull() }
                if (regexError != null) issues += "$owner: bodyRegex is invalid (${regexError.message?.lineSequence()?.first()})"
            }
            is TcpCheckConfig -> {
                checkHost(owner, check.host)
                checkPort(owner, check.port)
            }
            is PingCheckConfig -> checkHost(owner, check.host)
            is DnsCheckConfig -> checkHost(owner, check.hostname)
        }
    }

    config.smtpChannels.forEach { smtp ->
        val owner = "SMTP channel '${smtp.id}'"
        checkId("Channel", smtp.id)
        if (smtp.host.isBlank()) issues += "$owner: host must not be blank"
        checkPort(owner, smtp.port)
        if (smtp.to.isEmpty()) issues += "$owner: 'to' must contain at least one recipient"
        if (smtp.from.isBlank()) issues += "$owner: 'from' must not be blank"
    }

    config.webhookChannels.forEach { webhook ->
        checkId("Channel", webhook.id)
        if (webhook.url.isBlank()) issues += "Webhook channel '${webhook.id}': url must not be blank"
    }

    config.statusPages.forEach { page ->
        checkId("Status page", page.id)
        val unknownMonitors = page.monitorIds.filterNot { it in monitorIds }
        if (unknownMonitors.isNotEmpty()) issues += "Status page '${page.id}': unknown monitorIds $unknownMonitors"
    }

    if (config.retentionDays <= 0) issues += "retentionDays must be positive"
    if (config.flappingThreshold <= 0) issues += "flappingThreshold must be positive"
    if (config.heartbeatGapMinutesThreshold <= 0) issues += "heartbeatGapMinutesThreshold must be positive"
    checkPort("webPort", config.webPort)
    if (config.webHost.isBlank()) issues += "webHost must not be blank"

    return issues
}

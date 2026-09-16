package bayern.kickner.argos.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotnexlib.ResultOf2
import java.io.File
import java.net.InetAddress

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

    /** The file exists but could not be read (permissions, I/O). [reason] may contain the path — log only. */
    data class Unreadable(val reason: String) : ConfigError

    /** The configuration is fine but `dataDir` could not be opened as a database directory. */
    data class DataDirUnusable(val reason: String) : ConfigError
}

private val json = Json {
    ignoreUnknownKeys = true // unknown keys are reported by unknownFieldIssues with a path instead
    isLenient = true
    classDiscriminator = "type"
}

/** Allowed shape for monitor, channel and status page IDs — they end up in DB columns, routes and log lines. */
private val idPattern = Regex("[A-Za-z0-9_.-]{1,64}")

/** Hostnames and IPv4/IPv6 literals (with optional zone ID); must not start with '-' as hosts become process arguments. */
private val hostPattern = Regex("[A-Za-z0-9:][A-Za-z0-9._:%-]{0,253}")

/** http(s) with a non-empty, whitespace-free remainder; Ktor rejects anything else at request time. */
private val urlPattern = Regex("https?://\\S+", RegexOption.IGNORE_CASE)

/** Plain method token; `HttpMethod.parse` would accept anything, including an empty string. */
private val methodPattern = Regex("[A-Za-z]{1,16}")

/** `local@domain` or `Display Name <local@domain>`, no whitespace around the '@'. Jakarta Mail is the authority, this catches the typos. */
private val emailPattern = Regex("(?:[^<>@]*<[^\\s<>@]+@[^\\s<>@]+>|[^\\s<>@]+@[^\\s<>@]+)")

/** KotNexLib hashes are Base64 of `$argon2id$v=19$...`; the first 15 characters encode to this fixed prefix. */
private val passwordHashPattern = Regex("JGFyZ29uMmlkJHY9MTkk[A-Za-z0-9+/]+={0,2}")

private val ROOT_KEYS = setOf(
    "monitors", "smtpChannels", "webhookChannels", "statusPages", "retentionDays", "flappingThreshold",
    "heartbeatGapMinutesThreshold", "dataDir", "webHost", "webPort"
)
private val MONITOR_KEYS = setOf("id", "name", "intervalSeconds", "timeoutSeconds", "check", "notificationChannelIds")
private val CHECK_KEYS = mapOf(
    "http" to setOf("type", "url", "method", "headers", "expectedStatusCodes", "bodyRegex", "followRedirects"),
    "tcp" to setOf("type", "host", "port"),
    "ping" to setOf("type", "host"),
    "dns" to setOf("type", "hostname", "expectedIp")
)
private val SMTP_KEYS = setOf("id", "host", "port", "username", "password", "from", "to", "systemEvents", "tls")
private val WEBHOOK_KEYS = setOf("id", "url", "method", "headers", "bodyTemplate", "systemEvents")
private val STATUS_PAGE_KEYS = setOf("id", "name", "monitorIds", "basicAuth")
private val BASIC_AUTH_KEYS = setOf("username", "passwordHash")

/**
 * Loads and validates configuration from the provided JSON file path.
 *
 * @param path Path to the JSON configuration file.
 * @return [ResultOf2.Success] with [AppConfig] or [ResultOf2.Failure] with [ConfigError].
 */
fun loadConfig(path: String): ResultOf2<AppConfig, ConfigError> {
    val file = File(path)
    if (file.exists().not()) return ResultOf2.Failure(ConfigError.FileNotFound(path))

    // Kept apart from parse errors: an IOException message names the path, which must not end up on `/`
    val text = runCatching { file.readText() }
        .getOrElse { return ResultOf2.Failure(ConfigError.Unreadable(it.message ?: it::class.simpleName ?: "unknown")) }

    val tree = runCatching { json.parseToJsonElement(text) }
        .getOrElse { return ResultOf2.Failure(ConfigError.ParseError(sanitizeParseMessage(it.message))) }
    val parsed = runCatching { json.decodeFromJsonElement<AppConfig>(tree) }
        .getOrElse { return ResultOf2.Failure(ConfigError.ParseError(sanitizeParseMessage(it.message))) }

    val issues = unknownFieldIssues(tree) + validate(parsed)
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
 * Unknown keys are hard errors: a typo such as `basicauth` would otherwise silently fall back to the default —
 * here a public status page. Mirrored 1:1 by `unknownFieldIssues` in static/config-model.js.
 */
private fun unknownFieldIssues(root: JsonElement): List<String> {
    val issues = mutableListOf<String>()

    fun report(element: JsonElement?, known: Set<String>, where: String) {
        val fields = element as? JsonObject ?: return
        fields.keys.filterNot { it in known }.forEach { issues += "Unknown field '$it' in $where" }
    }

    fun objects(name: String): List<JsonElement> = (root as? JsonObject)?.get(name) as? JsonArray ?: emptyList()

    report(root, ROOT_KEYS, "the top level")
    objects("monitors").forEachIndexed { index, monitor ->
        report(monitor, MONITOR_KEYS, "monitors[$index]")
        val check = (monitor as? JsonObject)?.get("check")
        val type = ((check as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull
        type?.let { CHECK_KEYS[it] }?.let { known -> report(check, known, "monitors[$index].check") }
    }
    objects("smtpChannels").forEachIndexed { index, smtp -> report(smtp, SMTP_KEYS, "smtpChannels[$index]") }
    objects("webhookChannels").forEachIndexed { index, webhook -> report(webhook, WEBHOOK_KEYS, "webhookChannels[$index]") }
    objects("statusPages").forEachIndexed { index, page ->
        report(page, STATUS_PAGE_KEYS, "statusPages[$index]")
        report((page as? JsonObject)?.get("basicAuth"), BASIC_AUTH_KEYS, "statusPages[$index].basicAuth")
    }
    return issues
}

/**
 * Validates IDs, numeric ranges, formats and cross references. Every issue is a hard error: a config that
 * passes validation must not be able to crash the scheduler, silently drop notifications or fail a check for
 * a reason that was visible in the file. Mirrored 1:1 by `validate` in static/config-model.js.
 */
private fun validate(config: AppConfig): List<String> {
    val issues = mutableListOf<String>()

    fun checkId(kind: String, id: String) {
        val valid = idPattern.matches(id)
        if (valid.not()) issues += "$kind ID '$id' must match [A-Za-z0-9_.-] and be 1-64 characters long"
        else if (id == "." || id == "..") issues += "$kind ID '$id' must not be '.' or '..'"
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

    fun checkUrl(owner: String, url: String) {
        if (url.isBlank()) issues += "$owner: url must not be blank"
        else if (urlPattern.matches(url).not()) issues += "$owner: url must start with http:// or https:// and contain no whitespace"
    }

    fun checkMethod(owner: String, method: String) {
        val valid = methodPattern.matches(method)
        if (valid.not()) issues += "$owner: method '$method' must be an HTTP method name such as GET or POST"
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
                checkUrl(owner, check.url)
                checkMethod(owner, check.method)
                if (check.expectedStatusCodes.isEmpty()) issues += "$owner: expectedStatusCodes must not be empty"
                val badCodes = check.expectedStatusCodes.filterNot { it in 100..599 }
                if (badCodes.isNotEmpty()) issues += "$owner: expectedStatusCodes $badCodes must be between 100 and 599"
                val regexError = check.bodyRegex?.let { pattern -> runCatching { Regex(pattern) }.exceptionOrNull() }
                if (regexError != null) issues += "$owner: bodyRegex is invalid (${regexError.message?.lineSequence()?.first()})"
            }
            is TcpCheckConfig -> {
                checkHost(owner, check.host)
                checkPort(owner, check.port)
            }
            is PingCheckConfig -> checkHost(owner, check.host)
            is DnsCheckConfig -> {
                checkHost(owner, check.hostname)
                // ofLiteral parses strictly without a DNS lookup; the check compares by address bytes
                val ip = check.expectedIp
                val literal = ip == null || runCatching { InetAddress.ofLiteral(ip) }.isSuccess
                if (literal.not()) issues += "$owner: expectedIp '$ip' must be an IPv4 or IPv6 address"
            }
        }
    }

    config.smtpChannels.forEach { smtp ->
        val owner = "SMTP channel '${smtp.id}'"
        checkId("Channel", smtp.id)
        if (smtp.host.isBlank()) issues += "$owner: host must not be blank"
        checkPort(owner, smtp.port)
        if (smtp.to.isEmpty()) issues += "$owner: 'to' must contain at least one recipient"
        smtp.to.filterNot { emailPattern.matches(it) }.forEach { issues += "$owner: 'to' contains an invalid e-mail address '$it'" }
        if (smtp.from.isBlank()) issues += "$owner: 'from' must not be blank"
        else if (emailPattern.matches(smtp.from).not()) issues += "$owner: 'from' is not a valid e-mail address"
    }

    config.webhookChannels.forEach { webhook ->
        val owner = "Webhook channel '${webhook.id}'"
        checkId("Channel", webhook.id)
        checkUrl(owner, webhook.url)
        checkMethod(owner, webhook.method)
    }

    config.statusPages.forEach { page ->
        val owner = "Status page '${page.id}'"
        checkId("Status page", page.id)
        val unknownMonitors = page.monitorIds.filterNot { it in monitorIds }
        if (unknownMonitors.isNotEmpty()) issues += "$owner: unknown monitorIds $unknownMonitors"
        page.basicAuth?.let { auth ->
            if (auth.username.isBlank()) issues += "$owner: basicAuth.username must not be blank"
            if (passwordHashPattern.matches(auth.passwordHash).not()) issues += "$owner: basicAuth.passwordHash is not a hash produced by hashPassword="
        }
    }

    if (config.retentionDays <= 0) issues += "retentionDays must be positive"
    if (config.flappingThreshold <= 0) issues += "flappingThreshold must be positive"
    if (config.heartbeatGapMinutesThreshold <= 0) issues += "heartbeatGapMinutesThreshold must be positive"
    checkPort("webPort", config.webPort)
    if (config.webHost.isBlank()) issues += "webHost must not be blank"

    return issues
}

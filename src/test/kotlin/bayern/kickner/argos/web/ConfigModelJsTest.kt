package bayern.kickner.argos.web

import bayern.kickner.argos.config.AppConfig
import bayern.kickner.argos.config.ConfigError
import bayern.kickner.argos.config.loadConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotnexlib.ResultOf2
import kotnexlib.crypto.Argon2Helper
import org.graalvm.polyglot.Context
import java.io.File

/** Executes `static/config-model.js` — the DOM-free part of the setup page — the way the browser would. */
private class ConfigModelJs : AutoCloseable {
    private val context: Context = Context.newBuilder("js").option("engine.WarnInterpreterOnly", "false").build()

    init {
        val source = checkNotNull(ConfigModelJs::class.java.getResource("/static/config-model.js")) { "static/config-model.js missing" }.readText()
        context.eval("js", source)
    }

    fun validate(configJson: String): List<String> {
        context.getBindings("js").putMember("input", configJson)
        val issues = context.eval("js", "JSON.stringify(ArgosConfigModel.validate(JSON.parse(input)))").asString()
        return Json.decodeFromString(issues)
    }

    fun suggestId(name: String): String {
        context.getBindings("js").putMember("input", name)
        return context.eval("js", "ArgosConfigModel.suggestId(input)").asString()
    }

    fun eval(expression: String): String = context.eval("js", "String($expression)").asString()

    /** What the page emits after a file was loaded (normalized into the form) and downloaded again untouched. */
    fun roundTrip(configJson: String): String {
        context.getBindings("js").putMember("input", configJson)
        return context.eval("js", "ArgosConfigModel.serialize(ArgosConfigModel.normalize(JSON.parse(input)))").asString()
    }

    override fun close() = context.close()
}

private fun loadFromString(configJson: String): AppConfig {
    val file = File.createTempFile("argos-config", ".json").apply { writeText(configJson); deleteOnExit() }
    return loadConfig(file.path).shouldBeInstanceOf<ResultOf2.Success<AppConfig>>().value
}

/** The example from the README is the reference config: it must satisfy both the editor and the server. */
private val readmeExample: String by lazy {
    val readme = File("README.md").readText()
    readme.substringAfter("### Example `config.json`").substringAfter("```json\n").substringBefore("\n```")
}

private fun serverIssues(configJson: String): List<String> {
    val file = File.createTempFile("argos-config", ".json").apply { writeText(configJson); deleteOnExit() }
    return when (val result = loadConfig(file.path)) {
        is ResultOf2.Success -> emptyList()
        is ResultOf2.Failure -> when (val error = result.value) {
            is ConfigError.ValidationError -> error.issues
            else -> listOf(error.toString())
        }
    }
}

private const val TCP = """{"type":"tcp","host":"db","port":5432}"""

private fun monitor(id: String = "m1", interval: Int = 60, timeout: Int = 10, check: String = TCP, extra: String = "") =
    """{"id":"$id","name":"M","intervalSeconds":$interval,"timeoutSeconds":$timeout,"check":$check$extra}"""

private fun smtp(id: String = "mail", host: String = "smtp", port: Int = 587, from: String = "a@b", to: String = """["c@d"]""") =
    """{"id":"$id","host":"$host","port":$port,"username":"","password":"","from":"$from","to":$to}"""

private fun webhook(id: String = "hook", url: String = "https://h/x") = """{"id":"$id","url":"$url","bodyTemplate":"{{body}}"}"""

private fun page(id: String = "public", monitorIds: String = """["m1"]""") = """{"id":"$id","name":"P","monitorIds":$monitorIds}"""

private fun config(
    monitors: String = "[${monitor()}]",
    smtpChannels: String = "[]",
    webhookChannels: String = "[]",
    statusPages: String = "[]",
    globals: String = ""
) = """{"monitors":$monitors,"smtpChannels":$smtpChannels,"webhookChannels":$webhookChannels,"statusPages":$statusPages$globals}"""

private data class InvalidCase(val name: String, val json: String, val expectedIssue: String)

/** One case per rule in ConfigLoader.validate; the editor must flag exactly what the server would reject. */
private val invalidCases = listOf(
    InvalidCase("monitor ID with illegal characters", config("[${monitor(id = "bad id!")}]"), "Monitor ID 'bad id!' must match [A-Za-z0-9_.-] and be 1-64 characters long"),
    InvalidCase("duplicate monitor IDs", config("[${monitor()},${monitor()}]"), "Duplicate monitor IDs: [m1]"),
    InvalidCase("duplicate channel IDs across SMTP and webhook", config(smtpChannels = "[${smtp(id = "c")}]", webhookChannels = "[${webhook(id = "c")}]"), "Duplicate channel IDs: [c]"),
    InvalidCase("duplicate status page IDs", config(statusPages = "[${page()},${page()}]"), "Duplicate status page IDs: [public]"),
    InvalidCase("non-positive interval", config("[${monitor(interval = 0, timeout = -1)}]"), "Monitor 'm1': intervalSeconds must be positive"),
    InvalidCase("non-positive timeout", config("[${monitor(timeout = 0)}]"), "Monitor 'm1': timeoutSeconds must be positive"),
    InvalidCase("timeout not smaller than interval", config("[${monitor(interval = 10, timeout = 10)}]"), "Monitor 'm1': timeoutSeconds must be smaller than intervalSeconds"),
    InvalidCase("unknown notification channel", config("[${monitor(extra = ""","notificationChannelIds":["nope"]""")}]"), "Monitor 'm1': unknown notificationChannelIds [nope]"),
    InvalidCase("blank HTTP url", config("[${monitor(check = """{"type":"http","url":" "}""")}]"), "Monitor 'm1': url must not be blank"),
    InvalidCase("empty expected status codes", config("[${monitor(check = """{"type":"http","url":"https://x","expectedStatusCodes":[]}""")}]"), "Monitor 'm1': expectedStatusCodes must not be empty"),
    InvalidCase("invalid body regex", config("[${monitor(check = """{"type":"http","url":"https://x","bodyRegex":"("}""")}]"), "Monitor 'm1': bodyRegex is invalid"),
    InvalidCase("TCP host starting with a dash", config("[${monitor(check = """{"type":"tcp","host":"-h","port":80}""")}]"), "Monitor 'm1': host '-h' must be a hostname or IP address"),
    InvalidCase("TCP port out of range", config("[${monitor(check = """{"type":"tcp","host":"h","port":70000}""")}]"), "Monitor 'm1': port 70000 is out of range 1-65535"),
    InvalidCase("ping host with spaces", config("[${monitor(check = """{"type":"ping","host":"a b"}""")}]"), "Monitor 'm1': host 'a b' must be a hostname or IP address"),
    InvalidCase("DNS hostname with slash", config("[${monitor(check = """{"type":"dns","hostname":"a/b"}""")}]"), "Monitor 'm1': host 'a/b' must be a hostname or IP address"),
    InvalidCase("SMTP channel ID with illegal characters", config(smtpChannels = "[${smtp(id = "a b")}]"), "Channel ID 'a b' must match [A-Za-z0-9_.-] and be 1-64 characters long"),
    InvalidCase("blank SMTP host", config(smtpChannels = "[${smtp(host = "")}]"), "SMTP channel 'mail': host must not be blank"),
    InvalidCase("SMTP port out of range", config(smtpChannels = "[${smtp(port = 0)}]"), "SMTP channel 'mail': port 0 is out of range 1-65535"),
    InvalidCase("SMTP without recipients", config(smtpChannels = "[${smtp(to = "[]")}]"), "SMTP channel 'mail': 'to' must contain at least one recipient"),
    InvalidCase("blank SMTP sender", config(smtpChannels = "[${smtp(from = "")}]"), "SMTP channel 'mail': 'from' must not be blank"),
    InvalidCase("webhook channel ID with illegal characters", config(webhookChannels = "[${webhook(id = "a/b")}]"), "Channel ID 'a/b' must match [A-Za-z0-9_.-] and be 1-64 characters long"),
    InvalidCase("blank webhook url", config(webhookChannels = "[${webhook(url = "")}]"), "Webhook channel 'hook': url must not be blank"),
    InvalidCase("status page ID with illegal characters", config(statusPages = "[${page(id = "a b")}]"), "Status page ID 'a b' must match [A-Za-z0-9_.-] and be 1-64 characters long"),
    InvalidCase("status page referencing an unknown monitor", config(statusPages = "[${page(monitorIds = """["ghost"]""")}]"), "Status page 'public': unknown monitorIds [ghost]"),
    InvalidCase("non-positive retentionDays", config(globals = ""","retentionDays":0"""), "retentionDays must be positive"),
    InvalidCase("non-positive flappingThreshold", config(globals = ""","flappingThreshold":0"""), "flappingThreshold must be positive"),
    InvalidCase("non-positive heartbeatGapMinutesThreshold", config(globals = ""","heartbeatGapMinutesThreshold":0"""), "heartbeatGapMinutesThreshold must be positive"),
    InvalidCase("webPort out of range", config(globals = ""","webPort":0"""), "webPort: port 0 is out of range 1-65535"),
    InvalidCase("blank webHost", config(globals = ""","webHost":" """"), "webHost must not be blank"),
    InvalidCase("monitor ID '..'", config("[${monitor(id = "..")}]"), "Monitor ID '..' must not be '.' or '..'"),
    InvalidCase("HTTP url without scheme", config("[${monitor(check = """{"type":"http","url":"example.com/health"}""")}]"), "Monitor 'm1': url must start with http:// or https://"),
    InvalidCase("HTTP method with digits", config("[${monitor(check = """{"type":"http","url":"https://x","method":"G3T"}""")}]"), "Monitor 'm1': method 'G3T' must be an HTTP method name"),
    InvalidCase("status code out of range", config("[${monitor(check = """{"type":"http","url":"https://x","expectedStatusCodes":[200,999]}""")}]"), "Monitor 'm1': expectedStatusCodes [999] must be between 100 and 599"),
    InvalidCase("expectedIp that is not a literal", config("[${monitor(check = """{"type":"dns","hostname":"h","expectedIp":"gateway"}""")}]"), "Monitor 'm1': expectedIp 'gateway' must be an IPv4 or IPv6 address"),
    InvalidCase("SMTP recipient without @", config(smtpChannels = "[${smtp(to = """["ops"]""")}]"), "SMTP channel 'mail': 'to' contains an invalid e-mail address 'ops'"),
    InvalidCase("SMTP sender without @", config(smtpChannels = "[${smtp(from = "argos")}]"), "SMTP channel 'mail': 'from' is not a valid e-mail address"),
    InvalidCase("webhook url without scheme", config(webhookChannels = "[${webhook(url = "hooks.example/x")}]"), "Webhook channel 'hook': url must start with http:// or https://"),
    InvalidCase("password hash that is not from hashPassword", config(statusPages = """[{"id":"p","name":"P","monitorIds":["m1"],"basicAuth":{"username":"admin","passwordHash":"secret"}}]"""), "Status page 'p': basicAuth.passwordHash is not a hash produced by hashPassword="),
    InvalidCase("unknown top-level field", config(globals = ""","retention":3"""), "Unknown field 'retention' in the top level"),
    InvalidCase("misspelled basicAuth key", config(statusPages = """[{"id":"p","name":"P","monitorIds":["m1"],"basicauth":{"username":"admin","passwordHash":"x"}}]"""), "Unknown field 'basicauth' in statusPages[0]"),
    InvalidCase("unknown field inside a check", config("[${monitor(check = """{"type":"tcp","host":"db","port":5432,"timeout":3}""")}]"), "Unknown field 'timeout' in monitors[0].check")
)

class ConfigModelJsTest : FunSpec({

    val js = ConfigModelJs()
    afterSpec { js.close() }

    test("the README example config passes the editor validation and the server") {
        js.validate(readmeExample).shouldBeEmpty()
        serverIssues(readmeExample).shouldBeEmpty()
    }

    test("the README example hash really is the documented password, so readers can log in to the example page") {
        val hash = Regex(""""passwordHash":\s*"([^"]+)"""").find(readmeExample)!!.groupValues[1]
        Argon2Helper.verify("change-me".toCharArray(), hash).getOrThrow() shouldBe true
    }

    test("a display name in an SMTP address is accepted on both sides") {
        val named = config(smtpChannels = "[${smtp(from = "Argos <argos@example.com>", to = """["Ops <ops@example.com>"]""")}]")
        serverIssues(named).shouldBeEmpty()
        js.validate(named).shouldBeEmpty()
    }

    test("the fixture base config passes the editor validation and the server") {
        js.validate(config()).shouldBeEmpty()
        serverIssues(config()).shouldBeEmpty()
    }

    context("the editor reports the same issue as the server for") {
        invalidCases.forEach { case ->
            test(case.name) {
                serverIssues(case.json).joinToString("\n") shouldContain case.expectedIssue
                js.validate(case.json).joinToString("\n") shouldContain case.expectedIssue
            }
        }
    }

    context("loading and saving a config in the editor keeps its meaning for the server") {
        mapOf("README example" to readmeExample, "fixture base" to config(), "empty object" to "{}").forEach { (name, json) ->
            test(name) {
                loadFromString(js.roundTrip(json)) shouldBe loadFromString(json)
            }
        }
    }

    test("the README example is emitted exactly as documented, without null placeholders for unset optionals") {
        Json.parseToJsonElement(js.roundTrip(readmeExample)) shouldBe Json.parseToJsonElement(readmeExample)
    }

    test("numbers quoted as strings, which the lenient server parser accepts, are compared numerically") {
        val quoted = config("[${monitor().replace("\"intervalSeconds\":60,\"timeoutSeconds\":10", "\"intervalSeconds\":\"30\",\"timeoutSeconds\":\"5\"")}]")
        serverIssues(quoted).shouldBeEmpty()
        js.validate(quoted).shouldBeEmpty()
        loadFromString(js.roundTrip(quoted)) shouldBe loadFromString(quoted)
    }

    context("the editor rejects what the server cannot even parse") {
        listOf(
            InvalidCase("unknown TLS mode", config(smtpChannels = "[${smtp().replace("\"port\":587", "\"port\":587,\"tls\":\"tls\"")}]"), "SMTP channel 'mail': tls must be one of starttls, ssl, none"),
            InvalidCase("fractional interval", config("[${monitor().replace("\"intervalSeconds\":60", "\"intervalSeconds\":60.5")}]"), "Monitor 'm1': intervalSeconds must be a whole number"),
            InvalidCase("fractional retentionDays", config(globals = ""","retentionDays":1.5"""), "retentionDays must be a whole number"),
            InvalidCase("non-boolean followRedirects", config("[${monitor(check = """{"type":"http","url":"https://x","followRedirects":"yes"}""")}]"), "Monitor 'm1': followRedirects must be true or false"),
            InvalidCase("non-boolean systemEvents", config(webhookChannels = "[${webhook().replace("\"bodyTemplate\"", "\"systemEvents\":\"yes\",\"bodyTemplate\"")}]"), "Webhook channel 'hook': systemEvents must be true or false")
        ).forEach { case ->
            test(case.name) {
                serverIssues(case.json).shouldNotBeEmpty()
                js.validate(case.json).joinToString("\n") shouldContain case.expectedIssue
            }
        }
    }

    test("blank optional strings from a file are treated as unset instead of being written back") {
        val blanks = config("[${monitor(check = """{"type":"dns","hostname":"h","expectedIp":""}""")},${monitor(id = "m2", check = """{"type":"http","url":"https://x","bodyRegex":""}""")}]")
        val output = js.roundTrip(blanks)
        output shouldNotContain "expectedIp"
        output shouldNotContain "bodyRegex"
    }

    test("a file with the wrong structure is rejected with a message instead of crashing the page") {
        js.eval("(() => { try { ArgosConfigModel.normalize({ monitors: {} }); return 'no error'; } catch (e) { return e.message; } })()") shouldContain "monitors must be a list"
        js.eval("(() => { try { ArgosConfigModel.normalize({ monitors: [{ check: { type: 'http', headers: 'x' } }] }); return 'no error'; } catch (e) { return e.message; } })()") shouldContain "headers must be an object"
        js.eval("(() => { try { ArgosConfigModel.normalize({ smtpChannels: [{ to: 'a@b' }] }); return 'no error'; } catch (e) { return e.message; } })()") shouldContain "to must be a list"
        js.eval("(() => { try { ArgosConfigModel.normalize({ statusPages: [{ basicAuth: 'x' }] }); return 'no error'; } catch (e) { return e.message; } })()") shouldContain "basicAuth must be an object"
    }

    test("fresh objects from the factories carry the documented defaults") {
        js.eval("ArgosConfigModel.newMonitor().check.type") shouldBe "http"
        js.eval("ArgosConfigModel.newMonitor().intervalSeconds") shouldBe "60"
        js.eval("ArgosConfigModel.newCheck('tcp').port") shouldBe "null"
        js.eval("ArgosConfigModel.newSmtpChannel().tls") shouldBe "starttls"
        js.eval("ArgosConfigModel.newWebhookChannel().method") shouldBe "POST"
        js.eval("ArgosConfigModel.newStatusPage().basicAuth") shouldBe "null"
    }

    test("an ID suggested from a display name satisfies the server's ID rules") {
        js.suggestId("Public API") shouldBe "public-api"
        js.suggestId("  DB Server (prod) / EU  ") shouldBe "db-server-prod-eu"
        js.suggestId("Über Straße") shouldBe "ueber-strasse"
        js.suggestId("") shouldBe ""
        js.suggestId("x".repeat(80)) shouldBe "x".repeat(64)
    }
})

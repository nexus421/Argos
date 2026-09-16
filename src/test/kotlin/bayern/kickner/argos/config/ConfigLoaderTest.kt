package bayern.kickner.argos.config

import io.kotest.core.spec.style.FunSpec
import bayern.kickner.argos.web.hashPassword
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotnexlib.ResultOf2
import java.io.File

private fun load(json: String): ResultOf2<AppConfig, ConfigError> {
    val tmp = File.createTempFile("argos-config", ".json")
    tmp.writeText(json.trimIndent())
    val result = loadConfig(tmp.absolutePath)
    tmp.delete()
    return result
}

private fun issuesOf(json: String): List<String> {
    val failure = load(json).shouldBeInstanceOf<ResultOf2.Failure<ConfigError>>()
    val error = failure.value.shouldBeInstanceOf<ConfigError.ValidationError>()
    return error.issues
}

private fun tcpMonitor(id: String, interval: Int = 60, timeout: Int = 10, extra: String = "") =
    """{ "id": "$id", "name": "$id", "intervalSeconds": $interval, "timeoutSeconds": $timeout,
        "check": { "type": "tcp", "host": "a", "port": 1 }$extra }"""

class ConfigLoaderTest : FunSpec({

    test("loads a valid config with HTTP and TCP checks") {
        val result = load(
            """
            {
              "monitors": [
                { "id": "m1", "name": "API", "intervalSeconds": 60, "timeoutSeconds": 10,
                  "check": { "type": "http", "url": "https://example.com" } },
                ${tcpMonitor("m2", 30, 5)}
              ]
            }
            """
        )

        result.shouldBeInstanceOf<ResultOf2.Success<AppConfig>>()
        result.value.monitors.size shouldBe 2
    }

    test("reports duplicate monitor IDs as validation error") {
        issuesOf("""{ "monitors": [ ${tcpMonitor("dup")}, ${tcpMonitor("dup")} ] }""")
            .shouldExist { it.contains("dup") }
    }

    test("rejects timeoutSeconds >= intervalSeconds") {
        issuesOf("""{ "monitors": [ ${tcpMonitor("m1", 10, 10)} ] }""")
            .shouldExist { it.contains("timeoutSeconds") }
    }

    test("reports missing file without throwing exception") {
        loadConfig("/tmp/does-not-exist-argos-config.json")
            .shouldBeInstanceOf<ResultOf2.Failure<ConfigError>>()
    }

    test("rejects intervalSeconds and timeoutSeconds that are not positive") {
        val issues = issuesOf("""{ "monitors": [ ${tcpMonitor("m1", 0, -1)} ] }""")
        issues.shouldExist { it.contains("intervalSeconds") && it.contains("m1") }
        issues.shouldExist { it.contains("timeoutSeconds") && it.contains("m1") }
    }

    test("rejects IDs with unsafe characters or more than 64 characters") {
        val longId = "a".repeat(65)
        val issues = issuesOf(
            """{ "monitors": [ ${tcpMonitor("bad/id")}, ${tcpMonitor(longId)} ],
                 "statusPages": [ { "id": "{x}", "name": "p", "monitorIds": [] } ] }"""
        )
        issues.shouldExist { it.contains("bad/id") }
        issues.shouldExist { it.contains(longId) }
        issues.shouldExist { it.contains("{x}") }
    }

    test("rejects duplicate status page IDs and duplicate channel IDs across SMTP and webhook") {
        val issues = issuesOf(
            """{
              "smtpChannels": [ { "id": "c1", "host": "h", "port": 25, "username": "u", "password": "p", "from": "a@b", "to": ["x@y"] } ],
              "webhookChannels": [ { "id": "c1", "url": "https://h", "bodyTemplate": "{}" } ],
              "statusPages": [ { "id": "p", "name": "p", "monitorIds": [] }, { "id": "p", "name": "p", "monitorIds": [] } ]
            }"""
        )
        issues.shouldExist { it.contains("channel") && it.contains("c1") }
        issues.shouldExist { it.contains("status page") && it.contains("p") }
    }

    test("rejects references to unknown monitors and channels") {
        val issues = issuesOf(
            """{
              "monitors": [ ${tcpMonitor("m1", extra = """, "notificationChannelIds": ["nope"]""")} ],
              "statusPages": [ { "id": "p", "name": "p", "monitorIds": ["ghost"] } ]
            }"""
        )
        issues.shouldExist { it.contains("nope") && it.contains("m1") }
        issues.shouldExist { it.contains("ghost") && it.contains("p") }
    }

    test("rejects invalid bodyRegex and empty expectedStatusCodes") {
        val issues = issuesOf(
            """{ "monitors": [
              { "id": "h1", "name": "h", "intervalSeconds": 60, "timeoutSeconds": 10,
                "check": { "type": "http", "url": "https://x", "bodyRegex": "(", "expectedStatusCodes": [] } } ] }"""
        )
        issues.shouldExist { it.contains("bodyRegex") && it.contains("h1") }
        issues.shouldExist { it.contains("expectedStatusCodes") && it.contains("h1") }
    }

    test("rejects SMTP channel without recipients and ports out of range") {
        val issues = issuesOf(
            """{
              "monitors": [ { "id": "t", "name": "t", "intervalSeconds": 60, "timeoutSeconds": 10,
                              "check": { "type": "tcp", "host": "a", "port": 70000 } } ],
              "smtpChannels": [ { "id": "c1", "host": "h", "port": 0, "username": "u", "password": "p", "from": "a@b", "to": [] } ],
              "webPort": 0
            }"""
        )
        issues.shouldExist { it.contains("to") && it.contains("c1") }
        issues.shouldExist { it.contains("port") && it.contains("c1") }
        issues.shouldExist { it.contains("port") && it.contains("t") }
        issues.shouldExist { it.contains("webPort") }
    }

    test("rejects non-positive global thresholds") {
        val issues = issuesOf("""{ "retentionDays": 0, "flappingThreshold": 0, "heartbeatGapMinutesThreshold": -1 }""")
        issues.shouldExist { it.contains("retentionDays") }
        issues.shouldExist { it.contains("flappingThreshold") }
        issues.shouldExist { it.contains("heartbeatGapMinutesThreshold") }
    }

    test("accepts a config that references existing monitors and channels") {
        val result = load(
            """{
              "monitors": [ ${tcpMonitor("m1", extra = """, "notificationChannelIds": ["c1"]""")} ],
              "webhookChannels": [ { "id": "c1", "url": "https://h", "bodyTemplate": "{}" } ],
              "statusPages": [ { "id": "p", "name": "p", "monitorIds": ["m1"] } ]
            }"""
        )
        result.shouldBeInstanceOf<ResultOf2.Success<AppConfig>>()
    }

    test("SMTP tls defaults to STARTTLS and accepts ssl and none") {
        fun smtp(id: String, tls: String) =
            """{ "id": "$id", "host": "h", "port": 25, "username": "u", "password": "p", "from": "a@b", "to": ["x@y"]$tls }"""
        val result = load("""{ "smtpChannels": [ ${smtp("c1", "")}, ${smtp("c2", """, "tls": "ssl"""")}, ${smtp("c3", """, "tls": "none"""")} ] }""")
            .shouldBeInstanceOf<ResultOf2.Success<AppConfig>>()
        result.value.smtpChannels.map { it.tls } shouldBe listOf(SmtpTls.STARTTLS, SmtpTls.SSL, SmtpTls.NONE)
    }

    test("webHost and webPort default to 0.0.0.0:8080 and can be overridden") {
        load("{}").shouldBeInstanceOf<ResultOf2.Success<AppConfig>>().value.let {
            it.webHost shouldBe "0.0.0.0"
            it.webPort shouldBe 8080
        }
        load("""{ "webHost": "127.0.0.1", "webPort": 9090 }""").shouldBeInstanceOf<ResultOf2.Success<AppConfig>>().value.let {
            it.webHost shouldBe "127.0.0.1"
            it.webPort shouldBe 9090
        }
    }

    test("parse error message does not echo the JSON document") {
        val failure = load("""{ "smtpChannels": [ { "id": "x", "password": "SuperSecret123", "systemEvents": tru } ] }""")
            .shouldBeInstanceOf<ResultOf2.Failure<ConfigError>>()
        val error = failure.value.shouldBeInstanceOf<ConfigError.ParseError>()
        error.message shouldNotContain "SuperSecret123"
        error.message shouldNotContain "JSON input"
        error.message shouldContain "systemEvents"
    }

    test("empty config is valid") {
        val result = load("{}").shouldBeInstanceOf<ResultOf2.Success<AppConfig>>()
        result.value.monitors.shouldBeEmpty()
    }

    test("rejects hosts that could be mistaken for command-line options or contain whitespace") {
        val issues = issuesOf(
            """{ "monitors": [
              { "id": "p", "name": "p", "intervalSeconds": 10, "timeoutSeconds": 2, "check": { "type": "ping", "host": "-evil" } },
              { "id": "t", "name": "t", "intervalSeconds": 10, "timeoutSeconds": 2, "check": { "type": "tcp", "host": "my host", "port": 1 } },
              { "id": "d", "name": "d", "intervalSeconds": 10, "timeoutSeconds": 2, "check": { "type": "dns", "hostname": "ok.example" } } ] }"""
        )
        issues.shouldExist { it.contains("'p'") && it.contains("host") }
        issues.shouldExist { it.contains("'t'") && it.contains("host") }
        issues.none { it.contains("'d'") } shouldBe true
    }

    test("accepts hostnames, IPv4 and IPv6 literals including zone IDs") {
        load(
            """{ "monitors": [
              { "id": "a", "name": "a", "intervalSeconds": 10, "timeoutSeconds": 2, "check": { "type": "ping", "host": "db.internal.example.com" } },
              { "id": "b", "name": "b", "intervalSeconds": 10, "timeoutSeconds": 2, "check": { "type": "ping", "host": "192.168.1.1" } },
              { "id": "c", "name": "c", "intervalSeconds": 10, "timeoutSeconds": 2, "check": { "type": "tcp", "host": "fe80::1%eth0", "port": 22 } },
              { "id": "d", "name": "d", "intervalSeconds": 10, "timeoutSeconds": 2, "check": { "type": "ping", "host": "::1" } },
              { "id": "e", "name": "e", "intervalSeconds": 10, "timeoutSeconds": 2, "check": { "type": "ping", "host": "::ffff:192.0.2.1" } } ] }"""
        ).shouldBeInstanceOf<ResultOf2.Success<AppConfig>>()
    }

    test("a file that exists but cannot be read is reported as Unreadable, not as a parse error") {
        val tmp = File.createTempFile("argos-unreadable", ".json").apply { writeText("{}"); setReadable(false) }
        val readable = tmp.canRead()
        tmp.delete()
        // Root (or a filesystem without permission bits) can always read; nothing to test then
        if (readable) return@test

        val locked = File.createTempFile("argos-unreadable", ".json").apply { writeText("{}"); setReadable(false) }
        val failure = loadConfig(locked.absolutePath).shouldBeInstanceOf<ResultOf2.Failure<ConfigError>>()
        locked.delete()

        failure.value.shouldBeInstanceOf<ConfigError.Unreadable>()
    }

    test("rejects unknown fields at every level with their path") {
        val issues = issuesOf(
            """{
              "monitors": [ { "id": "m1", "name": "m", "intervalSeconds": 60, "timeoutSeconds": 10, "notificationChannelIDs": [],
                              "check": { "type": "tcp", "host": "a", "port": 1, "timeout": 3 } } ],
              "smtpChannels": [ { "id": "c1", "host": "h", "port": 25, "username": "u", "password": "p", "from": "a@b", "to": ["x@y"], "tsl": "none" } ],
              "webhookChannels": [ { "id": "c2", "url": "https://h", "bodyTemplate": "{}", "header": {} } ],
              "statusPages": [ { "id": "p", "name": "p", "monitorIds": ["m1"], "basicauth": { "username": "a", "passwordHash": "x" } },
                               { "id": "q", "name": "q", "monitorIds": ["m1"], "basicAuth": { "username": "a", "passwordHash": "x", "pw": "y" } } ],
              "retention": 3
            }"""
        )
        issues shouldContain "Unknown field 'retention' in the top level"
        issues shouldContain "Unknown field 'notificationChannelIDs' in monitors[0]"
        issues shouldContain "Unknown field 'timeout' in monitors[0].check"
        issues shouldContain "Unknown field 'tsl' in smtpChannels[0]"
        issues shouldContain "Unknown field 'header' in webhookChannels[0]"
        issues shouldContain "Unknown field 'basicauth' in statusPages[0]"
        issues shouldContain "Unknown field 'pw' in statusPages[1].basicAuth"
    }

    test("rejects '.' and '..' as IDs although they match the character rule") {
        val issues = issuesOf("""{ "statusPages": [ { "id": ".", "name": "p", "monitorIds": [] }, { "id": "..", "name": "q", "monitorIds": [] } ] }""")
        issues shouldContain "Status page ID '.' must not be '.' or '..'"
        issues shouldContain "Status page ID '..' must not be '.' or '..'"
    }

    test("rejects HTTP and webhook URLs without an http(s) scheme, methods that are not a plain token and status codes outside 100-599") {
        val issues = issuesOf(
            """{
              "monitors": [ { "id": "h1", "name": "h", "intervalSeconds": 60, "timeoutSeconds": 10,
                              "check": { "type": "http", "url": "example.com/health", "method": "G3T", "expectedStatusCodes": [200, 999, 0] } } ],
              "webhookChannels": [ { "id": "w1", "url": "hooks.example/x", "method": "", "bodyTemplate": "{}" } ]
            }"""
        )
        issues shouldContain "Monitor 'h1': url must start with http:// or https:// and contain no whitespace"
        issues shouldContain "Monitor 'h1': method 'G3T' must be an HTTP method name such as GET or POST"
        issues shouldContain "Monitor 'h1': expectedStatusCodes [999, 0] must be between 100 and 599"
        issues shouldContain "Webhook channel 'w1': url must start with http:// or https:// and contain no whitespace"
        issues shouldContain "Webhook channel 'w1': method '' must be an HTTP method name such as GET or POST"
    }

    test("rejects a DNS expectedIp that is not an IP literal and accepts IPv4 and IPv6 literals") {
        fun dns(id: String, ip: String) = """{ "id": "$id", "name": "d", "intervalSeconds": 60, "timeoutSeconds": 10, "check": { "type": "dns", "hostname": "h", "expectedIp": "$ip" } }"""
        val issues = issuesOf("""{ "monitors": [ ${dns("d1", "gateway")}, ${dns("d2", "999.1.1.1")}, ${dns("d3", "203.0.113.10")}, ${dns("d4", "2001:db8::1")} ] }""")
        issues shouldContain "Monitor 'd1': expectedIp 'gateway' must be an IPv4 or IPv6 address"
        issues shouldContain "Monitor 'd2': expectedIp '999.1.1.1' must be an IPv4 or IPv6 address"
        issues.filter { it.contains("'d3'") || it.contains("'d4'") }.shouldBeEmpty()
    }

    test("rejects SMTP addresses without an @ and accepts ordinary ones") {
        fun smtp(id: String, from: String, to: String) = """{ "id": "$id", "host": "h", "port": 25, "username": "u", "password": "p", "from": "$from", "to": $to }"""
        val issues = issuesOf("""{ "smtpChannels": [ ${smtp("c1", "argos", """["ops", "a@b"]""")}, ${smtp("c2", "Argos <argos@example.com>", """["ops@example.com"]""")} ] }""")
        issues shouldContain "SMTP channel 'c1': 'from' is not a valid e-mail address"
        issues shouldContain "SMTP channel 'c1': 'to' contains an invalid e-mail address 'ops'"
        issues.filter { it.contains("'c2'") }.shouldBeEmpty()
    }

    test("rejects a passwordHash that was not produced by hashPassword= and accepts a real one") {
        fun page(id: String, hash: String) = """{ "id": "$id", "name": "p", "monitorIds": [], "basicAuth": { "username": "admin", "passwordHash": "$hash" } }"""
        val issues = issuesOf("""{ "statusPages": [ ${page("p1", "secret")}, ${page("p2", "${'$'}argon2id${'$'}v=19${'$'}m=65536,t=3,p=1${'$'}abc${'$'}def")}, ${page("p3", hashPassword("secret"))} ] }""")
        issues shouldContain "Status page 'p1': basicAuth.passwordHash is not a hash produced by hashPassword="
        issues shouldContain "Status page 'p2': basicAuth.passwordHash is not a hash produced by hashPassword="
        issues.filter { it.contains("'p3'") }.shouldBeEmpty()
    }
})

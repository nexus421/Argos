package bayern.kickner.argos.notify

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TemplateTest : FunSpec({

    test("replaces all placeholders in template") {
        val template = """{"text": "{{monitorName}} is {{status}}"}"""
        val result = renderTemplate(template, mapOf("monitorName" to "API", "status" to "DOWN"))
        result shouldBe """{"text": "API is DOWN"}"""
    }

    test("leaves unknown placeholders intact") {
        val result = renderTemplate("{{unknown}}", emptyMap())
        result shouldBe "{{unknown}}"
    }

    test("jsonEscape escapes quotes, backslashes, newlines and control characters") {
        jsonEscape("""say "hi"\ line1
line2	tab""" + "\u0001") shouldBe """say \"hi\"\\ line1\nline2\ttab\u0001"""
    }

    test("renderTemplate escapes values for JSON when requested") {
        val template = """{"text": "{{body}}"}"""
        val result = renderTemplate(template, mapOf("body" to "Connection \"refused\"\nline2"), escapeJson = true)
        result shouldBe """{"text": "Connection \"refused\"\nline2"}"""
    }
})

package bayern.kickner.argos.notify

import bayern.kickner.argos.config.SmtpConfig
import bayern.kickner.argos.config.SmtpTls
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

private fun smtp(tls: SmtpTls = SmtpTls.STARTTLS, username: String = "user") =
    SmtpConfig(id = "c", host = "mail.example", port = 587, username = username, password = "pw", from = "a@b", to = listOf("x@y"), tls = tls)

class MailSenderTest : FunSpec({

    test("STARTTLS mode requires STARTTLS and verifies the server identity") {
        val props = buildSmtpProperties(smtp(SmtpTls.STARTTLS))
        props.getProperty("mail.smtp.starttls.enable") shouldBe "true"
        props.getProperty("mail.smtp.starttls.required") shouldBe "true"
        props.getProperty("mail.smtp.ssl.checkserveridentity") shouldBe "true"
        props.getProperty("mail.smtp.ssl.enable").shouldBeNull()
    }

    test("SSL mode uses implicit TLS and verifies the server identity") {
        val props = buildSmtpProperties(smtp(SmtpTls.SSL))
        props.getProperty("mail.smtp.ssl.enable") shouldBe "true"
        props.getProperty("mail.smtp.ssl.checkserveridentity") shouldBe "true"
        props.getProperty("mail.smtp.starttls.enable").shouldBeNull()
    }

    test("NONE mode configures no encryption") {
        val props = buildSmtpProperties(smtp(SmtpTls.NONE))
        props.getProperty("mail.smtp.ssl.enable").shouldBeNull()
        props.getProperty("mail.smtp.starttls.enable").shouldBeNull()
    }

    test("always sets connect, read and write timeouts") {
        val props = buildSmtpProperties(smtp())
        props.getProperty("mail.smtp.connectiontimeout") shouldBe "10000"
        props.getProperty("mail.smtp.timeout") shouldBe "30000"
        props.getProperty("mail.smtp.writetimeout") shouldBe "30000"
    }

    test("enables SMTP AUTH only when a username is configured") {
        buildSmtpProperties(smtp(username = "user")).getProperty("mail.smtp.auth") shouldBe "true"
        buildSmtpProperties(smtp(username = "")).getProperty("mail.smtp.auth") shouldBe "false"
    }
})

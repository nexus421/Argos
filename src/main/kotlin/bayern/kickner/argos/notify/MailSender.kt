package bayern.kickner.argos.notify

import bayern.kickner.argos.config.SmtpConfig
import bayern.kickner.argos.config.SmtpTls
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.util.Properties

private const val CONNECT_TIMEOUT_MS = 10_000
private const val IO_TIMEOUT_MS = 30_000

/**
 * Builds the Jakarta Mail session properties for [config]: transport encryption per [SmtpConfig.tls],
 * hostname verification whenever TLS is used, and finite timeouts (Jakarta Mail defaults to infinite).
 */
fun buildSmtpProperties(config: SmtpConfig): Properties = Properties().apply {
    put("mail.smtp.host", config.host)
    put("mail.smtp.port", config.port.toString())
    put("mail.smtp.auth", config.username.isNotBlank().toString())
    put("mail.smtp.connectiontimeout", CONNECT_TIMEOUT_MS.toString())
    put("mail.smtp.timeout", IO_TIMEOUT_MS.toString())
    put("mail.smtp.writetimeout", IO_TIMEOUT_MS.toString())

    when (config.tls) {
        SmtpTls.STARTTLS -> {
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.starttls.required", "true")
            put("mail.smtp.ssl.checkserveridentity", "true")
        }
        SmtpTls.SSL -> {
            put("mail.smtp.ssl.enable", "true")
            put("mail.smtp.ssl.checkserveridentity", "true")
        }
        SmtpTls.NONE -> Unit
    }
}

/**
 * Sends an email notification synchronously using Jakarta Mail over SMTP.
 *
 * @param config SMTP configuration (host, port, credentials, sender, recipients, TLS mode).
 * @param subject Email subject line.
 * @param body Plaintext message body.
 */
fun sendMail(config: SmtpConfig, subject: String, body: String) {
    val authenticator = object : Authenticator() {
        override fun getPasswordAuthentication() = PasswordAuthentication(config.username, config.password)
    }
    val session = Session.getInstance(buildSmtpProperties(config), authenticator)

    Transport.send(buildMimeMessage(session, config, subject, body))
}

/**
 * Builds the plaintext message. The charset is explicit: without it Jakarta Mail falls back to `file.encoding`,
 * and monitor names carry umlauts.
 */
fun buildMimeMessage(session: Session, config: SmtpConfig, subject: String, body: String): MimeMessage =
    MimeMessage(session).apply {
        setFrom(InternetAddress(config.from))
        setRecipients(Message.RecipientType.TO, config.to.map { InternetAddress(it) }.toTypedArray())
        setSubject(subject, "UTF-8")
        setText(body, "UTF-8")
    }

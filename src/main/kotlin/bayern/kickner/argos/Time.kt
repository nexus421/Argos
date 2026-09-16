package bayern.kickner.argos

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val utcTimestamp: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC)

/**
 * The one human-readable timestamp format of Argos — status pages and alert texts alike. Always UTC with suffix,
 * so neither the viewer's nor the server's zone changes what a reader sees.
 */
fun formatUtc(instant: Instant): String = utcTimestamp.format(instant)

package bayern.kickner.argos.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

/** Longest error message stored per check; Exposed rejects longer values instead of truncating. */
const val ERROR_MESSAGE_MAX_LENGTH = 1024

/**
 * Historical record of every executed monitor check.
 */
object CheckHistoryTable : Table("check_history") {
    val id = long("id").autoIncrement()
    val monitorId = varchar("monitor_id", 64)
    val timestamp = timestamp("timestamp").index()
    val success = bool("success")
    val responseTimeMs = double("response_time_ms")
    val errorMessage = varchar("error_message", ERROR_MESSAGE_MAX_LENGTH).nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        // Serves "latest results per monitor" lookups for status pages and state restoration
        index(isUnique = false, monitorId, timestamp)
    }
}

/**
 * Stores the last heartbeat timestamp for unplanned downtime gap detection.
 */
object SelfMonitorTable : Table("self_monitor") {
    val id = integer("id")
    val lastHeartbeat = timestamp("last_heartbeat")

    override val primaryKey = PrimaryKey(id)
}

/**
 * Alerts whose delivery has not been confirmed yet. A row is written in the same transaction as the check result
 * that triggered it and deleted once every channel accepted the notification; a crash or shutdown in between
 * therefore never loses an alert (it is re-delivered by the scheduler, at the latest at the next start).
 */
object PendingAlertTable : Table("pending_alert") {
    val id = long("id").autoIncrement()
    val monitorId = varchar("monitor_id", 64)
    /** `DOWN` or `UP`, see `MonitorEvent`; kept as text so the db package does not depend on notify. */
    val event = varchar("event", 8)
    val subject = text("subject")
    val body = text("body")
    /** Comma-separated channel IDs still waiting for this alert (IDs cannot contain commas). */
    val channelIds = text("channel_ids")
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * Single-row table with the schema version the database is at; see `migrateSchema` in Database.kt.
 */
object SchemaVersionTable : Table("schema_version") {
    val version = integer("version")

    override val primaryKey = PrimaryKey(version)
}

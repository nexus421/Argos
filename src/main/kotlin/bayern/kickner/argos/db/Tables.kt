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

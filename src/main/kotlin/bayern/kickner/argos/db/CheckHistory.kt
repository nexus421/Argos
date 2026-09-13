package bayern.kickner.argos.db

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant

/**
 * One stored check outcome.
 *
 * @property monitorId Monitor the result belongs to.
 * @property timestamp When the check finished.
 * @property success Whether the check passed.
 * @property responseTimeMs Duration of the check in milliseconds (sub-millisecond precision).
 * @property errorMessage Failure detail, truncated to [ERROR_MESSAGE_MAX_LENGTH].
 */
data class CheckHistoryEntry(
    val monitorId: String,
    val timestamp: Instant,
    val success: Boolean,
    val responseTimeMs: Double,
    val errorMessage: String?
)

/**
 * Persists a check outcome; over-long error messages are truncated instead of failing the insert.
 */
suspend fun insertCheckResult(database: Database, entry: CheckHistoryEntry) = dbWrite(database) {
    CheckHistoryTable.insert {
        it[monitorId] = entry.monitorId
        it[timestamp] = entry.timestamp
        it[success] = entry.success
        it[responseTimeMs] = entry.responseTimeMs
        it[errorMessage] = entry.errorMessage?.take(ERROR_MESSAGE_MAX_LENGTH)
    }
}

/**
 * Returns the newest [limit] results of one monitor, newest first.
 */
suspend fun latestResults(database: Database, monitorId: String, limit: Int): List<CheckHistoryEntry> = dbRead(database) {
    CheckHistoryTable.selectAll()
        .where { CheckHistoryTable.monitorId eq monitorId }
        .orderBy(CheckHistoryTable.timestamp, SortOrder.DESC)
        .limit(limit)
        .map {
            CheckHistoryEntry(
                monitorId = it[CheckHistoryTable.monitorId],
                timestamp = it[CheckHistoryTable.timestamp],
                success = it[CheckHistoryTable.success],
                responseTimeMs = it[CheckHistoryTable.responseTimeMs],
                errorMessage = it[CheckHistoryTable.errorMessage]
            )
        }
}

/**
 * Deletes history older than [cutoff] in batches of [batchSize] rows so the write lock is released
 * between batches and the WAL file does not balloon.
 *
 * @return Number of deleted rows.
 */
suspend fun deleteHistoryOlderThan(database: Database, cutoff: Instant, batchSize: Int = 5000): Long {
    var deletedTotal = 0L
    while (true) {
        val deleted = dbWrite(database) {
            val oldest = CheckHistoryTable.select(CheckHistoryTable.id)
                .where { CheckHistoryTable.timestamp less cutoff }
                .limit(batchSize)
            CheckHistoryTable.deleteWhere { CheckHistoryTable.id inSubQuery oldest }
        }
        deletedTotal += deleted
        val finished = deleted < batchSize
        if (finished) return deletedTotal
    }
}

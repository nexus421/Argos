package bayern.kickner.argos.db

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

/**
 * An alert waiting for (complete) delivery.
 *
 * @property id Row ID, null until stored — or permanently null when storing failed and the alert is delivered
 * once without restart protection.
 * @property event `DOWN` or `UP` (name of `MonitorEvent`).
 * @property channelIds Channels that have not confirmed this alert yet.
 */
data class PendingAlert(
    val id: Long?,
    val monitorId: String,
    val event: String,
    val subject: String,
    val body: String,
    val channelIds: List<String>,
    val createdAt: Instant
)

/**
 * Stores a check result and, in the same transaction, the alert it triggered. After a crash either both rows
 * exist or neither, so the trigger state rebuilt from the history never implies an alert that was not queued.
 *
 * @return ID of the queued alert, null when [alert] is null.
 */
suspend fun recordCheck(database: Database, entry: CheckHistoryEntry, alert: PendingAlert?): Long? = dbWrite(database) {
    CheckHistoryTable.insert {
        it[monitorId] = entry.monitorId
        it[timestamp] = entry.timestamp
        it[success] = entry.success
        it[responseTimeMs] = entry.responseTimeMs
        it[errorMessage] = entry.errorMessage?.take(ERROR_MESSAGE_MAX_LENGTH)
    }
    alert?.let { queued ->
        val inserted = PendingAlertTable.insert {
            it[monitorId] = queued.monitorId
            it[event] = queued.event
            it[subject] = queued.subject
            it[body] = queued.body
            it[channelIds] = queued.channelIds.joinToString(",")
            it[createdAt] = queued.createdAt
        }
        inserted[PendingAlertTable.id]
    }
}

/**
 * All queued alerts, oldest first.
 */
suspend fun pendingAlerts(database: Database): List<PendingAlert> = dbRead(database) {
    PendingAlertTable.selectAll()
        .orderBy(PendingAlertTable.id, SortOrder.ASC)
        .map { it.toPendingAlert() }
}

/**
 * The queued alert with [alertId], or null when it was delivered (deleted) in the meantime.
 */
suspend fun pendingAlertById(database: Database, alertId: Long): PendingAlert? = dbRead(database) {
    PendingAlertTable.selectAll()
        .where { PendingAlertTable.id eq alertId }
        .firstOrNull()?.toPendingAlert()
}

private fun ResultRow.toPendingAlert() = PendingAlert(
    id = this[PendingAlertTable.id],
    monitorId = this[PendingAlertTable.monitorId],
    event = this[PendingAlertTable.event],
    subject = this[PendingAlertTable.subject],
    body = this[PendingAlertTable.body],
    channelIds = this[PendingAlertTable.channelIds].split(',').filter { it.isNotEmpty() },
    createdAt = this[PendingAlertTable.createdAt]
)

/**
 * Removes a fully delivered (or deliberately dropped) alert.
 */
suspend fun deleteAlert(database: Database, alertId: Long): Int = dbWrite(database) {
    PendingAlertTable.deleteWhere { PendingAlertTable.id eq alertId }
}

/**
 * Narrows a partially delivered alert to the channels that still owe a confirmation.
 */
suspend fun keepAlertChannels(database: Database, alertId: Long, remaining: List<String>): Int = dbWrite(database) {
    PendingAlertTable.update({ PendingAlertTable.id eq alertId }) {
        it[channelIds] = remaining.joinToString(",")
    }
}

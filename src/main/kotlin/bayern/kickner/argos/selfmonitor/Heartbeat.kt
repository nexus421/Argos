package bayern.kickner.argos.selfmonitor

import bayern.kickner.argos.db.SelfMonitorTable
import bayern.kickner.argos.db.dbRead
import bayern.kickner.argos.db.dbWrite
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant

/**
 * Persists the current heartbeat timestamp into the self-monitoring table.
 *
 * @param database Connected database instance.
 * @param now Current timestamp to record.
 */
suspend fun writeHeartbeat(database: Database, now: Instant) = dbWrite(database) {
    SelfMonitorTable.deleteAll()
    SelfMonitorTable.insert {
        it[id] = 0
        it[lastHeartbeat] = now
    }
}

/**
 * Reads the latest recorded heartbeat timestamp from the database.
 *
 * @param database Connected database instance.
 * @return Latest recorded [Instant], or null if no previous heartbeat exists.
 */
suspend fun readLastHeartbeat(database: Database): Instant? = dbRead(database) {
    SelfMonitorTable.selectAll().firstOrNull()?.get(SelfMonitorTable.lastHeartbeat)
}

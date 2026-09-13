package bayern.kickner.argos.selfmonitor

import bayern.kickner.argos.db.SelfMonitorTable
import bayern.kickner.argos.db.connectDatabase
import bayern.kickner.argos.db.dbRead
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.nio.file.Files
import java.time.Instant

class HeartbeatTest : FunSpec({

    test("no heartbeat on a fresh database") {
        val appDatabase = connectDatabase(Files.createTempDirectory("argos-hb").toFile().absolutePath)
        readLastHeartbeat(appDatabase.database).shouldBeNull()
        appDatabase.close()
    }

    test("the latest heartbeat replaces the previous one and is read back") {
        val appDatabase = connectDatabase(Files.createTempDirectory("argos-hb").toFile().absolutePath)
        val first = Instant.parse("2026-01-01T12:00:00Z")
        val second = first.plusSeconds(30)

        writeHeartbeat(appDatabase.database, first)
        writeHeartbeat(appDatabase.database, second)

        readLastHeartbeat(appDatabase.database) shouldBe second
        dbRead(appDatabase.database) { SelfMonitorTable.selectAll().count() } shouldBe 1
        appDatabase.close()
    }
})

package bayern.kickner.argos.db

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.nio.file.Files
import java.time.Instant

private fun pragma(name: String): String =
    TransactionManager.current().exec("PRAGMA $name") { rs -> rs.next(); rs.getString(1) } ?: "null"

class DatabaseTest : FunSpec({

    test("writes and reads a CheckHistory record using the serialized write dispatcher") {
        val tempDir = Files.createTempDirectory("argos-db-test").toFile()
        val appDatabase = connectDatabase(tempDir.absolutePath)
        val database = appDatabase.database

        dbWrite(database) {
            CheckHistoryTable.insert {
                it[monitorId] = "m1"
                it[timestamp] = Instant.now()
                it[success] = true
                it[responseTimeMs] = 42.0
                it[errorMessage] = null
            }
        }

        val rows = dbRead(database) { CheckHistoryTable.selectAll().toList() }
        rows.size shouldBe 1
        rows.first()[CheckHistoryTable.monitorId] shouldBe "m1"

        appDatabase.close()
        tempDir.deleteRecursively()
    }

    test("enables WAL journal mode and a 5 s busy timeout on pooled connections") {
        val tempDir = Files.createTempDirectory("argos-db-wal").toFile()
        val appDatabase = connectDatabase(tempDir.absolutePath)

        dbRead(appDatabase.database) { pragma("journal_mode") } shouldBe "wal"
        dbRead(appDatabase.database) { pragma("busy_timeout") } shouldBe "5000"

        appDatabase.close()
        tempDir.deleteRecursively()
    }

    test("close releases the connection pool") {
        val tempDir = Files.createTempDirectory("argos-db-close").toFile()
        val appDatabase = connectDatabase(tempDir.absolutePath)

        appDatabase.close()

        shouldThrowAny { dbRead(appDatabase.database) { CheckHistoryTable.selectAll().count() } }
        tempDir.deleteRecursively()
    }
})

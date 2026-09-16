package bayern.kickner.argos.db

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Files
import java.time.Instant

private fun freshDir() = Files.createTempDirectory("argos-pending").toFile().absolutePath

private val now: Instant = Instant.parse("2026-01-01T12:00:00Z")

class PendingAlertsTest : FunSpec({

    test("recordCheck stores the check result and the alert together and returns the alert id") {
        val db = connectDatabase(freshDir())
        val entry = CheckHistoryEntry("m1", now, false, 12.5, "Connection refused")
        val alert = PendingAlert(null, "m1", "DOWN", "M is DOWN", "Connection refused", listOf("mail", "hook"), now)

        val id = recordCheck(db.database, entry, alert).shouldNotBeNull()

        latestResults(db.database, "m1", 5).single().success shouldBe false
        pendingAlerts(db.database).single().let {
            it.id shouldBe id
            it.monitorId shouldBe "m1"
            it.event shouldBe "DOWN"
            it.channelIds shouldBe listOf("mail", "hook")
            it.createdAt shouldBe now
        }
        db.close()
    }

    test("recordCheck without an alert only stores the result") {
        val db = connectDatabase(freshDir())

        recordCheck(db.database, CheckHistoryEntry("m1", now, true, 1.0, null), null).shouldBeNull()

        latestResults(db.database, "m1", 5) shouldHaveSize 1
        pendingAlerts(db.database).shouldBeEmpty()
        db.close()
    }

    test("a delivered alert is deleted, a partially delivered one keeps only the open channels") {
        val db = connectDatabase(freshDir())
        val entry = CheckHistoryEntry("m1", now, false, 1.0, null)
        val id = recordCheck(db.database, entry, PendingAlert(null, "m1", "DOWN", "s", "b", listOf("mail", "hook"), now)).shouldNotBeNull()

        keepAlertChannels(db.database, id, listOf("hook")) shouldBe 1
        pendingAlerts(db.database).single().channelIds shouldBe listOf("hook")

        deleteAlert(db.database, id) shouldBe 1
        pendingAlerts(db.database).shouldBeEmpty()
        db.close()
    }

    test("pendingAlertById returns the current row, null once it is gone") {
        val db = connectDatabase(freshDir())
        val id = recordCheck(db.database, CheckHistoryEntry("m1", now, false, 1.0, null), PendingAlert(null, "m1", "DOWN", "s", "b", listOf("mail", "hook"), now)).shouldNotBeNull()

        keepAlertChannels(db.database, id, listOf("hook"))
        pendingAlertById(db.database, id).shouldNotBeNull().channelIds shouldBe listOf("hook")

        deleteAlert(db.database, id)
        pendingAlertById(db.database, id).shouldBeNull()
        pendingAlertById(db.database, 4711).shouldBeNull()
        db.close()
    }

    test("pendingAlerts lists queued alerts oldest first") {
        val db = connectDatabase(freshDir())
        listOf("first", "second", "third").forEachIndexed { index, subject ->
            recordCheck(db.database, CheckHistoryEntry("m1", now.plusSeconds(index.toLong()), false, 1.0, null), PendingAlert(null, "m1", "DOWN", subject, "b", listOf("mail"), now.plusSeconds(index.toLong())))
        }

        pendingAlerts(db.database).map { it.subject } shouldBe listOf("first", "second", "third")
        db.close()
    }

    test("failingSince is the first failure after the last success, null without an open streak") {
        val db = connectDatabase(freshDir())
        listOf(false to 0L, true to 60L, false to 120L, false to 180L).forEach { (ok, offset) ->
            recordCheck(db.database, CheckHistoryEntry("m1", now.plusSeconds(offset), ok, 1.0, null), null)
        }
        recordCheck(db.database, CheckHistoryEntry("never-ok", now, false, 1.0, null), null)
        recordCheck(db.database, CheckHistoryEntry("healthy", now, true, 1.0, null), null)

        failingSince(db.database, "m1") shouldBe now.plusSeconds(120)
        failingSince(db.database, "never-ok") shouldBe now
        failingSince(db.database, "healthy").shouldBeNull()
        failingSince(db.database, "unknown").shouldBeNull()
        db.close()
    }

    test("a fresh database is stamped with the current schema version") {
        val db = connectDatabase(freshDir())

        transaction(db.database) { SchemaVersionTable.selectAll().single()[SchemaVersionTable.version] } shouldBe SCHEMA_VERSION
        db.close()
    }

    test("a database from before schema versioning is migrated without losing history") {
        val dir = freshDir()
        connectDatabase(dir).also { first ->
            recordCheck(first.database, CheckHistoryEntry("m1", now, true, 1.0, null), null)
            // Shape of a v0.0.1-test database: history and heartbeat tables only
            transaction(first.database) { SchemaUtils.drop(SchemaVersionTable, PendingAlertTable) }
            first.close()
        }

        val second = connectDatabase(dir)

        transaction(second.database) { SchemaVersionTable.selectAll().single()[SchemaVersionTable.version] } shouldBe SCHEMA_VERSION
        latestResults(second.database, "m1", 1) shouldHaveSize 1
        pendingAlerts(second.database).shouldBeEmpty()
        second.close()
    }

    test("a database written by a newer build is refused") {
        val dir = freshDir()
        connectDatabase(dir).also { first ->
            transaction(first.database) {
                SchemaVersionTable.deleteAll()
                SchemaVersionTable.insert { it[version] = SCHEMA_VERSION + 1 }
            }
            first.close()
        }

        shouldThrowAny { connectDatabase(dir) }.message shouldContain "newer"
    }
})

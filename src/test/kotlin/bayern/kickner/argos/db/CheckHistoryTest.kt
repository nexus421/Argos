package bayern.kickner.argos.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.time.Instant

class CheckHistoryTest : FunSpec({

    fun freshDatabase() = connectDatabase(Files.createTempDirectory("argos-history").toFile().absolutePath)

    test("truncates error messages longer than the column allows instead of failing") {
        val appDatabase = freshDatabase()
        val longMessage = "x".repeat(ERROR_MESSAGE_MAX_LENGTH + 500)

        insertCheckResult(appDatabase.database, CheckHistoryEntry("m1", Instant.now(), false, 5.0, longMessage))

        val stored = latestResults(appDatabase.database, "m1", limit = 1).single()
        stored.errorMessage?.length shouldBe ERROR_MESSAGE_MAX_LENGTH
        appDatabase.close()
    }

    test("latestResults returns the newest entries first, limited and scoped to the monitor") {
        val appDatabase = freshDatabase()
        val base = Instant.parse("2026-01-01T00:00:00Z")
        listOf(true, false, false, true).forEachIndexed { index, ok ->
            insertCheckResult(appDatabase.database, CheckHistoryEntry("m1", base.plusSeconds(index.toLong()), ok, index.toDouble(), null))
        }
        insertCheckResult(appDatabase.database, CheckHistoryEntry("other", base.plusSeconds(99), false, 0.0, null))

        val latest = latestResults(appDatabase.database, "m1", limit = 3)

        latest.map { it.success } shouldBe listOf(true, false, false)
        latest.map { it.responseTimeMs } shouldBe listOf(3.0, 2.0, 1.0)
        appDatabase.close()
    }

    test("deleteHistoryOlderThan removes only old rows and works across several batches") {
        val appDatabase = freshDatabase()
        val cutoff = Instant.parse("2026-01-10T00:00:00Z")
        repeat(12) { insertCheckResult(appDatabase.database, CheckHistoryEntry("m1", cutoff.minusSeconds(60L + it), true, 1.0, null)) }
        repeat(3) { insertCheckResult(appDatabase.database, CheckHistoryEntry("m1", cutoff.plusSeconds(1L + it), true, 1.0, null)) }

        val deleted = deleteHistoryOlderThan(appDatabase.database, cutoff, batchSize = 5)

        deleted shouldBe 12
        latestResults(appDatabase.database, "m1", limit = 100).size shouldBe 3
        appDatabase.close()
    }
})

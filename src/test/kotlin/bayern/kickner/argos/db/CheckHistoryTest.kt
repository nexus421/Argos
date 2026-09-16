package bayern.kickner.argos.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate

class CheckHistoryTest : FunSpec({

    fun freshDatabase() = connectDatabase(Files.createTempDirectory("argos-history").toFile().absolutePath)

    test("truncates error messages longer than the column allows instead of failing") {
        val appDatabase = freshDatabase()
        val longMessage = "x".repeat(ERROR_MESSAGE_MAX_LENGTH + 500)

        recordCheck(appDatabase.database, CheckHistoryEntry("m1", Instant.now(), false, 5.0, longMessage), null)

        val stored = latestResults(appDatabase.database, "m1", limit = 1).single()
        stored.errorMessage?.length shouldBe ERROR_MESSAGE_MAX_LENGTH
        appDatabase.close()
    }

    test("latestResults returns the newest entries first, limited and scoped to the monitor") {
        val appDatabase = freshDatabase()
        val base = Instant.parse("2026-01-01T00:00:00Z")
        listOf(true, false, false, true).forEachIndexed { index, ok ->
            recordCheck(appDatabase.database, CheckHistoryEntry("m1", base.plusSeconds(index.toLong()), ok, index.toDouble(), null), null)
        }
        recordCheck(appDatabase.database, CheckHistoryEntry("other", base.plusSeconds(99), false, 0.0, null), null)

        val latest = latestResults(appDatabase.database, "m1", limit = 3)

        latest.map { it.success } shouldBe listOf(true, false, false)
        latest.map { it.responseTimeMs } shouldBe listOf(3.0, 2.0, 1.0)
        appDatabase.close()
    }

    test("dailySummaries groups by UTC day: counts, failures and the average latency of successful checks only") {
        val appDatabase = freshDatabase()
        val day1 = Instant.parse("2026-03-01T00:00:00Z")
        val day2 = Instant.parse("2026-03-02T23:59:59Z")
        recordCheck(appDatabase.database, CheckHistoryEntry("m1", day1, true, 100.0, null), null)
        recordCheck(appDatabase.database, CheckHistoryEntry("m1", day1.plusSeconds(60), true, 300.0, null), null)
        recordCheck(appDatabase.database, CheckHistoryEntry("m1", day1.plusSeconds(120), false, 9999.0, "timeout"), null)
        recordCheck(appDatabase.database, CheckHistoryEntry("m1", day2, false, 5000.0, "refused"), null)
        recordCheck(appDatabase.database, CheckHistoryEntry("other", day1, true, 1.0, null), null)
        recordCheck(appDatabase.database, CheckHistoryEntry("m1", day1.minusSeconds(1), true, 1.0, null), null) // before `from`

        val summaries = dailySummaries(appDatabase.database, "m1", from = day1)

        summaries shouldBe listOf(
            DaySummary(LocalDate.parse("2026-03-01"), checks = 3, failed = 1, averageOkMillis = 200.0),
            DaySummary(LocalDate.parse("2026-03-02"), checks = 1, failed = 1, averageOkMillis = null)
        )
        appDatabase.close()
    }

    test("deleteHistoryOlderThan removes only old rows and works across several batches") {
        val appDatabase = freshDatabase()
        val cutoff = Instant.parse("2026-01-10T00:00:00Z")
        repeat(12) { recordCheck(appDatabase.database, CheckHistoryEntry("m1", cutoff.minusSeconds(60L + it), true, 1.0, null), null) }
        repeat(3) { recordCheck(appDatabase.database, CheckHistoryEntry("m1", cutoff.plusSeconds(1L + it), true, 1.0, null), null) }

        val deleted = deleteHistoryOlderThan(appDatabase.database, cutoff, batchSize = 5)

        deleted shouldBe 12
        latestResults(appDatabase.database, "m1", limit = 100).size shouldBe 3
        appDatabase.close()
    }
})

package bayern.kickner.argos.web

import bayern.kickner.argos.config.AppConfig
import bayern.kickner.argos.config.MonitorConfig
import bayern.kickner.argos.config.StatusPageConfig
import bayern.kickner.argos.config.TcpCheckConfig
import bayern.kickner.argos.db.CheckHistoryEntry
import bayern.kickner.argos.db.DaySummary
import bayern.kickner.argos.db.connectDatabase
import bayern.kickner.argos.db.recordCheck
import bayern.kickner.argos.notify.MonitorRuntimeState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

class StatusServiceTest : FunSpec({

    val config = AppConfig(
        monitors = listOf(
            MonitorConfig("up", "Up Service", 60, 10, TcpCheckConfig("h", 1)),
            MonitorConfig("down", "Down Service", 60, 10, TcpCheckConfig("h", 2)),
            MonitorConfig("fresh", "Fresh Service", 60, 10, TcpCheckConfig("h", 3))
        )
    )
    val page = StatusPageConfig("p", "P", listOf("up", "down", "fresh"))

    test("combines latest stored result with runtime trigger state") {
        val appDatabase = connectDatabase(Files.createTempDirectory("argos-status").toFile().absolutePath)
        val at = Instant.parse("2026-01-01T12:00:00Z")
        recordCheck(appDatabase.database, CheckHistoryEntry("up", at.minusSeconds(60), false, 1.0, "old failure"), null)
        recordCheck(appDatabase.database, CheckHistoryEntry("up", at, true, 12.0, null), null)
        recordCheck(appDatabase.database, CheckHistoryEntry("down", at, false, 5000.0, "Connection refused"), null)
        val states = mapOf(
            "up" to MonitorRuntimeState(0, currentlyDown = false),
            "down" to MonitorRuntimeState(3, currentlyDown = true),
            "fresh" to MonitorRuntimeState()
        )
        val service = StatusService(config, appDatabase.database, stateOf = { states[it] })

        val statuses = service.statusesFor(page)

        statuses.map { it.copy(history = emptyList()) } shouldBe listOf(
            MonitorStatus("up", "Up Service", MonitorState.UP, at, 12.0, null, emptyList()),
            MonitorStatus("down", "Down Service", MonitorState.DOWN, at, 5000.0, "Connection refused", emptyList()),
            MonitorStatus("fresh", "Fresh Service", MonitorState.UNKNOWN, null, null, null, emptyList())
        )
        appDatabase.close()
    }

    test("history covers the last HISTORY_DAYS UTC days ending today, empty days where nothing was stored") {
        val appDatabase = connectDatabase(Files.createTempDirectory("argos-status").toFile().absolutePath)
        val today = LocalDate.now(ZoneOffset.UTC)
        val noon = today.atTime(12, 0).toInstant(ZoneOffset.UTC)
        recordCheck(appDatabase.database, CheckHistoryEntry("up", noon, true, 10.0, null), null)
        recordCheck(appDatabase.database, CheckHistoryEntry("up", noon.minus(3, ChronoUnit.DAYS), false, 1.0, "x"), null)
        recordCheck(appDatabase.database, CheckHistoryEntry("up", noon.minus(HISTORY_DAYS.toLong(), ChronoUnit.DAYS), true, 1.0, null), null) // just outside
        val service = StatusService(config, appDatabase.database, stateOf = { MonitorRuntimeState() })

        val history = service.statusesFor(page).first { it.id == "up" }.history

        history shouldHaveSize HISTORY_DAYS
        history.first() shouldBe DaySummary(today.minusDays(HISTORY_DAYS - 1L), checks = 0, failed = 0, averageOkMillis = null)
        history.last() shouldBe DaySummary(today, checks = 1, failed = 0, averageOkMillis = 10.0)
        history[HISTORY_DAYS - 4] shouldBe DaySummary(today.minusDays(3), checks = 1, failed = 1, averageOkMillis = null)
        history.count { it.checks > 0 } shouldBe 2
        history.map { it.day } shouldBe (0 until HISTORY_DAYS).map { today.minusDays(HISTORY_DAYS - 1L - it) }
        appDatabase.close()
    }

    test("history is recomputed at most every HISTORY_CACHE_TTL per monitor while the latest result stays live") {
        val appDatabase = connectDatabase(Files.createTempDirectory("argos-status").toFile().absolutePath)
        var now = Instant.parse("2026-01-10T12:00:00Z")
        val service = StatusService(config, appDatabase.database, { MonitorRuntimeState() }) { now }
        recordCheck(appDatabase.database, CheckHistoryEntry("up", now, true, 10.0, null), null)
        service.statusesFor(page).first { it.id == "up" }.history.last() shouldBe DaySummary(LocalDate.parse("2026-01-10"), 1, 0, 10.0)

        val later = now.plusSeconds(30)
        recordCheck(appDatabase.database, CheckHistoryEntry("up", later, false, 1.0, "x"), null)
        now = now.plusSeconds(59)
        val withinTtl = service.statusesFor(page).first { it.id == "up" }
        withinTtl.lastCheck shouldBe later                                                      // live
        withinTtl.history.last() shouldBe DaySummary(LocalDate.parse("2026-01-10"), 1, 0, 10.0)  // cached

        now = now.plusSeconds(2)
        service.statusesFor(page).first { it.id == "up" }.history.last() shouldBe DaySummary(LocalDate.parse("2026-01-10"), 2, 1, 10.0)
        appDatabase.close()
    }
})

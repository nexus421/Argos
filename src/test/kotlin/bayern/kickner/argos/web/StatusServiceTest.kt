package bayern.kickner.argos.web

import bayern.kickner.argos.config.AppConfig
import bayern.kickner.argos.config.MonitorConfig
import bayern.kickner.argos.config.StatusPageConfig
import bayern.kickner.argos.config.TcpCheckConfig
import bayern.kickner.argos.db.CheckHistoryEntry
import bayern.kickner.argos.db.connectDatabase
import bayern.kickner.argos.db.insertCheckResult
import bayern.kickner.argos.notify.MonitorRuntimeState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.time.Instant

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
        insertCheckResult(appDatabase.database, CheckHistoryEntry("up", at.minusSeconds(60), false, 1.0, "old failure"))
        insertCheckResult(appDatabase.database, CheckHistoryEntry("up", at, true, 12.0, null))
        insertCheckResult(appDatabase.database, CheckHistoryEntry("down", at, false, 5000.0, "Connection refused"))
        val states = mapOf(
            "up" to MonitorRuntimeState(0, currentlyDown = false),
            "down" to MonitorRuntimeState(3, currentlyDown = true),
            "fresh" to MonitorRuntimeState()
        )
        val service = StatusService(config, appDatabase.database) { states[it] }

        val statuses = service.statusesFor(page)

        statuses shouldBe listOf(
            MonitorStatus("up", "Up Service", MonitorState.UP, at, 12.0, null),
            MonitorStatus("down", "Down Service", MonitorState.DOWN, at, 5000.0, "Connection refused"),
            MonitorStatus("fresh", "Fresh Service", MonitorState.UNKNOWN, null, null, null)
        )
        appDatabase.close()
    }
})

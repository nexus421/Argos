package bayern.kickner.argos.selfmonitor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class GapDetectionTest : FunSpec({

    test("reports no gap on initial boot with no previous heartbeat") {
        hasUnplannedGap(lastHeartbeat = null, now = Instant.now(), thresholdMinutes = 2) shouldBe false
    }

    test("reports no gap when duration is within threshold") {
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val last = now.minusSeconds(60)
        hasUnplannedGap(last, now, thresholdMinutes = 2) shouldBe false
    }

    test("reports a gap when duration exceeds threshold") {
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val last = now.minusSeconds(180)
        hasUnplannedGap(last, now, thresholdMinutes = 2) shouldBe true
    }
})

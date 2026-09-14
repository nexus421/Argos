package bayern.kickner.argos.scheduler

import bayern.kickner.argos.config.MonitorConfig
import bayern.kickner.argos.config.TcpCheckConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

private fun monitor(id: String, interval: Long) = MonitorConfig(
    id = id, name = id, intervalSeconds = interval, timeoutSeconds = 1,
    check = TcpCheckConfig("host", 80)
)

class DueTrackerTest : FunSpec({

    test("nextAlignedSecond returns the smallest multiple of the interval not before the given second") {
        nextAlignedSecond(100, 60) shouldBe 120
        nextAlignedSecond(120, 60) shouldBe 120
        nextAlignedSecond(121, 60) shouldBe 180
    }

    test("every monitor is due once right after start, then at its aligned second and not in between") {
        val tracker = DueTracker(listOf(monitor("a", 60), monitor("b", 45), monitor("c", 40)), now = 100)

        tracker.due(100).map { it.id } shouldBe listOf("a", "b", "c")
        tracker.due(119).shouldBeEmpty()
        tracker.due(120).map { it.id } shouldBe listOf("a", "c")
        tracker.due(120).shouldBeEmpty()
        tracker.due(135).map { it.id } shouldBe listOf("b")
    }

    test("a skipped tick does not lose a due monitor and the grid is kept afterwards") {
        val tracker = DueTracker(listOf(monitor("a", 60)), now = 100)

        tracker.due(121).map { it.id } shouldBe listOf("a")
        tracker.due(179).shouldBeEmpty()
        tracker.due(180).map { it.id } shouldBe listOf("a")
    }

    test("after a long pause each monitor is due exactly once") {
        val tracker = DueTracker(listOf(monitor("a", 10)), now = 100)

        tracker.due(3700).map { it.id } shouldBe listOf("a")
        tracker.due(3701).shouldBeEmpty()
        tracker.due(3710).map { it.id } shouldBe listOf("a")
    }

    test("realign after a clock jump backwards schedules monitors on the new grid") {
        val tracker = DueTracker(listOf(monitor("a", 60)), now = 3600)

        tracker.realign(100)

        tracker.due(119).shouldBeEmpty()
        tracker.due(120).map { it.id } shouldBe listOf("a")
    }
})

class TickGapTest : FunSpec({

    test("consecutive seconds and jitter up to 5 s are not a gap") {
        classifyTickGap(lastTick = 100, now = 101, thresholdMinutes = 2) shouldBe TickGap.None
        classifyTickGap(lastTick = 100, now = 105, thresholdMinutes = 2) shouldBe TickGap.None
        classifyTickGap(lastTick = 100, now = 100, thresholdMinutes = 2) shouldBe TickGap.None
    }

    test("a pause above 5 s is reported, without notification below the threshold") {
        classifyTickGap(lastTick = 100, now = 130, thresholdMinutes = 2) shouldBe TickGap.Paused(seconds = 30, notify = false)
    }

    test("a pause at or above the threshold requests a notification") {
        classifyTickGap(lastTick = 100, now = 220, thresholdMinutes = 2) shouldBe TickGap.Paused(seconds = 120, notify = true)
    }

    test("a clock moving backwards by more than a second is detected") {
        classifyTickGap(lastTick = 1000, now = 900, thresholdMinutes = 2) shouldBe TickGap.ClockJumpedBack(seconds = 100)
        classifyTickGap(lastTick = 1000, now = 999, thresholdMinutes = 2) shouldBe TickGap.None
    }
})

package bayern.kickner.argos.notify

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TriggerLogicTest : FunSpec({

    test("triggers DOWN only after flappingThreshold consecutive failures") {
        var state = MonitorRuntimeState()
        repeat(2) {
            val (next, decision) = evaluateTrigger(state, checkSucceeded = false, flappingThreshold = 3)
            decision shouldBe TriggerDecision.None
            state = next
        }
        val (finalState, decision) = evaluateTrigger(state, checkSucceeded = false, flappingThreshold = 3)
        decision shouldBe TriggerDecision.SendDownNotification
        finalState.currentlyDown shouldBe true
    }

    test("triggers recovery immediately when a check succeeds after DOWN") {
        val downState = MonitorRuntimeState(consecutiveFailures = 3, currentlyDown = true)
        val (next, decision) = evaluateTrigger(downState, checkSucceeded = true, flappingThreshold = 3)
        decision shouldBe TriggerDecision.SendRecoveryNotification
        next.currentlyDown shouldBe false
    }

    test("does not trigger when failure count is below threshold") {
        val (_, decision) = evaluateTrigger(MonitorRuntimeState(), checkSucceeded = false, flappingThreshold = 3)
        decision shouldBe TriggerDecision.None
    }

    test("does not re-trigger DOWN while monitor remains down") {
        val downState = MonitorRuntimeState(consecutiveFailures = 5, currentlyDown = true)
        val (_, decision) = evaluateTrigger(downState, checkSucceeded = false, flappingThreshold = 3)
        decision shouldBe TriggerDecision.None
    }
})

class StateFromHistoryTest : FunSpec({

    test("no history yields the initial state") {
        stateFromHistory(latestFirst = emptyList(), flappingThreshold = 3) shouldBe MonitorRuntimeState()
    }

    test("counts leading failures and marks DOWN when they reach the threshold") {
        stateFromHistory(latestFirst = listOf(false, false, false, true), flappingThreshold = 3) shouldBe
            MonitorRuntimeState(consecutiveFailures = 3, currentlyDown = true)
    }

    test("failures below the threshold are remembered without DOWN state") {
        stateFromHistory(latestFirst = listOf(false, false, true, false), flappingThreshold = 3) shouldBe
            MonitorRuntimeState(consecutiveFailures = 2, currentlyDown = false)
    }

    test("a most recent success means UP regardless of older failures") {
        stateFromHistory(latestFirst = listOf(true, false, false, false), flappingThreshold = 3) shouldBe
            MonitorRuntimeState(consecutiveFailures = 0, currentlyDown = false)
    }
})

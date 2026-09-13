package bayern.kickner.argos.checks

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class BlockingTimeoutTest : FunSpec({

    test("returns the block result when it finishes in time") {
        blockingWithTimeout(1000) { "done" } shouldBe "done"
    }

    test("returns null once the timeout elapses even if the blocking call keeps running") {
        val start = System.currentTimeMillis()

        val result = blockingWithTimeout(200) { Thread.sleep(3000); "late" }

        result.shouldBeNull()
        (System.currentTimeMillis() - start) shouldBeLessThan 1500
    }
})

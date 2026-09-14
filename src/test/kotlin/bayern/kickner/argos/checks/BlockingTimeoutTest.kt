package bayern.kickner.argos.checks

import bayern.kickner.argos.db.connectDatabase
import bayern.kickner.argos.db.dbRead
import bayern.kickner.klogger.KLogger
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.nio.file.Files
import java.util.Collections

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

    test("a saturated check pool is reported once the queued call starts, and database reads are not held up by it") {
        val logged = Collections.synchronizedList(mutableListOf<String>())
        KLogger.configure { logToCustom { _, _, message -> logged += message } }
        val db = connectDatabase(Files.createTempDirectory("argos-blocking").toFile().absolutePath)

        coroutineScope {
            // Every check thread is stuck in a "DNS lookup"; the extra call has to wait for a free one
            val stuck = (1..CHECK_THREADS).map { async { blockingWithTimeout(5000) { Thread.sleep(2500) } } }
            val queued = async { blockingWithTimeout(5000) { "ran" } }

            val readStart = System.currentTimeMillis()
            dbRead(db.database) { 1 } shouldBe 1
            (System.currentTimeMillis() - readStart) shouldBeLessThan 1000

            queued.await() shouldBe "ran"
            stuck.awaitAll()
        }

        // At least one: a thread left over from an earlier test may push a second call into the queue as well
        val warnings = logged.filter { it.contains("saturated") }
        warnings.shouldNotBeEmpty()
        warnings.forEach { it shouldContain "$CHECK_THREADS" }
        db.close()
    }

    test("no saturation warning while threads are free") {
        val logged = Collections.synchronizedList(mutableListOf<String>())
        KLogger.configure { logToCustom { _, _, message -> logged += message } }

        blockingWithTimeout(1000) { "quick" } shouldBe "quick"

        logged.filter { it.contains("saturated") }.shouldBeEmpty()
    }
})

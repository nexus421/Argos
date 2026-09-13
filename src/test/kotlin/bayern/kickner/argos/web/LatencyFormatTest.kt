package bayern.kickner.argos.web

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class LatencyFormatTest : FunSpec({

    test("sub-millisecond latencies keep two decimals") {
        formatLatency(0.036) shouldBe "0.04 ms"
    }

    test("single-digit milliseconds keep one decimal") {
        formatLatency(1.44) shouldBe "1.4 ms"
    }

    test("larger latencies are whole milliseconds") {
        formatLatency(12.0) shouldBe "12 ms"
        formatLatency(5000.4) shouldBe "5000 ms"
    }

    test("formatting is locale independent") {
        java.util.Locale.setDefault(java.util.Locale.GERMANY)
        formatLatency(0.5) shouldBe "0.50 ms"
    }
})

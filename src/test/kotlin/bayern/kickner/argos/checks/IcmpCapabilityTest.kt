package bayern.kickner.argos.checks

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class IcmpCapabilityTest : FunSpec({

    test("root's full capability set includes CAP_NET_RAW") {
        hasNetRawCapability("CapInh:\t0000000000000000\nCapPrm:\t000001ffffffffff\nCapEff:\t000001ffffffffff\n") shouldBe true
    }

    test("an empty effective set lacks CAP_NET_RAW") {
        hasNetRawCapability("CapEff:\t0000000000000000\n") shouldBe false
    }

    test("exactly bit 13 grants CAP_NET_RAW (AmbientCapabilities=CAP_NET_RAW)") {
        hasNetRawCapability("CapEff:\t0000000000002000\n") shouldBe true
        hasNetRawCapability("CapEff:\t0000000000001000\n") shouldBe false
    }

    test("a status file without CapEff is treated as capable so non-Linux hosts get no false warning") {
        hasNetRawCapability("Name:\tjava\n") shouldBe true
    }
})

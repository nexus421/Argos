package bayern.kickner.argos.checks

import bayern.kickner.argos.config.DnsCheckConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.net.InetAddress

class DnsCheckTest : FunSpec({

    test("resolves localhost successfully") {
        val result = executeDnsCheck(DnsCheckConfig("localhost"), timeoutSeconds = 2)
        result.success shouldBe true
    }

    test("reports failure for mismatched expected IP") {
        val result = executeDnsCheck(DnsCheckConfig("localhost", expectedIp = "203.0.113.99"), timeoutSeconds = 2)
        result.success shouldBe false
    }

    test("an IPv6 expectedIp in compressed form matches the JDK's expanded text form") {
        val resolver: (String) -> Array<InetAddress> = { arrayOf(InetAddress.getByName("2001:db8:0:0:0:0:0:1")) }

        val result = executeDnsCheck(DnsCheckConfig("v6.example", expectedIp = "2001:db8::1"), timeoutSeconds = 2, resolver = resolver)

        result.success shouldBe true
    }

    test("an expectedIp that is not an IP literal fails without a lookup") {
        val resolver: (String) -> Array<InetAddress> = { arrayOf(InetAddress.getLoopbackAddress()) }

        val result = executeDnsCheck(DnsCheckConfig("localhost", expectedIp = "gateway"), timeoutSeconds = 2, resolver = resolver)

        result.success shouldBe false
        result.message shouldContain "not a valid IP address"
    }

    test("fails with a timeout message when resolution takes longer than timeoutSeconds") {
        val slowResolver: (String) -> Array<InetAddress> = { Thread.sleep(5000); arrayOf(InetAddress.getLoopbackAddress()) }

        val result = executeDnsCheck(DnsCheckConfig("slow.example"), timeoutSeconds = 1, resolver = slowResolver)

        result.success shouldBe false
        result.message shouldContain "timed out"
    }
})

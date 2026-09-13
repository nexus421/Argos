package bayern.kickner.argos.checks

import bayern.kickner.argos.config.PingCheckConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.nio.file.Files

/** Writes an executable script that mimics iputils ping and returns its path. */
private fun fakePing(body: String): String {
    val file = Files.createTempFile("fake-ping", ".sh").toFile()
    file.writeText("#!/bin/sh\n$body\n")
    file.setExecutable(true)
    return file.absolutePath
}

private const val SUCCESS_OUTPUT = """PING 127.0.0.1 (127.0.0.1) 56(84) bytes of data.
64 bytes from 127.0.0.1: icmp_seq=1 ttl=64 time=0.036 ms

--- 127.0.0.1 ping statistics ---
1 packets transmitted, 1 received, 0% packet loss, time 0ms
rtt min/avg/max/mdev = 0.036/0.036/0.036/0.000 ms"""

class PingOutputParsingTest : FunSpec({

    test("exit 0 with a time line yields UP with the reported RTT") {
        val result = parsePingOutput(exitCode = 0, output = SUCCESS_OUTPUT, timeoutSeconds = 2, fallbackMillis = 5.0)
        result.success shouldBe true
        result.responseTimeMs shouldBe 0.036
        result.message.shouldBeNull()
    }

    test("exit 0 without a parseable time falls back to the measured duration") {
        val result = parsePingOutput(0, "1 packets transmitted, 1 received", timeoutSeconds = 2, fallbackMillis = 5.0)
        result.success shouldBe true
        result.responseTimeMs shouldBe 5.0
    }

    test("exit 1 is DOWN with a no-reply message") {
        val output = "PING 192.0.2.1 (192.0.2.1) 56(84) bytes of data.\n\n--- 192.0.2.1 ping statistics ---\n1 packets transmitted, 0 received, 100% packet loss, time 0ms"
        val result = parsePingOutput(1, output, timeoutSeconds = 2, fallbackMillis = 2000.0)
        result.success shouldBe false
        result.message shouldContain "No ICMP reply within 2 s"
    }

    test("exit 1 with an ICMP error line reports that line") {
        val output = "PING 10.0.0.9 (10.0.0.9) 56(84) bytes of data.\nFrom 10.0.0.1 icmp_seq=1 Destination Host Unreachable\n\n--- 10.0.0.9 ping statistics ---\n1 packets transmitted, 0 received, +1 errors, 100% packet loss, time 0ms"
        parsePingOutput(1, output, 2, 1.0).message shouldContain "Destination Host Unreachable"
    }

    test("exit 2 is DOWN with ping's error message, without the tool's own name prefix") {
        parsePingOutput(2, "ping: nope.invalid: Name or service not known", 2, 1.0).message shouldBe "nope.invalid: Name or service not known"
        parsePingOutput(2, "/usr/bin/ping: nope.invalid: Name or service not known", 2, 1.0).message shouldBe "nope.invalid: Name or service not known"
        parsePingOutput(2, "", 2, 1.0).message shouldBe "exit code 2"
    }
})

class PingBackendDetectionTest : FunSpec({

    test("uses the first executable candidate on Linux") {
        val binary = fakePing("exit 0")
        detectPingBackend(osName = "Linux", candidates = listOf("/nonexistent/ping", binary), path = "") shouldBe PingBackend.Binary(binary)
    }

    test("searches PATH when no candidate matches") {
        val dir = Files.createTempDirectory("fake-ping-dir").toFile()
        val binary = File(dir, "ping").apply { writeText("#!/bin/sh\nexit 0\n"); setExecutable(true) }
        detectPingBackend(osName = "Linux", candidates = emptyList(), path = "/nonexistent:${dir.absolutePath}") shouldBe PingBackend.Binary(binary.absolutePath)
    }

    test("falls back to the JDK when no candidate is executable") {
        detectPingBackend(osName = "Linux", candidates = listOf("/nonexistent/ping"), path = "") shouldBe PingBackend.Jdk
    }

    test("never uses a binary on non-Linux systems") {
        val binary = fakePing("exit 0")
        detectPingBackend(osName = "Mac OS X", candidates = listOf(binary)) shouldBe PingBackend.Jdk
    }
})

class PingBinaryExecutionTest : FunSpec({

    test("a replying host is UP with the RTT from the tool") {
        val binary = fakePing("cat <<'OUT'\n$SUCCESS_OUTPUT\nOUT\nexit 0")

        val result = executePingCheck(PingCheckConfig("127.0.0.1"), timeoutSeconds = 2, backend = PingBackend.Binary(binary))

        result.success shouldBe true
        result.responseTimeMs shouldBe 0.036
    }

    test("passes -c 1, -W timeout, -n and the host after -- to the binary") {
        val argsFile = Files.createTempFile("fake-ping-args", ".txt").toFile()
        val binary = fakePing("echo \"\$@\" > ${argsFile.absolutePath}\nexit 1")

        executePingCheck(PingCheckConfig("-evil.example"), timeoutSeconds = 3, backend = PingBackend.Binary(binary))

        argsFile.readText().trim() shouldBe "-c 1 -W 3 -n -- -evil.example"
    }

    test("a silent host is DOWN") {
        val binary = fakePing("echo '1 packets transmitted, 0 received, 100% packet loss, time 0ms'\nexit 1")

        val result = executePingCheck(PingCheckConfig("192.0.2.1"), timeoutSeconds = 1, backend = PingBackend.Binary(binary))

        result.success shouldBe false
        result.message shouldContain "No ICMP reply"
    }

    test("a hanging binary is killed after the timeout and reported as timed out") {
        val binary = fakePing("sleep 30\nexit 0")
        val start = System.currentTimeMillis()

        val result = executePingCheck(PingCheckConfig("10.0.0.1"), timeoutSeconds = 1, backend = PingBackend.Binary(binary))

        result.success shouldBe false
        result.message shouldContain "timed out"
        (System.currentTimeMillis() - start) shouldBeLessThan 4000
    }

    test("the real iputils ping reaches loopback without privileges").config(enabled = File("/usr/bin/ping").canExecute()) {
        val result = executePingCheck(PingCheckConfig("127.0.0.1"), timeoutSeconds = 2, backend = PingBackend.Binary("/usr/bin/ping"))

        result.success shouldBe true
        result.responseTimeMs shouldBeGreaterThan 0.0
        result.responseTimeMs shouldBeLessThan 50.0
    }
})

class IcmpProbeTest : FunSpec({

    test("binary backend probe passes when the tool answers exit 0") {
        probeIcmp(PingBackend.Binary(fakePing("exit 0"))).shouldBeNull()
    }

    test("binary backend probe reports the tool's error when ICMP is refused") {
        val reason = probeIcmp(PingBackend.Binary(fakePing("echo 'ping: socket: Operation not permitted'\nexit 2")))
        reason.shouldNotBeNull() shouldContain "Operation not permitted"
    }

    test("JDK backend probe reflects the process capabilities") {
        val reason = probeIcmp(PingBackend.Jdk)
        val capable = hasNetRawCapability(File("/proc/self/status").readText())
        if (capable) reason.shouldBeNull() else reason.shouldNotBeNull() shouldContain "CAP_NET_RAW"
    }
})

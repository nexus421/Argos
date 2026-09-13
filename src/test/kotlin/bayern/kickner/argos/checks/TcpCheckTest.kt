package bayern.kickner.argos.checks

import bayern.kickner.argos.config.TcpCheckConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.net.ServerSocket

class TcpCheckTest : FunSpec({

    test("reports success for an open port") {
        val serverSocket = ServerSocket(0)
        val result = executeTcpCheck(TcpCheckConfig("localhost", serverSocket.localPort), timeoutSeconds = 2)
        result.success shouldBe true
        serverSocket.close()
    }

    test("reports failure for a closed port") {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort
        serverSocket.close()

        val result = executeTcpCheck(TcpCheckConfig("localhost", port), timeoutSeconds = 2)
        result.success shouldBe false
    }
})

package bayern.kickner.argos.checks

import bayern.kickner.argos.config.TcpCheckConfig
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Attempts a TCP socket connection to [config] within [timeoutSeconds]. The timeout covers name
 * resolution as well, which `Socket.connect`'s own timeout does not.
 *
 * @param config TCP check target host and port.
 * @param timeoutSeconds Connection timeout in seconds.
 * @return [CheckResult] indicating connection outcome and latency.
 */
suspend fun executeTcpCheck(config: TcpCheckConfig, timeoutSeconds: Long): CheckResult {
    val startNanos = System.nanoTime()
    val timeoutMillis = timeoutSeconds.secondsToMillis()
    val attempt = blockingWithTimeout(timeoutMillis) {
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(config.host, config.port), timeoutMillis.toInt())
            }
        }
    } ?: return CheckResult(false, elapsedMillisSince(startNanos), "Connection to ${config.host}:${config.port} timed out after $timeoutSeconds s")

    return attempt.fold(
        onSuccess = { CheckResult(true, elapsedMillisSince(startNanos), null) },
        onFailure = { CheckResult(false, elapsedMillisSince(startNanos), it.message ?: it::class.simpleName) }
    )
}

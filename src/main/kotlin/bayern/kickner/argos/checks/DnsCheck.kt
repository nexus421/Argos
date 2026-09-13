package bayern.kickner.argos.checks

import bayern.kickner.argos.config.DnsCheckConfig
import java.net.InetAddress

/**
 * Resolves the hostname specified in [config] and validates the resolved IP address if configured.
 *
 * @param config DNS check configuration.
 * @param timeoutSeconds Resolution timeout in seconds; the JDK resolver has none of its own.
 * @param resolver Name resolution function, replaceable for tests.
 * @return [CheckResult] indicating whether resolution succeeded and IP matched.
 */
suspend fun executeDnsCheck(
    config: DnsCheckConfig,
    timeoutSeconds: Long,
    resolver: (String) -> Array<InetAddress> = InetAddress::getAllByName
): CheckResult {
    val startNanos = System.nanoTime()
    val resolved = blockingWithTimeout(timeoutSeconds.secondsToMillis()) { runCatching { resolver(config.hostname) } }
        ?: return CheckResult(false, elapsedMillisSince(startNanos), "DNS resolution timed out after $timeoutSeconds s")

    return resolved.fold(
        onSuccess = { addresses ->
            val ips = addresses.map { it.hostAddress }
            val matches = config.expectedIp == null || ips.contains(config.expectedIp)
            CheckResult(
                success = matches,
                responseTimeMs = elapsedMillisSince(startNanos),
                message = if (matches.not()) "Resolved IPs $ips do not contain ${config.expectedIp}" else null
            )
        },
        onFailure = { CheckResult(false, elapsedMillisSince(startNanos), it.message ?: it::class.simpleName) }
    )
}

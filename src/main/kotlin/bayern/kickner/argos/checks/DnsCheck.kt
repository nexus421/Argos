package bayern.kickner.argos.checks

import bayern.kickner.argos.config.DnsCheckConfig
import java.net.InetAddress

/**
 * Resolves the hostname specified in [config] and validates the resolved IP address if configured.
 * `expectedIp` is compared by address bytes: the JDK renders IPv6 uncompressed (`2001:db8:0:0:0:0:0:1`),
 * so a text comparison against the configured `2001:db8::1` would never match.
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

    // ofLiteral parses strictly and never queries DNS, unlike getByName
    val expected = config.expectedIp?.let { literal ->
        runCatching { InetAddress.ofLiteral(literal) }
            .getOrElse { return CheckResult(false, elapsedMillisSince(startNanos), "expectedIp '$literal' is not a valid IP address") }
    }

    val resolved = blockingWithTimeout(timeoutSeconds.secondsToMillis()) { runCatching { resolver(config.hostname) } }
        ?: return CheckResult(false, elapsedMillisSince(startNanos), "DNS resolution timed out after $timeoutSeconds s")

    return resolved.fold(
        onSuccess = { addresses ->
            val matches = expected == null || addresses.any { it.address.contentEquals(expected.address) }
            CheckResult(
                success = matches,
                responseTimeMs = elapsedMillisSince(startNanos),
                message = if (matches.not()) "Resolved IPs ${addresses.map { it.hostAddress }} do not contain ${config.expectedIp}" else null
            )
        },
        onFailure = { CheckResult(false, elapsedMillisSince(startNanos), it.message ?: it::class.simpleName) }
    )
}

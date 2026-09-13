package bayern.kickner.argos.checks

import bayern.kickner.argos.config.CheckConfig
import bayern.kickner.argos.config.DnsCheckConfig
import bayern.kickner.argos.config.HttpCheckConfig
import bayern.kickner.argos.config.PingCheckConfig
import bayern.kickner.argos.config.TcpCheckConfig

/**
 * Dispatches and executes a specific check strategy based on the concrete [CheckConfig] type.
 *
 * @param check Polymorphic check configuration.
 * @param timeoutSeconds Check execution timeout in seconds.
 * @return [CheckResult] outcome of the check.
 */
suspend fun executeCheck(check: CheckConfig, timeoutSeconds: Long): CheckResult = when (check) {
    is HttpCheckConfig -> executeHttpCheck(check, timeoutSeconds)
    is TcpCheckConfig -> executeTcpCheck(check, timeoutSeconds)
    is PingCheckConfig -> executePingCheck(check, timeoutSeconds)
    is DnsCheckConfig -> executeDnsCheck(check, timeoutSeconds)
}

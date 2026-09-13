package bayern.kickner.argos.checks

import bayern.kickner.argos.config.PingCheckConfig
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit

private const val CAP_NET_RAW_BIT = 13

/** Extra time the ping process gets beyond `-W` before it is killed (covers process start and name resolution). */
private const val PING_GRACE_MS = 1000L

/** Well-known locations of iputils ping; PATH is searched afterwards. */
private val DEFAULT_PING_CANDIDATES = listOf("/usr/bin/ping", "/bin/ping")

private val rttPattern = Regex("""time[=<]([0-9]+(?:\.[0-9]+)?) ?ms""")

/** iputils prefixes errors with its own argv[0], e.g. `ping: ` or `/usr/bin/ping: `. */
private val toolPrefixPattern = Regex("""^\S*ping: """)

/**
 * How ICMP echo requests are sent.
 */
sealed interface PingBackend {
    /** iputils-compatible `ping` executable; works without process privileges thanks to its file capability. */
    data class Binary(val path: String) : PingBackend

    /** [InetAddress.isReachable] — needs CAP_NET_RAW, otherwise the JDK silently probes TCP port 7 instead. */
    data object Jdk : PingBackend
}

/**
 * Backend chosen once per process: the `ping` binary on Linux when one is executable, the JDK otherwise.
 */
val defaultPingBackend: PingBackend by lazy { detectPingBackend() }

/**
 * Picks the ping backend. The binary is used on Linux only — its flags and output are iputils-specific.
 *
 * @param osName Operating system name as in `os.name`.
 * @param candidates Absolute paths tried in order before searching [path].
 * @param path `PATH`-style list of directories to search for `ping`.
 */
fun detectPingBackend(
    osName: String = System.getProperty("os.name") ?: "",
    candidates: List<String> = DEFAULT_PING_CANDIDATES,
    path: String? = System.getenv("PATH")
): PingBackend {
    val linux = osName.contains("linux", ignoreCase = true)
    if (linux.not()) return PingBackend.Jdk
    val fromPath = path.orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }.map { "$it/ping" }
    val binary = (candidates + fromPath).firstOrNull { File(it).canExecute() }
    return binary?.let { PingBackend.Binary(it) } ?: PingBackend.Jdk
}

/**
 * Pings [config] with [backend].
 *
 * @param config Ping check target host.
 * @param timeoutSeconds Ping timeout in seconds, covering name resolution as well.
 * @return [CheckResult] indicating whether the host answered, with the ICMP round-trip time when available.
 */
suspend fun executePingCheck(
    config: PingCheckConfig,
    timeoutSeconds: Long,
    backend: PingBackend = defaultPingBackend
): CheckResult = when (backend) {
    is PingBackend.Binary -> pingWithBinary(backend.path, config.host, timeoutSeconds)
    PingBackend.Jdk -> pingWithJdk(config.host, timeoutSeconds)
}

/**
 * Runs `ping -c 1 -W <timeout> -n -- <host>`. UP/DOWN comes from the exit code (0 reply, 1 no reply, 2 error);
 * the round-trip time is parsed from the `time=` field. A process that outlives the timeout is killed.
 */
private suspend fun pingWithBinary(binary: String, host: String, timeoutSeconds: Long): CheckResult {
    val timeoutMillis = timeoutSeconds.secondsToMillis()
    val startNanos = System.nanoTime()
    val outcome = blockingWithTimeout(timeoutMillis + 2 * PING_GRACE_MS) {
        runCatching { runPingProcess(binary, host, timeoutSeconds, timeoutMillis + PING_GRACE_MS) }
    } ?: return CheckResult(false, elapsedMillisSince(startNanos), "Ping to $host timed out after $timeoutSeconds s")

    return outcome.fold(
        onSuccess = { (exitCode, output) ->
            if (exitCode == null) CheckResult(false, elapsedMillisSince(startNanos), "Ping to $host timed out after $timeoutSeconds s (process killed)")
            else parsePingOutput(exitCode, output, timeoutSeconds, elapsedMillisSince(startNanos))
        },
        onFailure = { CheckResult(false, elapsedMillisSince(startNanos), "Could not run $binary: ${it.message}") }
    )
}

/**
 * @return exit code and combined output, or a null exit code when the process had to be killed.
 */
private fun runPingProcess(binary: String, host: String, timeoutSeconds: Long, killAfterMillis: Long): Pair<Int?, String> {
    val process = ProcessBuilder(binary, "-c", "1", "-W", timeoutSeconds.toString(), "-n", "--", host)
        .redirectErrorStream(true)
        .apply { environment()["LC_ALL"] = "C" }
        .start()

    val finished = process.waitFor(killAfterMillis, TimeUnit.MILLISECONDS)
    if (finished.not()) {
        process.destroyForcibly()
        process.waitFor(PING_GRACE_MS, TimeUnit.MILLISECONDS)
        return null to ""
    }
    // `-c 1` output is a few hundred bytes, far below the pipe buffer, so reading after exit cannot block
    val output = process.inputStream.readAllBytes().decodeToString()
    return process.exitValue() to output
}

/**
 * Maps iputils exit code and output to a [CheckResult].
 *
 * @param fallbackMillis Duration to report when the output carries no `time=` field.
 */
fun parsePingOutput(exitCode: Int, output: String, timeoutSeconds: Long, fallbackMillis: Double): CheckResult {
    val rtt = rttPattern.find(output)?.groupValues?.get(1)?.toDoubleOrNull()
    return when (exitCode) {
        0 -> CheckResult(true, rtt ?: fallbackMillis, null)
        1 -> {
            val icmpError = output.lineSequence().firstOrNull { it.startsWith("From ") }?.trim()
            CheckResult(false, fallbackMillis, icmpError ?: "No ICMP reply within $timeoutSeconds s")
        }
        else -> {
            val detail = output.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: "exit code $exitCode"
            CheckResult(false, fallbackMillis, detail.replace(toolPrefixPattern, ""))
        }
    }
}

private suspend fun pingWithJdk(host: String, timeoutSeconds: Long): CheckResult {
    val startNanos = System.nanoTime()
    val timeoutMillis = timeoutSeconds.secondsToMillis()
    val attempt = blockingWithTimeout(timeoutMillis) {
        runCatching { InetAddress.getByName(host).isReachable(timeoutMillis.toInt()) }
    } ?: return CheckResult(false, elapsedMillisSince(startNanos), "Ping to $host timed out after $timeoutSeconds s")

    return attempt.fold(
        onSuccess = { reachable ->
            CheckResult(
                success = reachable,
                responseTimeMs = elapsedMillisSince(startNanos),
                message = if (reachable.not()) "Host unreachable (ICMP or TCP echo fallback failed)" else null
            )
        },
        onFailure = { CheckResult(false, elapsedMillisSince(startNanos), it.message ?: it::class.simpleName) }
    )
}

/**
 * Verifies once at start that [backend] can actually send ICMP.
 *
 * @return null when ICMP works, otherwise a human-readable reason.
 */
fun probeIcmp(backend: PingBackend): String? = when (backend) {
    is PingBackend.Binary -> runCatching { runPingProcess(backend.path, "127.0.0.1", 1, 3000) }.fold(
        onSuccess = { (exitCode, output) ->
            if (exitCode == 0) null
            else "${backend.path} cannot ping loopback (exit $exitCode): ${output.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: "no output"}"
        },
        onFailure = { "${backend.path} could not be started: ${it.message}" }
    )
    PingBackend.Jdk -> if (icmpAvailable()) null else "no ping binary found and the process lacks CAP_NET_RAW (needs root or AmbientCapabilities=CAP_NET_RAW)"
}

/**
 * Whether this process may open raw ICMP sockets. Probing `isReachable` is useless for this: the JDK's
 * TCP-port-7 fallback reports any host that answers with RST as reachable, loopback included. On Linux the
 * effective capability set in `/proc/self/status` is authoritative (root always has CAP_NET_RAW).
 */
fun icmpAvailable(): Boolean =
    runCatching { File("/proc/self/status").readText() }.map { hasNetRawCapability(it) }.getOrDefault(true)

/**
 * Parses the `CapEff` line of a `/proc/<pid>/status` document. Missing line (non-Linux) counts as capable
 * so no false warning is logged where the check cannot be made.
 */
fun hasNetRawCapability(procStatus: String): Boolean {
    val capEff = procStatus.lineSequence()
        .firstOrNull { it.startsWith("CapEff:") }
        ?.substringAfter(':')?.trim()
        ?: return true
    val bits = capEff.toBigIntegerOrNull(16) ?: return true
    return bits.testBit(CAP_NET_RAW_BIT)
}

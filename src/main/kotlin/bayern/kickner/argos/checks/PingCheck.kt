package bayern.kickner.argos.checks

import bayern.kickner.argos.config.PingCheckConfig
import java.io.File
import java.util.concurrent.TimeUnit

/** Extra time the ping process gets beyond `-W` before it is killed (covers process start and name resolution). */
private const val PING_GRACE_MS = 1000L

/** Well-known locations of iputils ping; PATH is searched afterwards. */
private val DEFAULT_PING_CANDIDATES = listOf("/usr/bin/ping", "/bin/ping")

private val rttPattern = Regex("""time[=<]([0-9]+(?:\.[0-9]+)?) ?ms""")

/** iputils prefixes errors with its own argv[0], e.g. `ping: ` or `/usr/bin/ping: `. */
private val toolPrefixPattern = Regex("""^\S*ping: """)

/**
 * The iputils `ping` executable found once per process, or null. It works without process privileges thanks
 * to its file capability; flags and output are iputils-specific, so Argos ping monitors are Linux-only.
 */
val defaultPingBinary: String? by lazy { detectPingBinary() }

/**
 * Finds an executable `ping`.
 *
 * @param candidates Absolute paths tried in order before searching [path].
 * @param path `PATH`-style list of directories to search for `ping`.
 */
fun detectPingBinary(
    candidates: List<String> = DEFAULT_PING_CANDIDATES,
    path: String? = System.getenv("PATH")
): String? {
    val fromPath = path.orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }.map { "$it/ping" }
    return (candidates + fromPath).firstOrNull { File(it).canExecute() }
}

/**
 * Pings [config] with [binary].
 *
 * @param config Ping check target host.
 * @param timeoutSeconds Ping timeout in seconds, covering name resolution as well.
 * @param binary Path of the `ping` executable; null fails the check.
 * @return [CheckResult] indicating whether the host answered, with the ICMP round-trip time when available.
 */
suspend fun executePingCheck(
    config: PingCheckConfig,
    timeoutSeconds: Long,
    binary: String? = defaultPingBinary
): CheckResult {
    binary ?: return CheckResult(false, 0.0, "No ping binary found (iputils ping required)")
    return pingWithBinary(binary, config.host, timeoutSeconds)
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
    val output = process.inputStream.use { it.readAllBytes() }.decodeToString()
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

/**
 * Verifies once at start that [binary] can actually send ICMP (one echo request to loopback).
 *
 * @return null when ICMP works, otherwise a human-readable reason.
 */
fun probeIcmp(binary: String): String? = runCatching { runPingProcess(binary, "127.0.0.1", 1, 3000) }.fold(
    onSuccess = { (exitCode, output) ->
        if (exitCode == 0) null
        else "$binary cannot ping loopback (exit $exitCode): ${output.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: "no output"}"
    },
    onFailure = { "$binary could not be started: ${it.message}" }
)

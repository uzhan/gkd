package li.gkd.app.priv

import android.util.Log
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Fixed sensorservice commands for the privileged UserService process.
 *
 * Logging uses [Log] only — this code runs without Application ([LogUtils] is unsafe here).
 */
object SensorServiceCommands {
    private const val TAG = "SensorServiceCommands"
    private const val TIMEOUT_MS = 3_000L
    private const val DUMP_TIMEOUT_MS = 5_000L
    private const val AUTO_RESTORE_MIN_MS = 1_000L
    private const val AUTO_RESTORE_MAX_MS = 30_000L
    private val PKG_PATTERN = Regex("^[A-Za-z0-9._]+$")
    private const val PKG_MAX_LENGTH = 255
    private const val CALIBRATE_INTERVAL_MS = 150L
    private const val CALIBRATE_MIN_SEC = 1
    private const val CALIBRATE_MAX_SEC = 60

    private val autoEnableLock = Any()
    /** Session-leader PID of the detached auto-enable child (setsid); at most one. */
    private var autoEnablePid: Int? = null

    fun restrict(pkg: String, autoRestoreMs: Long): Boolean {
        if (!isValidPackageName(pkg)) {
            Log.w(TAG, "restrict: invalid pkg=$pkg")
            return false
        }
        val clamped = autoRestoreMs.coerceIn(AUTO_RESTORE_MIN_MS, AUTO_RESTORE_MAX_MS)
        // Always clear any previous auto-enable handle before a new restrict window.
        cancelAutoEnable()
        val ok = runCommand(TIMEOUT_MS, "dumpsys", "sensorservice", "restrict", pkg)
        if (ok) {
            scheduleAutoEnable(clamped)
        }
        return ok
    }

    fun enable(): Boolean {
        cancelAutoEnable()
        return runCommand(TIMEOUT_MS, "dumpsys", "sensorservice", "enable")
    }

    /**
     * Poll dumpsys without freezing; report when [pkg] gains/loses active sensor connections.
     *
     * Matching: listener exact/`pkg.*` prefix, or connection `uid` of [pkg] (ad SDKs register
     * under their own class names while sharing the host app uid).
     */
    fun calibrate(pkg: String, durationSec: Int): String {
        if (!isValidPackageName(pkg)) {
            return "error: invalid pkg=$pkg"
        }
        val seconds = durationSec.coerceIn(CALIBRATE_MIN_SEC, CALIBRATE_MAX_SEC)
        val durationMs = seconds * 1_000L
        val pkgUid = resolvePackageUid(pkg)
        val startNs = System.nanoTime()
        val activeStart = LinkedHashMap<String, Long>()
        val intervals = ArrayList<CalInterval>()
        var previous = emptySet<String>()
        var samples = 0
        var dumpFailures = 0

        while (elapsedMs(startNs) < durationMs) {
            val dump = captureDump()
            samples++
            if (dump == null) {
                dumpFailures++
            } else {
                val current = parseActiveSensorsForPkg(dump, pkg, pkgUid)
                val offset = elapsedMs(startNs)
                for (key in current - previous) {
                    activeStart[key] = offset
                }
                for (key in previous - current) {
                    val from = activeStart.remove(key) ?: offset
                    intervals.add(CalInterval(key, from, offset))
                }
                previous = current
            }
            val remaining = durationMs - elapsedMs(startNs)
            if (remaining <= 0L) break
            Thread.sleep(minOf(CALIBRATE_INTERVAL_MS, remaining))
        }
        val endOffset = elapsedMs(startNs)
        for ((key, from) in activeStart) {
            intervals.add(CalInterval(key, from, endOffset))
        }

        return buildString {
            appendLine(
                "sensor calibrate pkg=$pkg uid=${pkgUid ?: "?"} durationSec=$seconds " +
                    "intervalMs=$CALIBRATE_INTERVAL_MS",
            )
            appendLine("samples=$samples dumpFailures=$dumpFailures elapsedMs=$endOffset")
            if (intervals.isEmpty()) {
                appendLine(
                    "events: (none — no active connection matched pkg prefix or uid during window)",
                )
            } else {
                appendLine("events:")
                intervals.sortedWith(compareBy({ it.startMs }, { it.key })).forEach { ev ->
                    appendLine(
                        "  ${ev.key} startMs=${ev.startMs} endMs=${ev.endMs} holdMs=${ev.endMs - ev.startMs}",
                    )
                }
            }
            appendLine("hint: startDelayMs ≈ first startMs; durationMs ≈ max hold of ad-related sensors")
        }
    }

    private data class CalInterval(val key: String, val startMs: Long, val endMs: Long)

    private fun elapsedMs(startNs: Long): Long =
        (System.nanoTime() - startNs) / 1_000_000L

    private fun scheduleAutoEnable(delayMs: Long) {
        synchronized(autoEnableLock) {
            cancelAutoEnableLocked()
            val delaySec = delayMs / 1000.0
            // Detach from UserService / Shizuku process group so enable still runs after
            // force-stop of GKD or death of the privilege binder host.
            val starter =
                ProcessBuilder(
                        "sh",
                        "-c",
                        "setsid nohup sh -c 'sleep $delaySec; dumpsys sensorservice enable'" +
                            " </dev/null >/dev/null 2>&1 & echo \$!",
                    )
                    .redirectErrorStream(true)
                    .start()
            val pidLine =
                try {
                    starter.inputStream.bufferedReader().readLine()
                } catch (_: IOException) {
                    null
                }
            starter.waitFor(2, TimeUnit.SECONDS)
            if (starter.isAlive) starter.destroyForcibly()
            val pid = pidLine?.trim()?.toIntOrNull()
            autoEnablePid = pid
            Log.i(
                TAG,
                "scheduled auto-enable pid=$pid delayMs=$delayMs wall=${System.currentTimeMillis()}",
            )
        }
    }

    private fun cancelAutoEnable() {
        synchronized(autoEnableLock) { cancelAutoEnableLocked() }
    }

    private fun cancelAutoEnableLocked() {
        val pid = autoEnablePid ?: return
        autoEnablePid = null
        Log.i(TAG, "cancel auto-enable pid=$pid wall=${System.currentTimeMillis()}")
        try {
            // Negative PID: kill the setsid session/process group when possible.
            ProcessBuilder("kill", "-9", "-$pid")
                .redirectErrorStream(true)
                .start()
                .waitFor(1, TimeUnit.SECONDS)
            ProcessBuilder("kill", "-9", "$pid")
                .redirectErrorStream(true)
                .start()
                .waitFor(1, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "cancel auto-enable failed pid=$pid: ${e.message}")
        }
    }

    private fun isValidPackageName(pkg: String): Boolean =
        pkg.length in 1..PKG_MAX_LENGTH && PKG_PATTERN.matches(pkg)

    private fun captureDump(): String? {
        var process: Process? = null
        return try {
            process =
                ProcessBuilder("dumpsys", "sensorservice")
                    .redirectErrorStream(true)
                    .start()
            val output = StringBuilder()
            val reader =
                Thread(
                        {
                            try {
                                process.inputStream.bufferedReader().use { br ->
                                    var line: String?
                                    while (br.readLine().also { line = it } != null) {
                                        output.appendLine(line)
                                    }
                                }
                            } catch (_: IOException) {
                            }
                        },
                        "SensorDump-drain",
                    )
                    .apply {
                        isDaemon = true
                        start()
                    }
            val finished = process.waitFor(DUMP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                reader.join(500)
                Log.w(TAG, "dump timed out")
                return null
            }
            reader.join(500)
            if (process.exitValue() != 0) {
                Log.w(TAG, "dump exit=${process.exitValue()}")
            }
            output.toString()
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: ThreadDeath) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "dump failed", e)
            null
        } finally {
            process?.let {
                try {
                    it.inputStream.close()
                } catch (_: IOException) {
                }
                if (it.isAlive) it.destroyForcibly()
            }
        }
    }

    /**
     * Parse active connection blocks. Listener lines often use Activity/class names that start
     * with the app id (e.g. imoblife.androidsensorbox.acce.SensorAcceActivity). Ad SDKs may
     * instead use their own class as the listener while keeping the host app [pkgUid].
     */
    internal fun parseActiveSensorsForPkg(
        dump: String,
        pkg: String,
        pkgUid: Int? = null,
    ): Set<String> {
        val result = LinkedHashSet<String>()
        var matching = false
        var listener = ""
        for (raw in dump.lineSequence()) {
            val line = raw.trimEnd()
            when {
                line.startsWith("Connection Number:") -> {
                    matching = false
                    listener = ""
                }
                line.trimStart().startsWith("Operating Mode:") -> Unit
                !matching &&
                    line.contains('|') &&
                    line.contains("uid") &&
                    line.contains("pid") -> {
                    listener = line.trim().substringBefore('|').trim()
                    val uid =
                        Regex("""\buid\s+(\d+)""")
                            .find(line)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                    matching =
                        listenerMatchesPkg(listener, pkg) ||
                            (pkgUid != null && uid == pkgUid)
                }
                matching && line.contains("| status:") -> {
                    val trimmed = line.trim()
                    val name = trimmed.substringBefore(" 0x").trim()
                    val handle =
                        Regex("""0x[0-9a-fA-F]+""").find(trimmed)?.value.orEmpty()
                    val status =
                        Regex("""status:\s*(\w+)""")
                            .find(trimmed)
                            ?.groupValues
                            ?.getOrNull(1)
                            .orEmpty()
                    if (status == "active" && name.isNotEmpty()) {
                        result.add("$name|$handle listener=$listener")
                    }
                }
                line.startsWith("Previous Registrations:") ||
                    line.matches(Regex("""\d+ direct connections""")) -> {
                    matching = false
                    listener = ""
                }
            }
        }
        return result
    }

    /** Exact package, or listener/class under that package (`pkg` or `pkg.*`). */
    private fun listenerMatchesPkg(listener: String, pkg: String): Boolean =
        listener == pkg || listener.startsWith("$pkg.")

    /** `pm list packages -U <pkg>` → `package:<pkg> uid:<n>`. */
    private fun resolvePackageUid(pkg: String): Int? {
        var process: Process? = null
        return try {
            process =
                ProcessBuilder("pm", "list", "packages", "-U", pkg)
                    .redirectErrorStream(true)
                    .start()
            val text =
                process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                Log.w(TAG, "resolvePackageUid timed out pkg=$pkg")
                return null
            }
            Regex("""uid:(\d+)""")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                .also { uid ->
                    if (uid == null) {
                        Log.w(TAG, "resolvePackageUid miss pkg=$pkg out=${text.trim()}")
                    }
                }
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: ThreadDeath) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "resolvePackageUid failed pkg=$pkg", e)
            null
        } finally {
            process?.let {
                try {
                    it.inputStream.close()
                } catch (_: IOException) {
                }
                if (it.isAlive) it.destroyForcibly()
            }
        }
    }
    private fun runCommand(timeoutMs: Long, vararg args: String): Boolean {
        var process: Process? = null
        return try {
            process =
                ProcessBuilder(*args)
                    .redirectErrorStream(true)
                    .start()
            val outputReader =
                Thread(
                        {
                            try {
                                process.inputStream.bufferedReader().use { it.readText() }
                            } catch (_: IOException) {
                            }
                        },
                        "SensorServiceCommands-drain",
                    )
                    .apply {
                        isDaemon = true
                        start()
                    }
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                Log.w(TAG, "timed out: ${args.joinToString(" ")}")
                process.destroyForcibly()
                outputReader.join(500)
                return false
            }
            outputReader.join(500)
            val code = process.exitValue()
            if (code != 0) {
                Log.w(TAG, "exit=$code cmd=${args.joinToString(" ")}")
            }
            code == 0
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: ThreadDeath) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "failed: ${args.joinToString(" ")}", e)
            false
        } finally {
            process?.let {
                try {
                    it.inputStream.close()
                } catch (_: IOException) {
                }
                if (it.isAlive) it.destroyForcibly()
            }
        }
    }
}

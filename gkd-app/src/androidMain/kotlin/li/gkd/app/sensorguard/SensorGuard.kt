package li.gkd.app.sensorguard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import li.gkd.app.appScope
import li.gkd.app.logging.LogUtils
import li.gkd.app.matching.input.foregroundRulesFlow

/**
 * M2 runtime: watch foreground app id and apply in-memory [AppRule]s (ON_FOREGROUND only).
 * No persistence / settings UI yet.
 */
object SensorGuard {
    private val mutex = Mutex()
    private var started = false
    private var watchJob: Job? = null
    private var sessionJob: Job? = null
    private var frozen = false
    private var activeTargetPkg: String? = null

    val enabled: StateFlow<Boolean>
        field = MutableStateFlow(true)

    val rule: StateFlow<AppRule>
        field = MutableStateFlow(SensorGuardDefaults.m2DemoRule)

    val lastEvent: StateFlow<String>
        field = MutableStateFlow("idle")

    fun start() {
        if (started) return
        started = true
        watchJob =
            appScope.launch(Dispatchers.Default) {
                foregroundRulesFlow
                    .map { it.topActivity.appId }
                    .distinctUntilChanged()
                    .collect { appId -> onForegroundApp(appId) }
            }
        LogUtils.i("SensorGuard started target=${rule.value.pkg}")
    }

    fun setEnabled(value: Boolean) {
        enabled.value = value
        if (!value) {
            appScope.launch(Dispatchers.Default) { cancelSessionAndRestore("disabled") }
        }
    }

    /** Debug override for `/debug/sensor/target`. */
    fun setDebugTarget(pkg: String, startDelayMs: Long, durationMs: Long, maxHoldMs: Long = 30_000L) {
        rule.value =
            AppRule(
                pkg = pkg,
                mode = SensorTriggerMode.ON_FOREGROUND,
                startDelayMs = startDelayMs.coerceAtLeast(0L),
                durationMs = durationMs.coerceAtLeast(0L),
                maxHoldMs = maxHoldMs.coerceAtLeast(1_000L),
            )
        enabled.value = true
        lastEvent.value =
            "target pkg=$pkg delay=${rule.value.startDelayMs} duration=${rule.value.durationMs} maxHold=${rule.value.maxHoldMs}"
        LogUtils.i("SensorGuard setDebugTarget ${lastEvent.value}")
    }

    private suspend fun onForegroundApp(appId: String) {
        val current = rule.value
        if (!enabled.value || current.mode != SensorTriggerMode.ON_FOREGROUND) {
            if (frozen) cancelSessionAndRestore("mode_or_disabled")
            return
        }
        if (appId == current.pkg) {
            if (activeTargetPkg == appId && sessionJob?.isActive == true) return
            beginForegroundSession(current)
        } else if (activeTargetPkg != null) {
            cancelSessionAndRestore("left_target appId=$appId")
        }
    }

    private suspend fun beginForegroundSession(current: AppRule) =
        mutex.withLock {
            sessionJob?.cancel()
            activeTargetPkg = current.pkg
            val triggerAt = System.currentTimeMillis()
            val delayMs = current.startDelayMs.coerceAtLeast(0L)
            val maxHold = current.maxHoldMs.coerceAtLeast(1_000L)
            val intendedEnd = delayMs + current.durationMs.coerceAtLeast(0L)
            val restoreAt = minOf(intendedEnd, maxHold)
            if (restoreAt <= delayMs) {
                lastEvent.value =
                    "skip freeze: startDelayMs+duration exceeds window (delay=$delayMs maxHold=$maxHold)"
                LogUtils.w("SensorGuard ${lastEvent.value}")
                return@withLock
            }
            val freezeMs = restoreAt - delayMs
            lastEvent.value =
                "armed pkg=${current.pkg} delayMs=$delayMs freezeMs=$freezeMs maxHold=$maxHold"
            sessionJob =
                appScope.launch(Dispatchers.Default) {
                    try {
                        if (delayMs > 0L) delay(delayMs)
                        if (!enabled.value || rule.value.pkg != current.pkg) return@launch
                        val remainingToCap = maxHold - (System.currentTimeMillis() - triggerAt)
                        val actualFreeze = minOf(freezeMs, remainingToCap).coerceAtLeast(0L)
                        if (actualFreeze < 1_000L) {
                            lastEvent.value = "skip freeze: remaining to maxHold < 1000ms"
                            return@launch
                        }
                        val restrictStart = System.currentTimeMillis()
                        val ok =
                            SensorShell.freeze(
                                SensorGuardDefaults.DUMMY_RESTRICT_PKG,
                                autoRestoreMs = actualFreeze,
                            )
                        val latencyFgToRestrictMs = System.currentTimeMillis() - triggerAt
                        val restrictCallMs = System.currentTimeMillis() - restrictStart
                        frozen = ok
                        lastEvent.value =
                            if (ok)
                                "frozen ${actualFreeze}ms pkg=${current.pkg} " +
                                    "latencyFgToRestrictMs=$latencyFgToRestrictMs " +
                                    "restrictCallMs=$restrictCallMs"
                            else "freeze_failed pkg=${current.pkg} latencyFgToRestrictMs=$latencyFgToRestrictMs"
                        LogUtils.i("SensorGuard ${lastEvent.value}")
                        // Server auto-enables; still mark local state after duration.
                        delay(actualFreeze)
                        frozen = false
                        if (lastEvent.value.startsWith("frozen")) {
                            lastEvent.value = "auto_restored pkg=${current.pkg}"
                        }
                    } finally {
                        // Leaving is handled by cancelSessionAndRestore.
                    }
                }
        }

    private suspend fun cancelSessionAndRestore(reason: String) =
        mutex.withLock {
            sessionJob?.cancel()
            sessionJob = null
            activeTargetPkg = null
            if (frozen) {
                val ok = SensorShell.restore()
                frozen = false
                lastEvent.value = "restore reason=$reason ok=$ok"
                LogUtils.i("SensorGuard ${lastEvent.value}")
            } else {
                lastEvent.value = "cancel reason=$reason (not frozen)"
            }
        }
}

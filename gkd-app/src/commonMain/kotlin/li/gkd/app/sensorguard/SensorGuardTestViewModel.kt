package li.gkd.app.sensorguard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import li.gkd.app.app.applicationScope
import li.gkd.app.logging.LogUtils
import li.gkd.app.resources.*
import li.gkd.app.ui.state.BaseViewModel
import li.gkd.app.util.ToastUtils
import org.jetbrains.compose.resources.getString

class SensorGuardTestViewModel : BaseViewModel() {
    companion object {
        val DUMMY_PKG = SensorGuardDefaults.DUMMY_RESTRICT_PKG
        const val FREEZE_SECONDS = 5
    }

    val remainingSeconds: StateFlow<Int?>
        field = MutableStateFlow(null)

    val freezing: StateFlow<Boolean>
        field = MutableStateFlow(false)

    // UI countdown only. Actual auto-enable is scheduled inside UserService so it
    // still runs when ColorOS freezes this app process after switching to Sensor Box.
    private var countdownJob: Job? = null

    fun startFreezeTest() {
        if (!SensorShell.isAvailable()) {
            scope.launch {
                ToastUtils.show(getString(Res.string.sensor_guard_test_need_privilege))
            }
            return
        }
        countdownJob?.cancel()
        countdownJob =
            applicationScope().launch(Dispatchers.Default) {
                freezing.value = true
                remainingSeconds.value = FREEZE_SECONDS
                val ok = SensorShell.freeze(DUMMY_PKG, autoRestoreMs = FREEZE_SECONDS * 1_000L)
                if (!ok) {
                    freezing.value = false
                    remainingSeconds.value = null
                    withContext(Dispatchers.Main.immediate) {
                        ToastUtils.show(getString(Res.string.sensor_guard_test_freeze_failed))
                    }
                    return@launch
                }
                withContext(Dispatchers.Main.immediate) {
                    ToastUtils.show(getString(Res.string.sensor_guard_test_frozen))
                }
                for (left in FREEZE_SECONDS downTo 1) {
                    remainingSeconds.value = left
                    delay(1_000)
                }
                // Clear UI; server-side auto-enable should already have run (or be imminent).
                // Best-effort client restore in case we returned to GKD while still frozen.
                freezing.value = false
                remainingSeconds.value = null
                val restored = SensorShell.restore()
                LogUtils.i("SensorGuardTest client countdown done restoreOk=$restored")
                withContext(Dispatchers.Main.immediate) {
                    ToastUtils.show(getString(Res.string.sensor_guard_test_restored))
                }
            }
    }

    fun restoreNow() {
        countdownJob?.cancel()
        countdownJob = null
        applicationScope().launch(Dispatchers.Default) {
            restoreInternal(showToast = freezing.value || remainingSeconds.value != null, reason = "manual")
        }
    }

    /** Navigate away / page disposed — restore immediately (cancels server auto-enable). */
    fun restoreOnLeave() {
        countdownJob?.cancel()
        countdownJob = null
        applicationScope().launch(Dispatchers.Default) {
            restoreInternal(showToast = false, reason = "leave")
        }
    }

    private suspend fun restoreInternal(showToast: Boolean, reason: String) {
        val wasFreezing = freezing.value || remainingSeconds.value != null
        freezing.value = false
        remainingSeconds.value = null
        val ok = SensorShell.restore()
        LogUtils.i("SensorGuardTest restore reason=$reason ok=$ok")
        if (showToast && wasFreezing) {
            withContext(Dispatchers.Main.immediate) {
                ToastUtils.show(
                    getString(
                        if (ok) Res.string.sensor_guard_test_restored
                        else Res.string.sensor_guard_test_restore_failed,
                    ),
                )
            }
        }
    }

    override fun onCleared() {
        countdownJob?.cancel()
        countdownJob = null
        applicationScope().launch(Dispatchers.IO) {
            try {
                SensorShell.restore()
            } catch (e: Exception) {
                LogUtils.e("SensorGuardTest onCleared restore failed", e)
            }
        }
        super.onCleared()
    }
}

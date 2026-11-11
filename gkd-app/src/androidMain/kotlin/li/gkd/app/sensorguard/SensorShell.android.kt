package li.gkd.app.sensorguard

import android.os.DeadObjectException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import li.gkd.app.logging.LogUtils
import li.gkd.app.priv.privilegeContextFlow

actual object SensorShell {
    actual fun isAvailable(): Boolean = privilegeContextFlow.value != null

    actual suspend fun freeze(pkg: String, autoRestoreMs: Long): Boolean =
        withContext(Dispatchers.IO) {
            val context = privilegeContextFlow.value
            if (context == null) {
                LogUtils.w("SensorShell.freeze: privilege service not connected")
                return@withContext false
            }
            try {
                val ok = context.sensorRestrict(pkg, autoRestoreMs)
                if (!ok) {
                    LogUtils.w(
                        "SensorShell.freeze: remote returned false pkg=$pkg autoRestoreMs=$autoRestoreMs",
                    )
                }
                ok
            } catch (e: DeadObjectException) {
                LogUtils.e("SensorShell.freeze: dead binder", e)
                false
            } catch (e: Exception) {
                LogUtils.e("SensorShell.freeze failed", e)
                false
            }
        }

    actual suspend fun restore(): Boolean =
        withContext(Dispatchers.IO) {
            val context = privilegeContextFlow.value
            if (context == null) {
                LogUtils.w("SensorShell.restore: privilege service not connected")
                return@withContext false
            }
            try {
                val ok = context.sensorEnable()
                if (!ok) LogUtils.w("SensorShell.restore: remote returned false")
                ok
            } catch (e: DeadObjectException) {
                LogUtils.e("SensorShell.restore: dead binder", e)
                false
            } catch (e: Exception) {
                LogUtils.e("SensorShell.restore failed", e)
                false
            }
        }

    actual suspend fun calibrate(pkg: String, durationSec: Int): String =
        withContext(Dispatchers.IO) {
            val context = privilegeContextFlow.value
            if (context == null) {
                return@withContext "error: privilege_not_connected"
            }
            try {
                context.sensorCalibrate(pkg, durationSec)
            } catch (e: DeadObjectException) {
                LogUtils.e("SensorShell.calibrate: dead binder", e)
                "error: dead_binder"
            } catch (e: Exception) {
                LogUtils.e("SensorShell.calibrate failed", e)
                "error: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
}

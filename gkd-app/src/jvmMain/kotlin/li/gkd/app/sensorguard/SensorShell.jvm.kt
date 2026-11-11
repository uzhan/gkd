package li.gkd.app.sensorguard

import li.gkd.app.logging.LogUtils

actual object SensorShell {
    actual fun isAvailable(): Boolean = false

    actual suspend fun freeze(pkg: String, autoRestoreMs: Long): Boolean {
        LogUtils.w("SensorShell.freeze unsupported on Desktop")
        return false
    }

    actual suspend fun restore(): Boolean = true

    actual suspend fun calibrate(pkg: String, durationSec: Int): String =
        "error: unsupported_on_desktop"
}

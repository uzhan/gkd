package li.gkd.app.sensorguard

/** Client wrapper around privileged sensorservice APIs. Never throws to callers. */
expect object SensorShell {
    fun isAvailable(): Boolean

    /**
     * Restrict sensors to [pkg] only, auto-restore after [autoRestoreMs]
     * (server clamps to 1000..30000).
     */
    suspend fun freeze(pkg: String, autoRestoreMs: Long = 5_000L): Boolean

    /** Re-enable sensors for all packages. Idempotent. */
    suspend fun restore(): Boolean

    /**
     * M2.5 calibrate: sample dumpsys without freezing. Returns a readable report, or an error
     * string when unavailable.
     */
    suspend fun calibrate(pkg: String, durationSec: Int = 20): String
}

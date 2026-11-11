package li.gkd.app.sensorguard

/**
 * Per-app freeze rule. M2 keeps instances in memory / debug overrides only —
 * no persistence or settings UI yet (M4).
 *
 * [mode] B ([ON_ACTIVITY]) / C ([ON_FEATURE]) are reserved; M2 only runs [ON_FOREGROUND].
 */
enum class SensorTriggerMode {
    /** Freeze after the target app becomes foreground. */
    ON_FOREGROUND,
    /** Reserved: freeze when a configured splash/ad Activity appears. */
    ON_ACTIVITY,
    /** Reserved: freeze when a UI feature matches (selector engine — not before confirmation). */
    ON_FEATURE,
}

data class AppRule(
    val pkg: String,
    val mode: SensorTriggerMode = SensorTriggerMode.ON_FOREGROUND,
    /** Wait after trigger before calling restrict. */
    val startDelayMs: Long = 0L,
    /** Intended freeze length after restrict. */
    val durationMs: Long = 5_000L,
    /** Hard cap on wall time from trigger (enter foreground) until forced restore. */
    val maxHoldMs: Long = 30_000L,
    /** Reserved for [SensorTriggerMode.ON_ACTIVITY]. */
    val adActivities: List<String> = emptyList(),
)

object SensorGuardDefaults {
    const val DUMMY_RESTRICT_PKG = "com.sensorguard.none"
    /** M2 hardcoded acceptance target (Sensor Box). Override via debug `/debug/sensor/target`. */
    const val M2_TARGET_PKG = "imoblife.androidsensorbox"

    val m2DemoRule =
        AppRule(
            pkg = M2_TARGET_PKG,
            mode = SensorTriggerMode.ON_FOREGROUND,
            startDelayMs = 0L,
            durationMs = 5_000L,
            maxHoldMs = 30_000L,
        )
}

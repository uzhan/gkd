package li.gkd.app.development

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import li.gkd.app.development.matching.benchmarkAndroidMatching
import li.gkd.app.matching.diagnostics.matchForegroundSnapshot
import li.gkd.app.matching.input.foregroundRulesFlow
import li.gkd.app.sensorguard.SensorGuard
import li.gkd.app.sensorguard.SensorGuardDefaults
import li.gkd.app.sensorguard.SensorShell
import li.gkd.app.service.A11yService
import li.gkd.app.settings.SettingsRepository

/** Cached engine observations, not a new Binder query or a claim of an atomic OS snapshot. */
fun Route.androidDebugRoutes() {
    post("/debug/android/benchmark") {
        try {
            call.respond(benchmarkAndroidMatching(call.receive()))
        } catch (error: TimeoutCancellationException) {
            call.respond(
                HttpStatusCode.RequestTimeout,
                mapOf(
                    "error" to
                        "Benchmark deadline exceeded; in-flight Binder cleanup has completed",
                ),
            )
        } catch (error: IllegalArgumentException) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to (error.message ?: "Invalid benchmark request")),
            )
        } catch (error: IllegalStateException) {
            if (error is CancellationException) throw error
            call.respond(
                HttpStatusCode.Conflict,
                mapOf("error" to (error.message ?: "Benchmark unavailable")),
            )
        }
    }
    get("/debug/android/foreground") {
        call.respond(requireNotNull(matchForegroundSnapshot()))
    }
    get("/debug/android/preferred-a11y") {
        call.respond(mapOf("component" to A11yService.preferredComponent.flattenToString()))
    }
    get("/debug/sensor/status") {
        val rule = SensorGuard.rule.value
        val snap = foregroundRulesFlow.value
        val settings = SettingsRepository.settings.value
        call.respond(
            mapOf(
                "privilegeConnected" to SensorShell.isAvailable().toString(),
                "enabled" to SensorGuard.enabled.value.toString(),
                "targetPkg" to rule.pkg,
                "mode" to rule.mode.name,
                "startDelayMs" to rule.startDelayMs.toString(),
                "durationMs" to rule.durationMs.toString(),
                "maxHoldMs" to rule.maxHoldMs.toString(),
                "lastEvent" to SensorGuard.lastEvent.value,
                "dummyRestrictPkg" to SensorGuardDefaults.DUMMY_RESTRICT_PKG,
                "topAppId" to snap.topActivity.appId,
                "topActivityId" to (snap.topActivity.activityId ?: ""),
                "currentRulesSize" to snap.currentRules.size.toString(),
                "emptyReason" to snap.emptyReason,
                "enableMatch" to settings.enableMatch.toString(),
                "enableAutomator" to settings.enableAutomator.toString(),
            ),
        )
    }
    /** M2.6 diagnostic: toggle match/automator flags without UI (Debug only). */
    get("/debug/sensor/test-flags") {
        val enableMatch = call.request.queryParameters["enableMatch"]?.toBooleanStrictOrNull()
        val enableAutomator =
            call.request.queryParameters["enableAutomator"]?.toBooleanStrictOrNull()
        if (enableMatch != null || enableAutomator != null) {
            SettingsRepository.updateSettings { current ->
                current.copy(
                    enableMatch = enableMatch ?: current.enableMatch,
                    enableAutomator = enableAutomator ?: current.enableAutomator,
                )
            }
        }
        val settings = SettingsRepository.settings.value
        call.respond(
            mapOf(
                "enableMatch" to settings.enableMatch.toString(),
                "enableAutomator" to settings.enableAutomator.toString(),
            ),
        )
    }
    get("/debug/sensor/target") {
        val pkg =
            call.request.queryParameters["pkg"]?.takeIf { it.isNotBlank() }
                ?: SensorGuardDefaults.M2_TARGET_PKG
        val delay = call.request.queryParameters["delay"]?.toLongOrNull() ?: 0L
        val duration = call.request.queryParameters["duration"]?.toLongOrNull() ?: 5_000L
        val maxHold = call.request.queryParameters["maxHold"]?.toLongOrNull() ?: 30_000L
        SensorGuard.setDebugTarget(pkg, delay, duration, maxHold)
        val rule = SensorGuard.rule.value
        call.respond(
            mapOf(
                "ok" to "true",
                "pkg" to rule.pkg,
                "delay" to rule.startDelayMs.toString(),
                "duration" to rule.durationMs.toString(),
                "maxHold" to rule.maxHoldMs.toString(),
                "lastEvent" to SensorGuard.lastEvent.value,
            ),
        )
    }
    get("/debug/sensor/calibrate") {
        if (!SensorShell.isAvailable()) {
            call.respond(
                HttpStatusCode.Conflict,
                mapOf("ok" to "false", "error" to "privilege_not_connected"),
            )
            return@get
        }
        val pkg =
            call.request.queryParameters["pkg"]?.takeIf { it.isNotBlank() }
                ?: SensorGuardDefaults.M2_TARGET_PKG
        val durationSec =
            call.request.queryParameters["durationSec"]?.toIntOrNull()?.coerceIn(1, 60) ?: 20
        val report = SensorShell.calibrate(pkg, durationSec)
        call.respondText(report, ContentType.Text.Plain)
    }
    post("/debug/sensor/freeze") {
        if (!SensorShell.isAvailable()) {
            call.respond(
                HttpStatusCode.Conflict,
                mapOf(
                    "ok" to "false",
                    "error" to "privilege_not_connected",
                    "hint" to "Reconnect privilege / Shizuku (UserService version bump requires it)",
                ),
            )
            return@post
        }
        val duration =
            call.request.queryParameters["duration"]?.toLongOrNull()
                ?: SensorGuard.rule.value.durationMs
        val ok =
            SensorShell.freeze(
                SensorGuardDefaults.DUMMY_RESTRICT_PKG,
                autoRestoreMs = duration.coerceIn(1_000L, 30_000L),
            )
        call.respond(
            mapOf(
                "ok" to ok.toString(),
                "pkg" to SensorGuardDefaults.DUMMY_RESTRICT_PKG,
                "autoRestoreMs" to duration.coerceIn(1_000L, 30_000L).toString(),
            ),
        )
    }
    post("/debug/sensor/restore") {
        if (!SensorShell.isAvailable()) {
            call.respond(
                HttpStatusCode.Conflict,
                mapOf("ok" to "false", "error" to "privilege_not_connected"),
            )
            return@post
        }
        call.respond(mapOf("ok" to SensorShell.restore().toString()))
    }
    get("/debug/capabilities") {
        call.respond(
            listOf(
                "/debug/app",
                "/debug/app/catalog",
                "/debug/readiness",
                "/debug/android/foreground",
                "/debug/android/preferred-a11y",
                "/debug/sessions",
                "/debug/policy",
                "/debug/engine",
                "/debug/android/benchmark",
                "/debug/sensor/status",
                "/debug/sensor/test-flags",
                "/debug/sensor/target",
                "/debug/sensor/calibrate",
                "/debug/sensor/freeze",
                "/debug/sensor/restore",
            ),
        )
    }
}

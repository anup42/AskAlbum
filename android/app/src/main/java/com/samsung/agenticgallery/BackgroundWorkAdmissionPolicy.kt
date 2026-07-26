package com.samsung.agenticgallery

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.content.Context
import android.os.PowerManager
import androidx.work.Constraints
import androidx.work.NetworkType

data class BackgroundWorkAdmission(
    val allowed: Boolean,
    val thermalStatus: Int,
    val reason: String?,
    val batteryPercent: Int = 100,
    val charging: Boolean = true,
)

data class IndexingRunCriteria(
    val requireCharging: Boolean = false,
    val minimumBatteryPercent: Int = 15,
    val pauseAtThermalStatus: Int = PowerManager.THERMAL_STATUS_MODERATE,
) {
    fun normalized(): IndexingRunCriteria = copy(
        minimumBatteryPercent = minimumBatteryPercent.coerceIn(10, 50),
        pauseAtThermalStatus = pauseAtThermalStatus.coerceIn(
            PowerManager.THERMAL_STATUS_LIGHT,
            PowerManager.THERMAL_STATUS_SEVERE,
        ),
    )
}

class IndexingRunCriteriaStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): IndexingRunCriteria = IndexingRunCriteria(
        requireCharging = preferences.getBoolean(KEY_REQUIRE_CHARGING, false),
        minimumBatteryPercent = preferences.getInt(KEY_MINIMUM_BATTERY, 15),
        pauseAtThermalStatus = preferences.getInt(KEY_THERMAL_STATUS, PowerManager.THERMAL_STATUS_MODERATE),
    ).normalized()

    fun save(criteria: IndexingRunCriteria): IndexingRunCriteria {
        val normalized = criteria.normalized()
        check(
            preferences.edit()
                .putBoolean(KEY_REQUIRE_CHARGING, normalized.requireCharging)
                .putInt(KEY_MINIMUM_BATTERY, normalized.minimumBatteryPercent)
                .putInt(KEY_THERMAL_STATUS, normalized.pauseAtThermalStatus)
                .commit(),
        ) { "Could not save indexing run criteria" }
        return normalized
    }

    private companion object {
        const val PREFERENCES = "indexing-run-criteria-v1"
        const val KEY_REQUIRE_CHARGING = "require_charging"
        const val KEY_MINIMUM_BATTERY = "minimum_battery_percent"
        const val KEY_THERMAL_STATUS = "pause_at_thermal_status"
    }
}

object ThermalWorkAdmission {
    fun evaluate(
        thermalStatus: Int,
        pauseAtThermalStatus: Int = PowerManager.THERMAL_STATUS_MODERATE,
    ): BackgroundWorkAdmission {
        val paused = thermalStatus >= pauseAtThermalStatus
        return BackgroundWorkAdmission(
            allowed = !paused,
            thermalStatus = thermalStatus,
            reason = if (paused) "thermal_status_$thermalStatus" else null,
        )
    }
}

class BackgroundWorkAdmissionPolicy(context: Context) {
    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private val criteriaStore = IndexingRunCriteriaStore(appContext)

    fun evaluate(): BackgroundWorkAdmission {
        val criteria = criteriaStore.load()
        val thermalStatus = runCatching { powerManager.currentThermalStatus }
            .getOrDefault(PowerManager.THERMAL_STATUS_NONE)
        val battery = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPercent = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else 100
        val batteryStatus = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val charging = batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
            batteryStatus == BatteryManager.BATTERY_STATUS_FULL
        val thermal = ThermalWorkAdmission.evaluate(thermalStatus, criteria.pauseAtThermalStatus)
        val reason = when {
            !thermal.allowed ->
                "Device thermal state is ${thermalStatusLabel(thermalStatus)}; resumes below ${thermalStatusLabel(criteria.pauseAtThermalStatus)}"
            criteria.requireCharging && !charging -> "Waiting for charger"
            !charging && batteryPercent < criteria.minimumBatteryPercent ->
                "Battery $batteryPercent% is below the ${criteria.minimumBatteryPercent}% limit"
            else -> null
        }
        return BackgroundWorkAdmission(
            allowed = reason == null,
            thermalStatus = thermalStatus,
            reason = reason,
            batteryPercent = batteryPercent,
            charging = charging,
        )
    }
}

fun indexingWorkerConstraints(
    context: Context,
    forceCharging: Boolean = false,
    requireDeviceIdle: Boolean = false,
): Constraints {
    val criteria = IndexingRunCriteriaStore(context).load()
    return Constraints.Builder()
        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
        .setRequiresCharging(forceCharging || criteria.requireCharging)
        .setRequiresBatteryNotLow(criteria.minimumBatteryPercent >= 15)
        .setRequiresStorageNotLow(true)
        .setRequiresDeviceIdle(requireDeviceIdle)
        .build()
}

fun thermalStatusLabel(status: Int): String = when (status) {
    PowerManager.THERMAL_STATUS_NONE -> "none"
    PowerManager.THERMAL_STATUS_LIGHT -> "light"
    PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
    PowerManager.THERMAL_STATUS_SEVERE -> "severe"
    PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
    PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
    PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
    else -> "unknown"
}

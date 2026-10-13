package com.a0soft.gphone.camonitor.ui.common

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

data class BatteryInfo(
    val percentage: Int,
    val isCharging: Boolean
)

object BatteryHelper {
    fun getBatteryInfo(context: Context): BatteryInfo {
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus: Intent? = context.registerReceiver(null, filter)
            val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            BatteryInfo(percentage = pct, isCharging = isCharging)
        } catch (_: Exception) {
            BatteryInfo(percentage = -1, isCharging = false)
        }
    }

    fun formatPercentage(info: BatteryInfo?): String {
        val pctText = if (info == null || info.percentage < 0) "--%" else "${info.percentage}%"
        val chargingText = if (info?.isCharging == true) "⚡" else ""
        return "$pctText$chargingText"
    }
}

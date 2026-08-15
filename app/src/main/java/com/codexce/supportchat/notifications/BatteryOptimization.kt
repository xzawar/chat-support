package com.codexce.supportchat.notifications

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Doze and OEM task killers, which are the real reason a push arrives minutes late.
 *
 * A HIGH priority FCM message is allowed to wake a dozing device, but that guarantee ends where
 * the manufacturer own power manager begins. Xiaomi, Oppo, Vivo, Huawei and Samsung all ship
 * extra killers that stop a background process outright, and none can be disabled from code.
 * All an app can do is take the one exemption Android does offer, then send the user to the OEM
 * screen for the rest.
 */
object BatteryOptimization {

    /** Whether Android itself has stopped throttling this app. */
    fun isExempt(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val power = context.getSystemService(PowerManager::class.java) ?: return true
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Asks for the exemption directly: the system dialog with an Allow button, not a settings
     * screen the user has to navigate.
     */
    @SuppressLint("BatteryLife")
    fun requestExemption(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:" + context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(direct) }.isFailure) openBatterySettings(context)
    }

    /** The full list, used when the direct request is unavailable. */
    fun openBatterySettings(context: Context) {
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(fallback) }.onFailure { openAppDetails(context) }
    }

    /**
     * Known autostart managers. None of this is documented API, so every entry is attempted and
     * the first that resolves wins.
     */
    private val autostartScreens = listOf(
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
        "com.asus.mobilemanager" to "com.asus.mobilemanager.entry.FunctionActivity",
        "com.letv.android.letvsafe" to "com.letv.android.letvsafe.AutobootManageActivity",
    )

    /** True when an OEM autostart screen was opened. */
    fun openAutostart(context: Context): Boolean {
        for ((pkg, cls) in autostartScreens) {
            val intent = Intent()
                .setComponent(ComponentName(pkg, cls))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val resolves = context.packageManager.resolveActivity(intent, 0) != null
            if (resolves && runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        return false
    }

    /** Settings > Apps > Support Chat, where Battery and Autostart both live on most skins. */
    fun openAppDetails(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:" + context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}

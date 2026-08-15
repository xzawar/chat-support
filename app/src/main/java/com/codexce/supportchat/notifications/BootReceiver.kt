package com.codexce.supportchat.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * A reboot stops every service on the device. Without this the app would look connected in
 * Settings while silently having no listener until the user next opened it.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            MessageWatchService.start(context)
        }
    }
}

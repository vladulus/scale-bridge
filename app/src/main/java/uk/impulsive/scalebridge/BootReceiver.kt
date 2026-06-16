package uk.impulsive.scalebridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Restarts the listening service after a reboot.
 * On OxygenOS/ColorOS this only fires if "Auto-launch" is enabled for the app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            ContextCompat.startForegroundService(
                context, Intent(context, ScaleService::class.java)
            )
        }
    }
}

package de.uhd.ifi.babycryclassifier;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

/**
 * Restarts the detection service automatically after the phone reboots.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            ContextCompat.startForegroundService(
                    context,
                    new Intent(context, CryDetectionService.class));
        }
    }
}


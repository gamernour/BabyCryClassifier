package de.uhd.ifi.babycryclassifier;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;

/**
 * FeedbackAlarmReceiver
 *
 * Triggered by AlarmManager exactly 5 minutes after a cry classification.
 * Posts a notification that, when tapped, opens FeedbackActivity.
 */
public class FeedbackAlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        android.util.Log.d("FeedbackAlarm", "Receiver fired! label=" + intent.getStringExtra(MainActivity.EXTRA_TOP1_LABEL));
        String label    = intent.getStringExtra(MainActivity.EXTRA_TOP1_LABEL);
        int    recordId = intent.getIntExtra(FlashActivity.EXTRA_RECORD_ID, -1);

        if (label == null) label = "Unknown";

        Intent feedbackIntent = new Intent(context, FeedbackActivity.class);
        feedbackIntent.putExtra(MainActivity.EXTRA_TOP1_LABEL, label);
        feedbackIntent.putExtra(FlashActivity.EXTRA_RECORD_ID, recordId);
        feedbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pi = PendingIntent.getActivity(
                context, recordId, feedbackIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(
                context, "CryDetectionChannel")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Was the classification correct?")
                .setContentText("5 minutes ago I detected: " + label + ". Was I right?")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi);

        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            android.util.Log.d("FeedbackAlarm", "Posting notification with id=" + (recordId + 1000));
            // Use recordId as notification id so multiple events don't overwrite each other
            nm.notify(recordId + 1000, builder.build());
            android.util.Log.d("FeedbackAlarm", "notify() called, channel exists: " + (nm.getNotificationChannel("CryDetectionChannel") != null));
        }
    }
}
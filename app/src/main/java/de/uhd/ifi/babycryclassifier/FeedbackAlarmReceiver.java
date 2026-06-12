package de.uhd.ifi.babycryclassifier;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

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

        // Launch FeedbackActivity directly — works whether app is in foreground or background
        Intent feedbackIntent = new Intent(context, FeedbackActivity.class);
        feedbackIntent.putExtra(MainActivity.EXTRA_TOP1_LABEL, label);
        feedbackIntent.putExtra(FlashActivity.EXTRA_RECORD_ID, recordId);
        feedbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(feedbackIntent);

        // Also post a notification as a fallback in case the activity can't launch
        // (e.g. phone is locked and app is fully stopped)
        Intent notifIntent = new Intent(context, FeedbackActivity.class);
        notifIntent.putExtra(MainActivity.EXTRA_TOP1_LABEL, label);
        notifIntent.putExtra(FlashActivity.EXTRA_RECORD_ID, recordId);
        notifIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pi = PendingIntent.getActivity(
                context, recordId, notifIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        androidx.core.app.NotificationCompat.Builder builder =
                new androidx.core.app.NotificationCompat.Builder(context, "CryDetectionChannel")
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle("Was the classification correct?")
                        .setContentText("I detected: " + label + ". Was I right?")
                        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .setContentIntent(pi);

        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(recordId + 1000, builder.build());
    }
}
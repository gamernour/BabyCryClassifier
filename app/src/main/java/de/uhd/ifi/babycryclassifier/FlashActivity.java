package de.uhd.ifi.babycryclassifier;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

public class FlashActivity extends Activity {

    public static final String EXTRA_RECORD_ID = "record_id";

    private static final long FLASH_DURATION_MS = 5_000;
    private static final long FEEDBACK_DELAY_MS = 2 * 1_000;  // 2 seconds

    private static final Object[][] CLASS_CONFIG = {
            // label           bg colour   text colour   emoji
            { "Belly pain",   "#C0DD97",  "#173404",    "🤕" },
            { "Need to burp", "#FFB347",  "#412402",    "🫧" },
            { "Discomfort",   "#5DCAA5",  "#04342C",    "😣" },
            { "Hunger",       "#F09595",  "#501313",    "🍼" },
            { "Tiredness",    "#85B7EB",  "#042C53",    "😴" },
    };

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );

        setContentView(R.layout.activity_flash);

        String label     = getIntent().getStringExtra(MainActivity.EXTRA_TOP1_LABEL);
        int    percent   = getIntent().getIntExtra(MainActivity.EXTRA_TOP1_PERCENT, 0);
        String top2Label = getIntent().getStringExtra(MainActivity.EXTRA_TOP2_LABEL);
        int    top2Pct   = getIntent().getIntExtra(MainActivity.EXTRA_TOP2_PERCENT, 0);
        int    recordId  = getIntent().getIntExtra(EXTRA_RECORD_ID, -1);

        if (label == null) label = "Unknown";

        Object[] config   = configForLabel(label);
        int      bgColor  = Color.parseColor((String) config[1]);
        int      txtColor = Color.parseColor((String) config[2]);
        String   emoji    = (String) config[3];

        View     root       = findViewById(R.id.flashRoot);
        View     timerBar   = findViewById(R.id.timerBar);
        View     timerTrack = findViewById(R.id.timerTrack);
        TextView flashEmoji = findViewById(R.id.flashEmoji);
        TextView flashLabel = findViewById(R.id.flashLabel);
        TextView flashSub   = findViewById(R.id.flashSub);

        root.setBackgroundColor(bgColor);
        timerBar.setBackgroundColor(txtColor);
        timerTrack.setBackgroundColor(txtColor);
        flashEmoji.setText(emoji);
        flashLabel.setText(label);
        flashLabel.setTextColor(txtColor);
        flashSub.setTextColor(txtColor);

        // Animate timer bar shrinking over 5 seconds
        ValueAnimator anim = ValueAnimator.ofFloat(1f, 0f);
        anim.setDuration(FLASH_DURATION_MS);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> {
            float fraction = (float) a.getAnimatedValue();
            timerBar.setScaleX(fraction);
            timerBar.setPivotX(0f);
        });
        anim.start();

        // Broadcast to HomeFragment so result card updates in background
        Intent broadcast = new Intent(MainActivity.ACTION_CRY_RESULT);
        broadcast.putExtra(MainActivity.EXTRA_TOP1_LABEL,   label);
        broadcast.putExtra(MainActivity.EXTRA_TOP1_PERCENT, percent);
        broadcast.putExtra(MainActivity.EXTRA_TOP2_LABEL,   top2Label);
        broadcast.putExtra(MainActivity.EXTRA_TOP2_PERCENT, top2Pct);
        sendBroadcast(broadcast);

        final String finalLabel = label;
        final int    finalId    = recordId;
        handler.postDelayed(() -> {
            scheduleFeedbackAlarm(finalLabel, finalId);
            finish();
        }, FLASH_DURATION_MS);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }

    private void scheduleFeedbackAlarm(String label, int recordId) {
        Intent alarmIntent = new Intent(this, FeedbackAlarmReceiver.class);
        alarmIntent.putExtra(MainActivity.EXTRA_TOP1_LABEL, label);
        alarmIntent.putExtra(EXTRA_RECORD_ID, recordId);

        android.app.PendingIntent alarmPi = android.app.PendingIntent.getBroadcast(
                this, recordId == -1 ? 0 : recordId, alarmIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT |
                        android.app.PendingIntent.FLAG_IMMUTABLE
        );
        android.util.Log.d("FlashActivity", "Alarm scheduled for " + new java.util.Date(System.currentTimeMillis() + FEEDBACK_DELAY_MS));

        android.app.AlarmManager am =
                (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
        if (am != null) {
            long triggerAt = System.currentTimeMillis() + FEEDBACK_DELAY_MS;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(
                        android.app.AlarmManager.RTC_WAKEUP, triggerAt, alarmPi);
            } else {
                am.setExact(android.app.AlarmManager.RTC_WAKEUP, triggerAt, alarmPi);
            }
        }
    }

    private Object[] configForLabel(String label) {
        for (Object[] row : CLASS_CONFIG) {
            if (((String) row[0]).equalsIgnoreCase(label)) return row;
        }
        return new Object[]{ "Unknown", "#D3D1C7", "#2C2C2A", "❓" };
    }
}
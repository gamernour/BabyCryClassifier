package de.uhd.ifi.babycryclassifier;

import android.Manifest;
import android.app.AlarmManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.widget.EditText;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {

    public static final String ACTION_CRY_RESULT  = "de.uhd.ifi.babycryclassifier.CRY_RESULT";
    public static final String EXTRA_TOP1_LABEL   = "top1_label";
    public static final String EXTRA_TOP1_PERCENT = "top1_percent";
    public static final String EXTRA_TOP2_LABEL   = "top2_label";
    public static final String EXTRA_TOP2_PERCENT = "top2_percent";

    public static final String PREFS_NAME        = "BabyCryPrefs";
    public static final String KEY_PARTICIPANT_ID = "participant_id";
    public static final String KEY_BABY_ID        = "baby_id";

    private static final int PERMISSION_REQUEST_CODE = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        CryClassifier.testAssets(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        requestPermissions();
        checkExactAlarmPermission();

        // Load HomeFragment by default
        if (savedInstanceState == null) {
            loadFragment(new HomeFragment());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.app.NotificationChannel ch = new android.app.NotificationChannel(
                        "CryDetectionChannel",
                        "Cry Detection",
                        android.app.NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("Baby cry feedback");
                ch.enableVibration(true);
                android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
                if (nm != null) nm.createNotificationChannel(ch);
            }

            // Show participant ID dialog if not yet set for this device
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String existing = prefs.getString(KEY_PARTICIPANT_ID, null);
            if (existing == null || existing.isEmpty()) {
                showParticipantIdDialog(false);
            }
        }

        // Bottom navigation switching
        BottomNavigationView bottomNav = findViewById(R.id.bottomNav);
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_home) {
                loadFragment(new HomeFragment());
                return true;
            } else if (id == R.id.nav_history) {
                loadFragment(new HistoryFragment());
                return true;
            }
            return false;
        });
    }

    /**
     * Shows the participant ID entry dialog.
     * cancellable=false on first launch so the researcher must set it before proceeding.
     * cancellable=true when triggered by "change" button.
     */
    public void showParticipantIdDialog(boolean cancellable) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        android.widget.LinearLayout inner = new android.widget.LinearLayout(this);
        inner.setOrientation(android.widget.LinearLayout.VERTICAL);
        inner.setPadding(56, 24, 56, 8);

        // Participant ID — label above field (no hint, avoids strikethrough bug)
        android.widget.TextView lblParticipant = new android.widget.TextView(this);
        lblParticipant.setText("Participant ID (e.g. P01)");
        lblParticipant.setTextSize(12f);
        lblParticipant.setTextColor(0xFF888888);

        EditText inputParticipant = new EditText(this);
        inputParticipant.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        inputParticipant.setFilters(new InputFilter[]{ new InputFilter.LengthFilter(10) });
        inputParticipant.setTextSize(16f);
        String existingPid = prefs.getString(KEY_PARTICIPANT_ID, "");
        if (!existingPid.isEmpty()) inputParticipant.setText(existingPid);

        // Baby ID — label above field
        android.widget.TextView lblBaby = new android.widget.TextView(this);
        lblBaby.setText("Baby ID (e.g. B01)");
        lblBaby.setTextSize(12f);
        lblBaby.setTextColor(0xFF888888);
        lblBaby.setPadding(0, 24, 0, 0);

        EditText inputBaby = new EditText(this);
        inputBaby.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        inputBaby.setFilters(new InputFilter[]{ new InputFilter.LengthFilter(10) });
        inputBaby.setTextSize(16f);
        String existingBid = prefs.getString(KEY_BABY_ID, "");
        if (!existingBid.isEmpty()) inputBaby.setText(existingBid);

        inner.addView(lblParticipant);
        inner.addView(inputParticipant);
        inner.addView(lblBaby);
        inner.addView(inputBaby);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.session_setup_title))
                .setMessage(getString(R.string.session_setup_message))
                .setView(inner)
                .setCancelable(cancellable)
                .setPositiveButton(getString(R.string.btn_start_session), (d, w) -> {
                    String pid = inputParticipant.getText().toString().trim().toUpperCase();
                    String bid = inputBaby.getText().toString().trim().toUpperCase();
                    if (!pid.isEmpty()) prefs.edit().putString(KEY_PARTICIPANT_ID, pid).apply();
                    if (!bid.isEmpty()) prefs.edit().putString(KEY_BABY_ID, bid).apply();
                    Fragment frag = getSupportFragmentManager()
                            .findFragmentById(R.id.fragmentContainer);
                    if (frag instanceof HomeFragment) {
                        ((HomeFragment) frag).updateParticipantBadge();
                    }
                })
                .create();

        if (!cancellable) dialog.setCanceledOnTouchOutside(false);
        dialog.show();
    }

    private void loadFragment(Fragment fragment) {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragmentContainer, fragment)
                .commit();
    }

    private void requestPermissions() {
        String[] perms = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? new String[]{Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS}
                : new String[]{Manifest.permission.RECORD_AUDIO};
        ActivityCompat.requestPermissions(this, perms, PERMISSION_REQUEST_CODE);
    }

    /**
     * On Android 12+ (S), SCHEDULE_EXACT_ALARM requires the user to grant permission
     * manually in Settings if they previously denied it.
     * Without it the 5-minute feedback notification won't fire on time.
     */
    private void checkExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (am != null && !am.canScheduleExactAlarms()) {
                new AlertDialog.Builder(this)
                        .setTitle(getString(R.string.alarm_title))
                        .setMessage(getString(R.string.alarm_message))
                        .setPositiveButton(getString(R.string.btn_open_settings), (d, w) -> {
                            Intent intent = new Intent(
                                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                    Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        })
                        .setNegativeButton(getString(R.string.btn_skip), null)
                        .show();
            }
        }
    }
}
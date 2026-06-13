package de.uhd.ifi.babycryclassifier;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

public class FeedbackActivity extends AppCompatActivity {

    private int recordId;
    private String selectedLabel = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { }
        });

        setContentView(R.layout.activity_feedback);

        String label = getIntent().getStringExtra(MainActivity.EXTRA_TOP1_LABEL);
        recordId     = getIntent().getIntExtra(FlashActivity.EXTRA_RECORD_ID, -1);
        if (label == null) label = "Unknown";

        TextView resultLabel = findViewById(R.id.feedbackResultLabel);
        resultLabel.setText(getString(R.string.feedback_detected, localiseLabel(label)));

        View step1 = findViewById(R.id.step1Layout);
        View step2 = findViewById(R.id.step2Layout);
        step1.setVisibility(View.VISIBLE);
        step2.setVisibility(View.GONE);

        // Each button stores the English DB key as selectedLabel, displays translated text via XML
        int[] ids = { R.id.btnHunger, R.id.btnBurp, R.id.btnDiscomfort, R.id.btnBellyPain, R.id.btnTiredness };
        String[] keys = { "Hunger", "Need to burp", "Discomfort", "Belly pain", "Tiredness" };

        for (int i = 0; i < ids.length; i++) {
            final String key = keys[i];
            findViewById(ids[i]).setOnClickListener(v -> {
                selectedLabel = key;
                step1.setVisibility(View.GONE);
                step2.setVisibility(View.VISIBLE);
            });
        }

        findViewById(R.id.btnNotSure).setOnClickListener(v -> saveFeedback("unsure", "low"));
        findViewById(R.id.btnConfHigh).setOnClickListener(v   -> saveFeedback(selectedLabel, "high"));
        findViewById(R.id.btnConfMedium).setOnClickListener(v -> saveFeedback(selectedLabel, "medium"));
        findViewById(R.id.btnConfLow).setOnClickListener(v    -> saveFeedback(selectedLabel, "low"));
    }

    private void saveFeedback(String label, String confidence) {
        if (recordId != -1) {
            CryRepository repo = CryRepository.getInstance(getApplicationContext());
            repo.updateUserLabel(recordId, label, confidence);
            repo.updateFeedback(recordId, "unsure".equals(label) ? "unsure" : "labelled");
        }
        getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .remove(HomeFragment.KEY_PENDING_FEEDBACK_ID)
                .remove(HomeFragment.KEY_PENDING_FEEDBACK_LABEL)
                .remove(HomeFragment.KEY_PENDING_FEEDBACK_TIME)
                .apply();
        Toast.makeText(this, getString(R.string.feedback_thanks), Toast.LENGTH_SHORT).show();
        finish();
    }

    /** Converts English DB key → localised display name for the detected label. */
    private String localiseLabel(String dbLabel) {
        if (dbLabel == null) return "";
        switch (dbLabel) {
            case "Hunger":       return getString(R.string.label_hunger);
            case "Need to burp": return getString(R.string.label_burp);
            case "Discomfort":   return getString(R.string.label_discomfort);
            case "Belly pain":   return getString(R.string.label_belly_pain);
            case "Tiredness":    return getString(R.string.label_tiredness);
            default:             return dbLabel;
        }
    }
}
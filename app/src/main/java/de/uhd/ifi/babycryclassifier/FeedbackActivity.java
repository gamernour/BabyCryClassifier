package de.uhd.ifi.babycryclassifier;

import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

public class FeedbackActivity extends AppCompatActivity {

    private int    recordId;
    private String selectedLabel    = null;
    private String selectedConf     = null;

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
        View step3 = findViewById(R.id.step3Layout);
        View step4 = findViewById(R.id.step4Layout);

        step1.setVisibility(View.VISIBLE);
        step2.setVisibility(View.GONE);
        step3.setVisibility(View.GONE);
        step4.setVisibility(View.GONE);

        // Step 1 — class selection
        int[] classIds = { R.id.btnHunger, R.id.btnBurp, R.id.btnDiscomfort,
                R.id.btnBellyPain, R.id.btnTiredness };
        String[] classKeys = { "Hunger", "Need to burp", "Discomfort", "Belly pain", "Tiredness" };

        for (int i = 0; i < classIds.length; i++) {
            final String key = classKeys[i];
            findViewById(classIds[i]).setOnClickListener(v -> {
                selectedLabel = key;
                step1.setVisibility(View.GONE);
                step2.setVisibility(View.VISIBLE);
            });
        }
        findViewById(R.id.btnNotSure).setOnClickListener(v -> {
            selectedLabel = "unsure";
            step1.setVisibility(View.GONE);
            step3.setVisibility(View.VISIBLE); // skip confidence for unsure
        });

        // Step 2 — confidence
        findViewById(R.id.btnConfHigh).setOnClickListener(v -> {
            selectedConf = "high";
            step2.setVisibility(View.GONE);
            step3.setVisibility(View.VISIBLE);
        });
        findViewById(R.id.btnConfMedium).setOnClickListener(v -> {
            selectedConf = "medium";
            step2.setVisibility(View.GONE);
            step3.setVisibility(View.VISIBLE);
        });
        findViewById(R.id.btnConfLow).setOnClickListener(v -> {
            selectedConf = "low";
            step2.setVisibility(View.GONE);
            step3.setVisibility(View.VISIBLE);
        });

        // Step 3 — what stopped the crying + noise level → save
        SeekBar noiseBar = findViewById(R.id.noiseSeekBar);
        TextView noiseVal = findViewById(R.id.noiseValue);
        noiseBar.setMax(4); // 0-4 = displayed as 1-5
        noiseBar.setProgress(2);
        noiseVal.setText("3");
        noiseBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) { noiseVal.setText(String.valueOf(p + 1)); }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });

        // Step 4 — notes (optional)
        EditText notesField = findViewById(R.id.notesField);

        findViewById(R.id.btnStep3Next).setOnClickListener(v -> {
            step3.setVisibility(View.GONE);
            step4.setVisibility(View.VISIBLE);
        });

        findViewById(R.id.btnSubmit).setOnClickListener(v -> {
            // Collect what stopped crying
            int[][] stopIds = {
                    {R.id.cbFeeding,   0}, {R.id.cbBurping,  1}, {R.id.cbDiaper, 2},
                    {R.id.cbHolding,   3}, {R.id.cbSleep,    4}, {R.id.cbSelf,   5},
                    {R.id.cbOther,     6}
            };
            String[] stopKeys = { "feeding", "burping", "diaper", "holding", "sleep", "self", "other" };
            StringBuilder stopped = new StringBuilder();
            for (int i = 0; i < stopIds.length; i++) {
                CheckBox cb = findViewById(stopIds[i][0]);
                if (cb != null && cb.isChecked()) {
                    if (stopped.length() > 0) stopped.append(",");
                    stopped.append(stopKeys[i]);
                }
            }
            int noise   = noiseBar.getProgress() + 1;
            String notes = notesField.getText().toString().trim();

            saveFeedback(selectedLabel,
                    "unsure".equals(selectedLabel) ? "low" : selectedConf,
                    stopped.toString(), noise,
                    notes.isEmpty() ? null : notes);
        });

        // Skip notes button
        findViewById(R.id.btnSkipNotes).setOnClickListener(v -> {
            int[][] stopIds = {
                    {R.id.cbFeeding, 0}, {R.id.cbBurping, 1}, {R.id.cbDiaper, 2},
                    {R.id.cbHolding, 3}, {R.id.cbSleep,   4}, {R.id.cbSelf,   5},
                    {R.id.cbOther,   6}
            };
            String[] stopKeys = { "feeding", "burping", "diaper", "holding", "sleep", "self", "other" };
            StringBuilder stopped = new StringBuilder();
            for (int i = 0; i < stopIds.length; i++) {
                CheckBox cb = findViewById(stopIds[i][0]);
                if (cb != null && cb.isChecked()) {
                    if (stopped.length() > 0) stopped.append(",");
                    stopped.append(stopKeys[i]);
                }
            }
            int noise = noiseBar.getProgress() + 1;
            saveFeedback(selectedLabel,
                    "unsure".equals(selectedLabel) ? "low" : selectedConf,
                    stopped.toString(), noise, null);
        });
    }

    private void saveFeedback(String label, String confidence,
                              String whatStopped, int noise, String notes) {
        if (recordId != -1) {
            CryRepository repo = CryRepository.getInstance(getApplicationContext());
            repo.updateUserLabel(recordId, label, confidence);
            repo.updateFeedback(recordId, "unsure".equals(label) ? "unsure" : "labelled");
            repo.updateContextInfo(recordId, whatStopped, noise, notes);
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
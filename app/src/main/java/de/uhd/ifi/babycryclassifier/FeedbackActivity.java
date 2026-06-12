package de.uhd.ifi.babycryclassifier;

import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

/**
 * FeedbackActivity
 *
 * Shown 1 minute after a cry classification.
 * Parent taps Yes / No / Not sure → answer saved to the database.
 */
public class FeedbackActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Block back button — force parent to answer
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // Do nothing
            }
        });

        setContentView(R.layout.activity_feedback);

        String label    = getIntent().getStringExtra(MainActivity.EXTRA_TOP1_LABEL);
        int    recordId = getIntent().getIntExtra(FlashActivity.EXTRA_RECORD_ID, -1);

        if (label == null) label = "Unknown";

        TextView resultLabel = findViewById(R.id.feedbackResultLabel);
        resultLabel.setText("I detected: " + label);

        Button btnYes    = findViewById(R.id.btnYes);
        Button btnNo     = findViewById(R.id.btnNo);
        Button btnUnsure = findViewById(R.id.btnUnsure);

        final int finalId = recordId;

        btnYes.setOnClickListener(v    -> saveFeedback(finalId, "yes"));
        btnNo.setOnClickListener(v     -> saveFeedback(finalId, "no"));
        btnUnsure.setOnClickListener(v -> saveFeedback(finalId, "unsure"));
    }

    private void saveFeedback(int recordId, String feedback) {
        if (recordId != -1) {
            CryRepository.getInstance(getApplicationContext())
                    .updateFeedback(recordId, feedback);
        }
        // Clear the pending feedback so onResume doesn't re-trigger it
        getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .remove(HomeFragment.KEY_PENDING_FEEDBACK_ID)
                .remove(HomeFragment.KEY_PENDING_FEEDBACK_LABEL)
                .remove(HomeFragment.KEY_PENDING_FEEDBACK_TIME)
                .apply();
        Toast.makeText(this, "Thanks for your feedback!", Toast.LENGTH_SHORT).show();
        finish();
    }
}
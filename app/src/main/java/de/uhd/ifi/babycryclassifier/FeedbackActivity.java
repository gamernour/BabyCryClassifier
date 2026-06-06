package de.uhd.ifi.babycryclassifier;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

/**
 * FeedbackActivity
 *
 * Shown 5 minutes after a cry classification (via the alarm notification).
 * Parent taps Yes / No / Not sure → answer saved to the database.
 */
public class FeedbackActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_feedback);

        String label    = getIntent().getStringExtra(MainActivity.EXTRA_TOP1_LABEL);
        int    recordId = getIntent().getIntExtra(FlashActivity.EXTRA_RECORD_ID, -1);

        if (label == null) label = "Unknown";

        TextView resultLabel = findViewById(R.id.feedbackResultLabel);
        resultLabel.setText("I detected: " + label);

        Button btnYes    = findViewById(R.id.btnYes);
        Button btnNo     = findViewById(R.id.btnNo);
        Button btnUnsure = findViewById(R.id.btnUnsure);

        final String finalLabel = label;
        final int    finalId    = recordId;

        btnYes.setOnClickListener(v -> saveFeedback(finalId, "yes"));
        btnNo.setOnClickListener(v  -> saveFeedback(finalId, "no"));
        btnUnsure.setOnClickListener(v -> saveFeedback(finalId, "unsure"));
    }

    private void saveFeedback(int recordId, String feedback) {
        if (recordId != -1) {
            CryRepository.getInstance(getApplicationContext())
                    .updateFeedback(recordId, feedback);
        }
        Toast.makeText(this, "Thanks for your feedback!", Toast.LENGTH_SHORT).show();
        finish();
    }
}

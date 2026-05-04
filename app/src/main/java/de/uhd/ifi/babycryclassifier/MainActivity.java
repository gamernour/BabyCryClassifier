package de.uhd.ifi.babycryclassifier;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    public static final String ACTION_CRY_RESULT  = "de.uhd.ifi.babycryclassifier.CRY_RESULT";
    public static final String EXTRA_TOP1_LABEL   = "top1_label";
    public static final String EXTRA_TOP1_PERCENT = "top1_percent";
    public static final String EXTRA_TOP2_LABEL   = "top2_label";
    public static final String EXTRA_TOP2_PERCENT = "top2_percent";

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int SAMPLE_RATE             = 16_000;
    private static final int RECORD_SECONDS          = 3;
    private static final int RECORD_SAMPLES          = SAMPLE_RATE * RECORD_SECONDS;

    private TextView statusText;
    private TextView top1Text;
    private TextView top2Text;
    private Button   startListeningButton;
    private Button   stopListeningButton;
    private Button   recordButton;
    private Button   analyzeButton;

    private short[]  recordedClip  = null;
    private boolean  serviceRunning = false;

    // ── BroadcastReceiver — receives result from CryDetectionService
    private final BroadcastReceiver cryResultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_CRY_RESULT.equals(intent.getAction())) return;
            String top1Label   = intent.getStringExtra(EXTRA_TOP1_LABEL);
            int    top1Percent = intent.getIntExtra(EXTRA_TOP1_PERCENT, 0);
            String top2Label   = intent.getStringExtra(EXTRA_TOP2_LABEL);
            int    top2Percent = intent.getIntExtra(EXTRA_TOP2_PERCENT, 0);
            showResult(top1Label, top1Percent, top2Label, top2Percent);
            serviceRunning = false;
            updateListeningButtons();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        requestPermissions();
        wireButtons();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(ACTION_CRY_RESULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(cryResultReceiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(cryResultReceiver, filter);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(cryResultReceiver);
    }

    private void bindViews() {
        statusText           = findViewById(R.id.statusText);
        top1Text             = findViewById(R.id.topOneResult);
        top2Text             = findViewById(R.id.topTwoResult);
        startListeningButton = findViewById(R.id.startListeningButton);
        stopListeningButton  = findViewById(R.id.stopListeningButton);
        recordButton         = findViewById(R.id.recordButton);
        analyzeButton        = findViewById(R.id.analyzeButton);
    }

    private void wireButtons() {
        startListeningButton.setOnClickListener(v -> startDetectionService());
        stopListeningButton.setOnClickListener(v  -> stopDetectionService());
        recordButton.setOnClickListener(v         -> recordManualClip());
        analyzeButton.setOnClickListener(v        -> analyzeManualClip());
        updateListeningButtons();
    }

    private void requestPermissions() {
        String[] perms = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? new String[]{Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS}
                : new String[]{Manifest.permission.RECORD_AUDIO};
        ActivityCompat.requestPermissions(this, perms, PERMISSION_REQUEST_CODE);
    }

    private void startDetectionService() {
        ContextCompat.startForegroundService(this,
                new Intent(this, CryDetectionService.class));
        serviceRunning = true;
        statusText.setText("Listening for baby cry…");
        clearResults();
        updateListeningButtons();
    }

    private void stopDetectionService() {
        stopService(new Intent(this, CryDetectionService.class));
        serviceRunning = false;
        statusText.setText("Detection stopped.");
        updateListeningButtons();
    }

    private void updateListeningButtons() {
        startListeningButton.setEnabled(!serviceRunning);
        stopListeningButton.setEnabled(serviceRunning);
    }

    private void recordManualClip() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone permission required", Toast.LENGTH_SHORT).show();
            return;
        }

        statusText.setText("Recording " + RECORD_SECONDS + " s…");
        recordButton.setEnabled(false);
        analyzeButton.setEnabled(false);
        recordedClip = null;

        new Thread(() -> {
            int bufSize = Math.max(
                    AudioRecord.getMinBufferSize(SAMPLE_RATE,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT),
                    RECORD_SAMPLES * 2);

            AudioRecord recorder = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize);

            short[] clip = new short[RECORD_SAMPLES];
            int pos = 0;
            recorder.startRecording();
            while (pos < RECORD_SAMPLES) {
                int read = recorder.read(clip, pos, RECORD_SAMPLES - pos);
                if (read > 0) pos += read;
            }
            recorder.stop();
            recorder.release();
            recordedClip = clip;

            runOnUiThread(() -> {
                statusText.setText("Recording done. Press Analyse.");
                recordButton.setEnabled(true);
                analyzeButton.setEnabled(true);
            });
        }, "record-thread").start();
    }

    private void analyzeManualClip() {
        if (recordedClip == null) {
            Toast.makeText(this, "Record audio first", Toast.LENGTH_SHORT).show();
            return;
        }

        statusText.setText("Analysing…");
        analyzeButton.setEnabled(false);

        short[] clip = recordedClip;

        new Thread(() -> {
            CryClassifier.PredictionResult result = null;
            try {
                CryClassifier classifier = new CryClassifier(this);
                result = classifier.predict(clip);
            } catch (Exception e) {
                android.util.Log.e("MainActivity", "Classification error", e);
            }

            final CryClassifier.PredictionResult finalResult = result;
            runOnUiThread(() -> {
                analyzeButton.setEnabled(true);
                if (finalResult != null) {
                    showResult(finalResult.top1Label, finalResult.top1Percent,
                            finalResult.top2Label, finalResult.top2Percent);
                } else {
                    statusText.setText("Classification failed. Check Logcat.");
                }
            });
        }, "analyse-thread").start();
    }
    private void showResult(String l1, int p1, String l2, int p2) {
        statusText.setText("Cry classified!");
        top1Text.setText("Most likely: "        + l1 + " (" + p1 + "%)");
        top2Text.setText("Second possibility: " + l2 + " (" + p2 + "%)");
    }

    private void clearResults() {
        top1Text.setText("");
        top2Text.setText("");
    }
}
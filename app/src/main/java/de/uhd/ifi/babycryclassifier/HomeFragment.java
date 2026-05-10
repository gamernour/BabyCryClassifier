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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

public class HomeFragment extends Fragment {

    private static final int SAMPLE_RATE    = 16_000;
    private static final int RECORD_SECONDS = 3;
    private static final int RECORD_SAMPLES = SAMPLE_RATE * RECORD_SECONDS;

    private TextView statusText, top1Text, top2Text;
    private Button   startListeningButton, stopListeningButton;
    private Button   recordButton, analyzeButton;

    private short[]  recordedClip   = null;
    private boolean  serviceRunning = false;

    private final BroadcastReceiver cryResultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            android.util.Log.d("CryDetection", "Broadcast received in HomeFragment");
            if (!MainActivity.ACTION_CRY_RESULT.equals(intent.getAction())) return;
            String top1Label   = intent.getStringExtra(MainActivity.EXTRA_TOP1_LABEL);
            int    top1Percent = intent.getIntExtra(MainActivity.EXTRA_TOP1_PERCENT, 0);
            String top2Label   = intent.getStringExtra(MainActivity.EXTRA_TOP2_LABEL);
            int    top2Percent = intent.getIntExtra(MainActivity.EXTRA_TOP2_PERCENT, 0);
            showResult(top1Label, top1Percent, top2Label, top2Percent);
            serviceRunning = false;
            updateListeningButtons();
        }
    };

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        statusText           = view.findViewById(R.id.statusText);
        top1Text             = view.findViewById(R.id.topOneResult);
        top2Text             = view.findViewById(R.id.topTwoResult);
        startListeningButton = view.findViewById(R.id.startListeningButton);
        stopListeningButton  = view.findViewById(R.id.stopListeningButton);
        recordButton         = view.findViewById(R.id.recordButton);
        analyzeButton        = view.findViewById(R.id.analyzeButton);

        startListeningButton.setOnClickListener(v -> startDetectionService());
        stopListeningButton.setOnClickListener(v  -> stopDetectionService());
        recordButton.setOnClickListener(v         -> recordManualClip());
        analyzeButton.setOnClickListener(v        -> analyzeManualClip());
        updateListeningButtons();
    }

    @Override
    public void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(MainActivity.ACTION_CRY_RESULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(cryResultReceiver, filter,
                    Context.RECEIVER_EXPORTED);
        } else {
            requireContext().registerReceiver(cryResultReceiver, filter);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        requireContext().unregisterReceiver(cryResultReceiver);
    }

    private void startDetectionService() {
        ContextCompat.startForegroundService(requireContext(),
                new Intent(requireContext(), CryDetectionService.class));
        serviceRunning = true;
        statusText.setText("Listening for baby cry…");
        clearResults();
        updateListeningButtons();
    }

    private void stopDetectionService() {
        requireContext().stopService(new Intent(requireContext(), CryDetectionService.class));
        serviceRunning = false;
        statusText.setText("Detection stopped.");
        updateListeningButtons();
    }

    private void updateListeningButtons() {
        startListeningButton.setEnabled(!serviceRunning);
        stopListeningButton.setEnabled(serviceRunning);
    }

    private void recordManualClip() {
        if (ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(requireContext(),
                    "Microphone permission required", Toast.LENGTH_SHORT).show();
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
                    MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);

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

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    statusText.setText("Recording done. Press Analyse.");
                    recordButton.setEnabled(true);
                    analyzeButton.setEnabled(true);
                });
            }
        }, "record-thread").start();
    }

    private void analyzeManualClip() {
        if (recordedClip == null) {
            Toast.makeText(requireContext(), "Record audio first", Toast.LENGTH_SHORT).show();
            return;
        }

        statusText.setText("Analysing…");
        analyzeButton.setEnabled(false);
        short[] clip = recordedClip;

        new Thread(() -> {
            CryClassifier.PredictionResult result = null;
            try {
                CryClassifier classifier = new CryClassifier(requireContext());
                result = classifier.predict(clip);
            } catch (Exception e) {
                android.util.Log.e("HomeFragment", "Classification error", e);
            }

            final CryClassifier.PredictionResult finalResult = result;
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    analyzeButton.setEnabled(true);
                    if (finalResult != null) {
                        showResult(finalResult.top1Label, finalResult.top1Percent,
                                finalResult.top2Label, finalResult.top2Percent);
                    } else {
                        statusText.setText("Classification failed. Check Logcat.");
                    }
                });
            }
        }, "analyse-thread").start();
    }

    private void showResult(String l1, int p1, String l2, int p2) {
        statusText.setText("Cry classified!");
        top1Text.setText("Most likely: " + l1 + " (" + p1 + "%)");
        top2Text.setText("Second possibility: " + l2 + " (" + p2 + "%)");
    }

    private void clearResults() {
        top1Text.setText("");
        top2Text.setText("");
    }
}

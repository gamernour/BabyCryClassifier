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
import android.widget.LinearLayout;
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

    // Color scale thresholds
    private static final int GREEN_THRESHOLD  = 60;  // >= 60% → green  (very likely)
    private static final int ORANGE_THRESHOLD = 30;  // >= 30% → orange (possible)
    //                                                //  < 30% → red    (low confidence)

    private static final int COLOR_GREEN  = 0xFF16A34A;
    private static final int COLOR_ORANGE = 0xFFF97316;
    private static final int COLOR_RED    = 0xFFDC2626;

    private TextView    statusText, top1Text, top1ConfidenceLabel, top1PercentBadge;
    private TextView    top2Text, top2PercentBadge;
    private View        top1Dot, top2Dot, resultDivider;
    private LinearLayout top2Row;
    private Button      startListeningButton, stopListeningButton;
    private Button      recordButton, analyzeButton;

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
        top1ConfidenceLabel  = view.findViewById(R.id.top1ConfidenceLabel);
        top1PercentBadge     = view.findViewById(R.id.top1PercentBadge);
        top2Text             = view.findViewById(R.id.topTwoResult);
        top2PercentBadge     = view.findViewById(R.id.top2PercentBadge);
        top1Dot              = view.findViewById(R.id.top1Dot);
        top2Dot              = view.findViewById(R.id.top2Dot);
        resultDivider        = view.findViewById(R.id.resultDivider);
        top2Row              = view.findViewById(R.id.top2Row);
        startListeningButton = view.findViewById(R.id.startListeningButton);
        stopListeningButton  = view.findViewById(R.id.stopListeningButton);
        recordButton         = view.findViewById(R.id.recordButton);
        analyzeButton        = view.findViewById(R.id.analyzeButton);

        startListeningButton.setOnClickListener(v -> startDetectionService());
        stopListeningButton.setOnClickListener(v  -> stopDetectionService());
        recordButton.setOnClickListener(v         -> recordManualClip());
        analyzeButton.setOnClickListener(v        -> analyzeManualClip());
        updateListeningButtons();

        view.findViewById(R.id.btnTestAssets).setOnClickListener(v ->
                CryClassifier.testAssets(requireContext()));
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

    // ─── Detection service ────────────────────────────────────────────────────

    private void startDetectionService() {
        ContextCompat.startForegroundService(requireContext(),
                new Intent(requireContext(), CryDetectionService.class));
        serviceRunning = true;
        statusText.setText("Listening…");
        clearResults();
        updateListeningButtons();
    }

    private void stopDetectionService() {
        requireContext().stopService(new Intent(requireContext(), CryDetectionService.class));
        serviceRunning = false;
        statusText.setText("Stopped");
        updateListeningButtons();
    }

    private void updateListeningButtons() {
        startListeningButton.setEnabled(!serviceRunning);
        stopListeningButton.setEnabled(serviceRunning);
        // Active state: teal; inactive: muted grey
        startListeningButton.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                        serviceRunning ? 0xFFCBD5E1 : 0xFF0D9488));
        stopListeningButton.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                        serviceRunning ? 0xFFEF4444 : 0xFFCBD5E1));
    }

    // ─── Manual recording ─────────────────────────────────────────────────────

    private void recordManualClip() {
        if (ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(requireContext(),
                    "Microphone permission required", Toast.LENGTH_SHORT).show();
            return;
        }

        statusText.setText("Recording 3 s…");
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
                    statusText.setText("Done — tap Analyse");
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
                        statusText.setText("Could not classify. Try again.");
                    }
                });
            }
        }, "analyse-thread").start();
    }

    // ─── Result display ───────────────────────────────────────────────────────

    private void showResult(String l1, int p1, String l2, int p2) {
        statusText.setText("Result ready");

        // --- Top 1 ---
        top1Text.setText(l1);
        top1PercentBadge.setText(p1 + "%");

        int color1 = colorForPercent(p1);
        top1Dot.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(color1));
        top1PercentBadge.setTextColor(color1);
        top1ConfidenceLabel.setText(confidenceLabel(p1));
        top1ConfidenceLabel.setTextColor(color1);

        // --- Top 2: only show if percent is meaningful (> 5%) ---
        boolean showTop2 = p2 > 5 && l2 != null && !l2.isEmpty();
        if (showTop2) {
            top2Row.setVisibility(View.VISIBLE);
            resultDivider.setVisibility(View.VISIBLE);
            top2Text.setText(l2);
            top2PercentBadge.setText(p2 + "%");
            int color2 = colorForPercent(p2);
            top2Dot.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(color2));
        } else {
            top2Row.setVisibility(View.GONE);
            resultDivider.setVisibility(View.GONE);
        }
    }


    private String confidenceLabel(int percent) {
        if (percent >= GREEN_THRESHOLD)  return "Very likely";
        if (percent >= ORANGE_THRESHOLD) return "Possible";
        return "Low confidence";
    }

    /** Green ≥ 60%, Orange ≥ 30%, Red < 30% */
    private int colorForPercent(int percent) {
        if (percent >= GREEN_THRESHOLD)  return COLOR_GREEN;
        if (percent >= ORANGE_THRESHOLD) return COLOR_ORANGE;
        return COLOR_RED;
    }

    private void clearResults() {
        top1Text.setText("—");
        top1PercentBadge.setText("");
        top1ConfidenceLabel.setText("");
        top1Dot.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(0xFFCBD5E1));
        top2Row.setVisibility(View.GONE);
        resultDivider.setVisibility(View.GONE);
    }
}

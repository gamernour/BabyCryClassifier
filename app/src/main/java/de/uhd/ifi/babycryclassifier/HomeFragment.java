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

import java.util.HashMap;
import java.util.Map;

public class HomeFragment extends Fragment {

    private static final int SAMPLE_RATE    = 16_000;
    private static final int RECORD_SECONDS = 3;
    private static final int RECORD_SAMPLES = SAMPLE_RATE * RECORD_SECONDS;

    // Voting window: 5 classifications over ~10 seconds
    private static final int VOTING_ROUNDS  = 5;

    private static final int GREEN_THRESHOLD  = 60;
    private static final int ORANGE_THRESHOLD = 30;

    private static final int COLOR_GREEN  = 0xFF16A34A;
    private static final int COLOR_ORANGE = 0xFFF97316;
    private static final int COLOR_RED    = 0xFFDC2626;

    private TextView     statusText, top1Text, top1ConfidenceLabel, top1PercentBadge;
    private TextView     top2Text, top2PercentBadge, recordHintText;
    private View         top1Dot, top2Dot, resultDivider;
    private LinearLayout top2Row;
    private Button       startListeningButton, stopListeningButton, recordButton;

    private boolean serviceRunning = false;
    private boolean isRecording    = false;

    private final BroadcastReceiver cryResultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
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
        recordHintText       = view.findViewById(R.id.recordHintText);
        startListeningButton = view.findViewById(R.id.startListeningButton);
        stopListeningButton  = view.findViewById(R.id.stopListeningButton);
        recordButton         = view.findViewById(R.id.recordButton);

        startListeningButton.setOnClickListener(v -> startDetectionService());
        stopListeningButton.setOnClickListener(v  -> stopDetectionService());
        recordButton.setOnClickListener(v         -> recordAndClassify());

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
        startListeningButton.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                        serviceRunning ? 0xFFCBD5E1 : 0xFF0D9488));
        stopListeningButton.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                        serviceRunning ? 0xFFEF4444 : 0xFFCBD5E1));
    }

    // ─── Manual record + voting window + FlashActivity ────────────────────────

    private void recordAndClassify() {
        if (isRecording) return;

        if (ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(requireContext(),
                    "Microphone permission required", Toast.LENGTH_SHORT).show();
            return;
        }

        isRecording = true;
        recordButton.setEnabled(false);
        recordButton.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(0xFFB91C1C));
        statusText.setText("Listening…");
        recordHintText.setText("Hold near the baby…");
        clearResults();

        new Thread(() -> {
            try {
                CryClassifier classifier = new CryClassifier(requireContext());

                // Voting state
                Map<String, Integer> votes    = new HashMap<>();
                Map<String, Integer> totalPct = new HashMap<>();

                int bufSize = Math.max(
                        AudioRecord.getMinBufferSize(SAMPLE_RATE,
                                AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT),
                        RECORD_SAMPLES * 2);

                AudioRecord recorder = new AudioRecord(
                        MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, bufSize);
                recorder.startRecording();

                for (int round = 1; round <= VOTING_ROUNDS; round++) {
                    final int currentRound = round;

                    // Update status
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() ->
                                statusText.setText("Classifying… (" + currentRound + "/" + VOTING_ROUNDS + ")"));
                    }

                    // Record 3 seconds
                    short[] clip = new short[RECORD_SAMPLES];
                    int pos = 0;
                    while (pos < RECORD_SAMPLES) {
                        int read = recorder.read(clip, pos, RECORD_SAMPLES - pos);
                        if (read > 0) pos += read;
                    }

                    clip = CryDetectionService.rmsNormalize(clip);
                    clip = CryDetectionService.preEmphasis(clip);
                    // Classify
                    // Skip silent clips — don't let them corrupt the vote
                    double rms = 0;
                    for (short s : clip) rms += (double) s * s;
                    rms = Math.sqrt(rms / clip.length);
                    if (rms < 200) {
                        android.util.Log.d("HomeFragment", "Skipping silent clip, RMS=" + rms);
                        continue;
                    }

// Classify
                    CryClassifier.PredictionResult result = classifier.predict(clip);

                    // Update votes
                    votes.merge(result.top1Label, 1, Integer::sum);
                    totalPct.merge(result.top1Label, result.top1Percent, Integer::sum);

                    // Find current winner
                    String winner = votes.entrySet().stream()
                            .max(Map.Entry.comparingByValue())
                            .map(Map.Entry::getKey)
                            .orElse(result.top1Label);
                    int avgPct = totalPct.get(winner) / votes.get(winner);

                    // Show live update in result card
                    final String liveLabel = winner;
                    final int    livePct   = avgPct;
                    final String top2Label = result.top2Label;
                    final int    top2Pct   = result.top2Percent;

                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() ->
                                showResult(liveLabel, livePct, top2Label, top2Pct));
                    }
                }

                recorder.stop();
                recorder.release();

                // Final winner
                String finalWinner = votes.entrySet().stream()
                        .max(Map.Entry.comparingByValue())
                        .map(Map.Entry::getKey)
                        .orElse("");
                int finalPct = totalPct.get(finalWinner) / votes.get(finalWinner);

                // Find second place
                String secondLabel = "";
                int secondPct = 0;
                for (Map.Entry<String, Integer> e : votes.entrySet()) {
                    if (!e.getKey().equals(finalWinner) && e.getValue() > votes.getOrDefault(secondLabel, 0)) {
                        secondLabel = e.getKey();
                        secondPct  = totalPct.get(secondLabel) / e.getValue();
                    }
                }

                final String fWinner      = finalWinner;
                final int    fPct         = finalPct;
                final String fSecondLabel = secondLabel;
                final int    fSecondPct   = secondPct;

                // Save to DB and launch FlashActivity
                CryRepository repo = new CryRepository(requireContext());
                long recordId = repo.insertForId(
                        new CryRecord(System.currentTimeMillis(),
                                fWinner, fPct, fSecondLabel, fSecondPct)).get();

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        isRecording = false;
                        recordButton.setEnabled(true);
                        recordButton.setBackgroundTintList(
                                android.content.res.ColorStateList.valueOf(0xFFEF4444));
                        recordHintText.setText("Tap to record & classify");
                        statusText.setText("Result ready");
                        showResult(fWinner, fPct, fSecondLabel, fSecondPct);

                        // Launch FlashActivity after 1 second
                        recordButton.postDelayed(() -> {
                            Intent flash = new Intent(requireContext(), FlashActivity.class);
                            flash.putExtra(MainActivity.EXTRA_TOP1_LABEL,   fWinner);
                            flash.putExtra(MainActivity.EXTRA_TOP1_PERCENT, fPct);
                            flash.putExtra(MainActivity.EXTRA_TOP2_LABEL,   fSecondLabel);
                            flash.putExtra(MainActivity.EXTRA_TOP2_PERCENT, fSecondPct);
                            flash.putExtra(FlashActivity.EXTRA_RECORD_ID,   (int) recordId);
                            flash.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(flash);
                        }, 1_000);
                    });
                }

            } catch (Exception e) {
                android.util.Log.e("HomeFragment", "recordAndClassify error", e);
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        isRecording = false;
                        recordButton.setEnabled(true);
                        recordButton.setBackgroundTintList(
                                android.content.res.ColorStateList.valueOf(0xFFEF4444));
                        recordHintText.setText("Tap to record & classify");
                        statusText.setText("Could not classify. Try again.");
                    });
                }
            }
        }, "record-classify-thread").start();
    }

    // ─── Result display ───────────────────────────────────────────────────────

    private void showResult(String l1, int p1, String l2, int p2) {
        statusText.setText("Result ready");

        top1Text.setText(l1);
        top1PercentBadge.setText(p1 + "%");

        int color1 = colorForPercent(p1);
        top1Dot.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(color1));
        top1PercentBadge.setTextColor(color1);
        top1ConfidenceLabel.setText(confidenceLabel(p1));
        top1ConfidenceLabel.setTextColor(color1);

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
package de.uhd.ifi.babycryclassifier;

import android.Manifest;
import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class HomeFragment extends Fragment {

    private static final int  SAMPLE_RATE      = 16_000;
    private static final int  RECORD_SECONDS   = 3;
    private static final int  RECORD_SAMPLES   = SAMPLE_RATE * RECORD_SECONDS;
    private static final long OVERLAY_DURATION = 6_000;   // ms to show overlay
    private static final long FEEDBACK_DELAY   = 10 * 1_000;

    private static final int GREEN_THRESHOLD  = 60;
    private static final int ORANGE_THRESHOLD = 30;
    private static final int COLOR_GREEN  = 0xFF16A34A;
    private static final int COLOR_ORANGE = 0xFFF97316;
    private static final int COLOR_RED    = 0xFFDC2626;

    // Class colours — matches FlashActivity
    private static final Object[][] CLASS_CONFIG = {
            { "Belly pain",   "#C0DD97", "#173404", "🤕" },
            { "Need to burp", "#FFB347", "#412402", "🫧" },
            { "Discomfort",   "#5DCAA5", "#04342C", "😣" },
            { "Hunger",       "#F09595", "#501313", "🍼" },
            { "Tiredness",    "#85B7EB", "#042C53", "😴" },
    };

    private TextView     statusText, top1Text, top1ConfidenceLabel, top1PercentBadge;
    private TextView     top2Text, top2PercentBadge, recordHintText;
    private View         top1Dot, top2Dot, resultDivider;
    private LinearLayout top2Row;
    private Button       startListeningButton, stopListeningButton, recordButton;

    // Participant badge
    private LinearLayout participantBadge;
    private TextView     participantLabel;

    // Overlay views
    private LinearLayout flashOverlay, overlayTop, overlayBottom;
    private TextView     overlayTopEmoji, overlayTopLabel, overlayTopPercent;
    private TextView     overlayBottomEmoji, overlayBottomLabel, overlayBottomPercent;
    private TextView     discardButton;

    // Tracks the DB record id and audio path of the current overlay — for discard
    private int    pendingRecordId   = -1;
    private String pendingAudioPath  = null;

    private boolean serviceRunning = false;
    private boolean isRecording    = false;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver cryResultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!MainActivity.ACTION_CRY_RESULT.equals(intent.getAction())) return;
            String top1Label   = intent.getStringExtra(MainActivity.EXTRA_TOP1_LABEL);
            int    top1Percent = intent.getIntExtra(MainActivity.EXTRA_TOP1_PERCENT, 0);
            String top2Label   = intent.getStringExtra(MainActivity.EXTRA_TOP2_LABEL);
            int    top2Percent = intent.getIntExtra(MainActivity.EXTRA_TOP2_PERCENT, 0);
            int    recordId    = intent.getIntExtra(FlashActivity.EXTRA_RECORD_ID, -1);

            showResult(top1Label, top1Percent, top2Label, top2Percent);
            showFlashOverlay(top1Label, top1Percent, top2Label, top2Percent);
            scheduleFeedbackAlarm(top1Label, recordId);
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

        // Overlay
        flashOverlay         = view.findViewById(R.id.flashOverlay);
        overlayTop           = view.findViewById(R.id.overlayTop);
        overlayBottom        = view.findViewById(R.id.overlayBottom);
        overlayTopEmoji      = view.findViewById(R.id.overlayTopEmoji);
        overlayTopLabel      = view.findViewById(R.id.overlayTopLabel);
        overlayTopPercent    = view.findViewById(R.id.overlayTopPercent);
        overlayBottomEmoji   = view.findViewById(R.id.overlayBottomEmoji);
        overlayBottomLabel   = view.findViewById(R.id.overlayBottomLabel);
        overlayBottomPercent = view.findViewById(R.id.overlayBottomPercent);
        discardButton        = view.findViewById(R.id.discardButton);

        discardButton.setOnClickListener(v -> discardCurrentRecording());

        startListeningButton.setOnClickListener(v -> startDetectionService());
        stopListeningButton.setOnClickListener(v  -> stopDetectionService());
        recordButton.setOnClickListener(v         -> recordAndClassify());

        // Participant badge
        participantBadge = view.findViewById(R.id.participantBadge);
        participantLabel = view.findViewById(R.id.participantLabel);
        view.findViewById(R.id.changeParticipantBtn).setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).showParticipantIdDialog(true);
            }
        });
        updateParticipantBadge();

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
        checkPendingFeedback();
    }

    @Override
    public void onPause() {
        super.onPause();
        requireContext().unregisterReceiver(cryResultReceiver);
        handler.removeCallbacksAndMessages(null);
    }

    // ─── Detection service

    private void startDetectionService() {
        ContextCompat.startForegroundService(requireContext(),
                new Intent(requireContext(), CryDetectionService.class));
        serviceRunning = true;
        statusText.setText(getString(R.string.status_listening));
        clearResults();
        updateListeningButtons();
    }

    private void stopDetectionService() {
        requireContext().stopService(new Intent(requireContext(), CryDetectionService.class));
        serviceRunning = false;
        statusText.setText(getString(R.string.status_stopped));
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

    // ─── Record + classify + overlay flash

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
        statusText.setText(getString(R.string.status_recording));
        recordHintText.setText(getString(R.string.hold_near_baby));
        clearResults();

        new Thread(() -> {
            int bufSize = Math.max(
                    AudioRecord.getMinBufferSize(SAMPLE_RATE,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT),
                    RECORD_SAMPLES * 2);

            AudioRecord recorder = new AudioRecord(
                    MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufSize);

            short[] clip = new short[RECORD_SAMPLES];
            int pos = 0;
            recorder.startRecording();
            while (pos < RECORD_SAMPLES) {
                int read = recorder.read(clip, pos, RECORD_SAMPLES - pos);
                if (read > 0) pos += read;
            }
            recorder.stop();
            recorder.release();

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> statusText.setText(getString(R.string.status_analysing)));
            }

            clip = CryDetectionService.rmsNormalize(clip);
            clip = CryDetectionService.preEmphasis(clip);
            final short[] processedClip = clip;

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
                    isRecording = false;
                    recordButton.setEnabled(true);
                    recordButton.setBackgroundTintList(
                            android.content.res.ColorStateList.valueOf(0xFFEF4444));
                    recordHintText.setText(getString(R.string.record_hint));

                    if (finalResult != null) {
                        showResult(finalResult.top1Label, finalResult.top1Percent,
                                finalResult.top2Label, finalResult.top2Percent);
                        showFlashOverlay(finalResult.top1Label, finalResult.top1Percent,
                                finalResult.top2Label, finalResult.top2Percent);

                        // Save to DB, save audio file, schedule feedback
                        new Thread(() -> {
                            try {
                                long timestamp = System.currentTimeMillis();
                                CryRepository repo = new CryRepository(requireContext());
                                SharedPreferences prefs = requireContext()
                                        .getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
                                String participantId = prefs.getString(MainActivity.KEY_PARTICIPANT_ID, "unknown");
                                String babyId = prefs.getString(MainActivity.KEY_BABY_ID, "unknown");
                                long recordId = repo.insertForId(
                                        new CryRecord(timestamp,
                                                finalResult.top1Label, finalResult.top1Percent,
                                                finalResult.top2Label, finalResult.top2Percent,
                                                participantId, babyId)).get();

                                // Save WAV file and store path in DB
                                String audioPath = saveAudioToFile(processedClip, timestamp);
                                if (audioPath != null) {
                                    repo.updateAudioPath((int) recordId, audioPath);
                                }

                                // Store pending info so discard button can clean up
                                if (getActivity() != null) {
                                    final int finalRecordId = (int) recordId;
                                    final String finalAudioPath = audioPath;
                                    getActivity().runOnUiThread(() -> {
                                        pendingRecordId  = finalRecordId;
                                        pendingAudioPath = finalAudioPath;
                                        scheduleFeedbackAlarm(finalResult.top1Label, finalRecordId);
                                    });
                                }
                            } catch (Exception e) {
                                android.util.Log.e("HomeFragment", "DB insert error", e);
                            }
                        }).start();
                    } else {
                        statusText.setText(getString(R.string.status_error));
                    }
                });
            }
        }, "record-classify-thread").start();
    }

    // ─── Flash overlay

    private void showFlashOverlay(String top1Label, int top1Pct,
                                  String top2Label, int top2Pct) {
        Object[] cfg1 = configForLabel(top1Label);
        int bg1   = Color.parseColor((String) cfg1[1]);
        int txt1  = Color.parseColor((String) cfg1[2]);
        String emoji1 = (String) cfg1[3];

        boolean showSplit = top2Pct > 5 && top2Label != null && !top2Label.isEmpty();

        // Set top section
        overlayTop.setBackgroundColor(bg1);
        overlayTopEmoji.setText(emoji1);
        overlayTopLabel.setText(top1Label);
        overlayTopLabel.setTextColor(txt1);
        overlayTopPercent.setText(top1Pct + "%");
        overlayTopPercent.setTextColor(txt1);

        if (showSplit) {
            Object[] cfg2 = configForLabel(top2Label);
            int bg2  = Color.parseColor((String) cfg2[1]);
            int txt2 = Color.parseColor((String) cfg2[2]);
            String emoji2 = (String) cfg2[3];

            overlayBottom.setBackgroundColor(bg2);
            overlayBottomEmoji.setText(emoji2);
            overlayBottomLabel.setText(top2Label);
            overlayBottomLabel.setTextColor(txt2);
            overlayBottomPercent.setText(top2Pct + "%");
            overlayBottomPercent.setTextColor(txt2);

            // Set proportional heights via layout weights
            LinearLayout.LayoutParams topParams =
                    (LinearLayout.LayoutParams) overlayTop.getLayoutParams();
            topParams.weight = top1Pct;
            overlayTop.setLayoutParams(topParams);

            LinearLayout.LayoutParams botParams =
                    (LinearLayout.LayoutParams) overlayBottom.getLayoutParams();
            botParams.weight = top2Pct;
            botParams.height = 0;
            overlayBottom.setLayoutParams(botParams);

            overlayBottom.setVisibility(View.VISIBLE);
        } else {
            // Full screen single color
            LinearLayout.LayoutParams topParams =
                    (LinearLayout.LayoutParams) overlayTop.getLayoutParams();
            topParams.weight = 1;
            overlayTop.setLayoutParams(topParams);

            LinearLayout.LayoutParams botParams =
                    (LinearLayout.LayoutParams) overlayBottom.getLayoutParams();
            botParams.weight = 0;
            botParams.height = 0;
            overlayBottom.setLayoutParams(botParams);

            overlayBottom.setVisibility(View.GONE);
        }

        // Hide bottom nav bar
        if (getActivity() != null) {
            com.google.android.material.bottomnavigation.BottomNavigationView bnv =
                    getActivity().findViewById(R.id.bottomNav);
            if (bnv != null) bnv.setVisibility(View.GONE);
        }

        // Show overlay with fade in
        flashOverlay.setAlpha(0f);
        flashOverlay.setVisibility(View.VISIBLE);
        flashOverlay.animate().alpha(1f).setDuration(200).start();

        handler.postDelayed(() -> {
            if (flashOverlay != null) {
                flashOverlay.animate().alpha(0f).setDuration(300)
                        .withEndAction(() -> {
                            flashOverlay.setVisibility(View.GONE);
                            // Restore bottom nav bar
                            if (getActivity() != null) {
                                com.google.android.material.bottomnavigation.BottomNavigationView bnv =
                                        getActivity().findViewById(R.id.bottomNav);
                                if (bnv != null) bnv.setVisibility(View.VISIBLE);
                            }
                        })
                        .start();
            }
        }, OVERLAY_DURATION);
    }

    // ─── Feedback prompt

    public static final String KEY_PENDING_FEEDBACK_ID    = "pending_feedback_id";
    public static final String KEY_PENDING_FEEDBACK_LABEL = "pending_feedback_label";
    public static final String KEY_PENDING_FEEDBACK_TIME  = "pending_feedback_time";

    private void scheduleFeedbackAlarm(String label, int recordId) {
        long triggerAt = System.currentTimeMillis() + FEEDBACK_DELAY;

        // Store pending feedback in SharedPreferences — survives process kills
        requireContext()
                .getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_PENDING_FEEDBACK_ID, recordId)
                .putString(KEY_PENDING_FEEDBACK_LABEL, label)
                .putLong(KEY_PENDING_FEEDBACK_TIME, triggerAt)
                .apply();

        // System notification backup — fires if app is in background or killed
        Intent alarmIntent = new Intent(requireContext(), FeedbackAlarmReceiver.class);
        alarmIntent.putExtra(MainActivity.EXTRA_TOP1_LABEL, label);
        alarmIntent.putExtra(FlashActivity.EXTRA_RECORD_ID, recordId);

        android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(
                requireContext(),
                recordId == -1 ? 0 : recordId,
                alarmIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);

        AlarmManager am = (AlarmManager) requireContext().getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            }
        }
    }

    /** Called from onResume — launches FeedbackActivity if a pending prompt is due. */
    private void checkPendingFeedback() {
        SharedPreferences prefs = requireContext()
                .getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        long triggerAt = prefs.getLong(KEY_PENDING_FEEDBACK_TIME, -1);
        if (triggerAt == -1) return;
        if (System.currentTimeMillis() < triggerAt) return;

        int    recordId = prefs.getInt(KEY_PENDING_FEEDBACK_ID, -1);
        String label    = prefs.getString(KEY_PENDING_FEEDBACK_LABEL, "Unknown");

        // Clear so it doesn't re-trigger on next resume
        prefs.edit()
                .remove(KEY_PENDING_FEEDBACK_ID)
                .remove(KEY_PENDING_FEEDBACK_LABEL)
                .remove(KEY_PENDING_FEEDBACK_TIME)
                .apply();

        Intent feedbackIntent = new Intent(requireContext(), FeedbackActivity.class);
        feedbackIntent.putExtra(MainActivity.EXTRA_TOP1_LABEL, label);
        feedbackIntent.putExtra(FlashActivity.EXTRA_RECORD_ID, recordId);
        startActivity(feedbackIntent);
    }

    // ─── Discard ──────────────────────────────────────────────────────────────

    private void discardCurrentRecording() {
        handler.removeCallbacksAndMessages(null);

        if (pendingRecordId != -1) {
            Intent alarmIntent = new Intent(requireContext(), FeedbackAlarmReceiver.class);
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(
                    requireContext(), pendingRecordId, alarmIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
            android.app.AlarmManager am = (android.app.AlarmManager)
                    requireContext().getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(pi);
        }

        requireContext().getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_PENDING_FEEDBACK_ID)
                .remove(KEY_PENDING_FEEDBACK_LABEL)
                .remove(KEY_PENDING_FEEDBACK_TIME)
                .apply();

        final int idToDelete      = pendingRecordId;
        final String pathToDelete = pendingAudioPath;
        new Thread(() -> {
            if (idToDelete != -1)   CryRepository.getInstance(requireContext()).deleteById(idToDelete);
            if (pathToDelete != null) { java.io.File f = new java.io.File(pathToDelete); if (f.exists()) f.delete(); }
        }).start();

        pendingRecordId  = -1;
        pendingAudioPath = null;

        if (flashOverlay != null) {
            flashOverlay.animate().alpha(0f).setDuration(200)
                    .withEndAction(() -> {
                        flashOverlay.setVisibility(View.GONE);
                        if (getActivity() != null) {
                            com.google.android.material.bottomnavigation.BottomNavigationView bnv =
                                    getActivity().findViewById(R.id.bottomNav);
                            if (bnv != null) bnv.setVisibility(View.VISIBLE);
                        }
                    }).start();
        }
        android.widget.Toast.makeText(requireContext(),
                getString(R.string.recording_discarded), android.widget.Toast.LENGTH_SHORT).show();
    }

    // ─── Audio saving

    /**
     * Saves a 16-bit PCM short[] as a WAV file in internal storage.
     * Returns the absolute file path, or null if saving failed.
     * Filename pattern: cry_<timestamp>.wav
     */
    private String saveAudioToFile(short[] pcm, long timestamp) {
        File dir = new File(requireContext().getFilesDir(), "cry_recordings");
        if (!dir.exists() && !dir.mkdirs()) return null;

        File file = new File(dir, "cry_" + timestamp + ".wav");
        int dataSize   = pcm.length * 2;          // 2 bytes per short
        int headerSize = 44;
        int totalSize  = headerSize + dataSize;

        try (FileOutputStream fos = new FileOutputStream(file)) {
            ByteBuffer header = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN);
            // RIFF chunk
            header.put(new byte[]{'R','I','F','F'});
            header.putInt(totalSize - 8);
            header.put(new byte[]{'W','A','V','E'});
            // fmt sub-chunk
            header.put(new byte[]{'f','m','t',' '});
            header.putInt(16);           // sub-chunk size
            header.putShort((short) 1);  // PCM format
            header.putShort((short) 1);  // mono
            header.putInt(SAMPLE_RATE);
            header.putInt(SAMPLE_RATE * 2); // byte rate
            header.putShort((short) 2);  // block align
            header.putShort((short) 16); // bits per sample
            // data sub-chunk
            header.put(new byte[]{'d','a','t','a'});
            header.putInt(dataSize);
            fos.write(header.array());

            // Write PCM samples as little-endian bytes
            ByteBuffer samples = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN);
            for (short s : pcm) samples.putShort(s);
            fos.write(samples.array());

            android.util.Log.d("HomeFragment", "Audio saved: " + file.getAbsolutePath());
            return file.getAbsolutePath();
        } catch (IOException e) {
            android.util.Log.e("HomeFragment", "Failed to save audio", e);
            return null;
        }
    }

    // ─── Helpers

    private Object[] configForLabel(String label) {
        for (Object[] row : CLASS_CONFIG) {
            if (((String) row[0]).equalsIgnoreCase(label)) return row;
        }
        return new Object[]{ "Unknown", "#D3D1C7", "#2C2C2A", "❓" };
    }

    // ─── Result display

    private void showResult(String l1, int p1, String l2, int p2) {
        statusText.setText(getString(R.string.status_result_ready));
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
        if (percent >= GREEN_THRESHOLD)  return getString(R.string.conf_very_likely);
        if (percent >= ORANGE_THRESHOLD) return getString(R.string.conf_possible);
        return getString(R.string.conf_low);
    }

    private int colorForPercent(int percent) {
        if (percent >= GREEN_THRESHOLD)  return COLOR_GREEN;
        if (percent >= ORANGE_THRESHOLD) return COLOR_ORANGE;
        return COLOR_RED;
    }

    /** Called by MainActivity after participant ID is set or changed. */
    public void updateParticipantBadge() {
        if (participantBadge == null || participantLabel == null) return;
        SharedPreferences prefs = requireContext()
                .getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        String pid = prefs.getString(MainActivity.KEY_PARTICIPANT_ID, null);
        if (pid != null && !pid.isEmpty()) {
            participantLabel.setText("Session: " + pid);
            participantBadge.setVisibility(View.VISIBLE);
        } else {
            participantBadge.setVisibility(View.GONE);
        }
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
package de.uhd.ifi.babycryclassifier;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import java.util.HashMap;
import java.util.Map;

/**
 * CryDetectionService — continuously listens to the microphone.
 *
 * Detection strategy:
 *   1. Energy gate  — fast pre-filter, skips silent frames
 *   2. ML gate      — binary CryDetector TFLite model
 *   3. Voting window — once cry confirmed, classifies every ~2 seconds
 *                      for 10 seconds, votes on the most frequent result,
 *                      updates UI live after each classification
 *
 *
 */
public class CryDetectionService extends Service {

    private static final String CHANNEL_ID          = "CryDetectionChannel";
    private static final int    NOTIF_ID             = 1;

    private static final int    SAMPLE_RATE          = 16_000;
    private static final int    CLIP_SECONDS         = 3;
    private static final int    CLIP_SAMPLES         = SAMPLE_RATE * CLIP_SECONDS;

    private static final double ENERGY_THRESHOLD     = 500;
    private static final int    REQUIRED_LOUD_CHUNKS = 2;
    private static final int    ML_CHECK_INTERVAL    = 1;

    private static final int    VOTING_WINDOW_MS     = 10_000;
    private static final int    VOTING_INTERVAL_MS   = 2_000;

    private AudioRecord      audioRecord;
    private volatile boolean isListening   = false;
    private volatile boolean isVoting      = false;  // true during voting window

    private final short[] ringBuffer = new short[CLIP_SAMPLES];
    private int     ringIndex        = 0;
    private boolean ringFilled       = false;
    private int     loudChunkCounter = 0;
    private int     mlCheckCounter   = 0;
    private static final int REQUIRED_ML_PASSES = 3;
    private int mlPassCounter = 0;

    private CryDetector           cryDetector;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("Listening for baby crying…"));
        acquireWakeLock();

        try {
            cryDetector = new CryDetector(this);
        } catch (Exception e) {
            android.util.Log.w("CryDetection", "Binary model not found", e);
            cryDetector = null;
        }

        startListening();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopListening();
        releaseWakeLock();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Nullable @Override
    public IBinder onBind(Intent intent) { return null; }

    // Wake lock

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "BabyCryClassifier::DetectionWakeLock");
            wakeLock.acquire();
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }
    }

    // Detection loop

    private void startListening() {
        if (ActivityCompat.checkSelfPermission(this,
                android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return;
        }

        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);

        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufferSize);

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            stopSelf();
            return;
        }

        ringIndex        = 0;
        ringFilled       = false;
        loudChunkCounter = 0;
        mlCheckCounter   = 0;
        isVoting         = false;

        isListening = true;
        audioRecord.startRecording();
        updateNotification("Listening for baby crying…");

        //Recording thread — runs the whole time including during voting
        new Thread(() -> {
            short[] buffer = new short[bufferSize];
            while (isListening) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read <= 0) continue;
                writeToRingBuffer(buffer, read);  // always keep ring buffer fresh

                if (isVoting) continue;  // skip gate checks during voting window

                // Gate 1: energy
                double rms = computeRMS(buffer, read);
                if (rms > ENERGY_THRESHOLD) {
                    loudChunkCounter++;
                    android.util.Log.d("CryDetection",
                            "Loud chunk: " + loudChunkCounter + " RMS: " + rms);
                } else {
                    loudChunkCounter = 0;
                    mlCheckCounter   = 0;
                }

                if (loudChunkCounter < REQUIRED_LOUD_CHUNKS) continue;

                // Gate 2: ML binary model
                mlCheckCounter++;
                if (mlCheckCounter % ML_CHECK_INTERVAL != 0) continue;

                short[] clip = drainRingBuffer();

                if (cryDetector != null) {
                    float   prob  = cryDetector.crySoftmax(clip);
                    boolean isCry = cryDetector.isCry(clip);
                    android.util.Log.d("CryDetection",
                            "ML prob: " + prob + " isCry: " + isCry);
                    if (!isCry) {
                        mlPassCounter = 0;
                        continue;
                    }
                    mlPassCounter++;
                    if (mlPassCounter < REQUIRED_ML_PASSES) continue;
                    mlPassCounter = 0;
// Both gates passed consistently → classify
                    android.util.Log.d("CryDetection", "ML PASSED — starting voting window");
                }

                // Both gates passed — start voting window on separate thread
                // Set isVoting so recording thread keeps filling ring buffer
                // but skips gate checks
                isVoting = true;
                loudChunkCounter = 0;
                mlCheckCounter   = 0;
                final short[] firstClip = clip;
                new Thread(() -> startVotingWindow(firstClip), "voting-thread").start();
            }
        }, "recording-thread").start();
    }

    private void stopListening() {
        isListening = false;
        isVoting    = false;
        if (audioRecord != null) {
            try { audioRecord.stop(); } catch (IllegalStateException ignored) {}
            audioRecord.release();
            audioRecord = null;
        }
    }

    private void resumeListening() {
        stopListening();
        startListening();
    }

    //Voting window

    private void startVotingWindow(short[] firstClip) {
        updateNotification("Cry detected! Analysing…");
        long windowStart = System.currentTimeMillis();
        long detectedAt  = windowStart;

        Map<String, Integer> votes    = new HashMap<>();
        Map<String, Integer> totalPct = new HashMap<>();

        CryClassifier classifier;
        try {
            classifier = new CryClassifier(this);
        } catch (Exception e) {
            android.util.Log.e("CryDetection", "Failed to load classifier", e);
            isVoting = false;
            return;
        }

        // Classify first clip immediately
        classifyAndVote(firstClip, classifier, votes, totalPct);

        // Classify every 2 seconds for 10 second window
        while (System.currentTimeMillis() - windowStart < VOTING_WINDOW_MS) {
            try { Thread.sleep(VOTING_INTERVAL_MS); } catch (InterruptedException ignored) {}
            if (System.currentTimeMillis() - windowStart >= VOTING_WINDOW_MS) break;

            // Ring buffer is still being filled by recording thread
            short[] clip = drainRingBuffer();
            classifyAndVote(clip, classifier, votes, totalPct);
        }

        // Find winner
        String winner     = findWinner(votes);
        int    totalVotes = votes.values().stream().mapToInt(Integer::intValue).sum();
        int    winnerVotes = votes.getOrDefault(winner, 0);
        int    confidence = totalVotes > 0
                ? Math.round((float) winnerVotes / totalVotes * 100) : 0;

        String second    = findSecondPlace(votes, winner);
        int    secondPct = totalVotes > 0
                ? Math.round((float) votes.getOrDefault(second, 0) / totalVotes * 100) : 0;

        android.util.Log.d("CryDetection",
                "Voting done: " + winner + " (" + confidence + "%) from "
                        + totalVotes + " votes");

        // Save to database
        CryRepository.getInstance(getApplicationContext()).insert(
                new CryRecord(detectedAt, winner, confidence, second, secondPct));

        // Final broadcast
        broadcastResult(winner, confidence, second, secondPct);
        updateNotification("Baby is crying: " + winner + " (" + confidence + "%)");

        // Resume detection
        isVoting = false;
    }

    private void classifyAndVote(short[] clip,
                                 CryClassifier classifier,
                                 Map<String, Integer> votes,
                                 Map<String, Integer> totalPct) {
        try {
            CryClassifier.PredictionResult result = classifier.predict(clip);
            votes.merge(result.top1Label, 1, Integer::sum);
            totalPct.merge(result.top1Label, result.top1Percent, Integer::sum);

            String currentLeader = findWinner(votes);
            int    totalVotes    = votes.values().stream().mapToInt(Integer::intValue).sum();
            int    leaderVotes   = votes.get(currentLeader);
            int    leaderPct     = Math.round((float) leaderVotes / totalVotes * 100);
            String second        = findSecondPlace(votes, currentLeader);
            int    secondPct     = totalVotes > 0
                    ? Math.round((float) votes.getOrDefault(second, 0) / totalVotes * 100) : 0;

            android.util.Log.d("CryDetection",
                    "Vote " + totalVotes + ": " + result.top1Label
                            + " → leader: " + currentLeader + " (" + leaderPct + "%)");

            broadcastResult(currentLeader, leaderPct, second, secondPct);

        } catch (Exception e) {
            android.util.Log.e("CryDetection", "Classification failed", e);
        }
        android.util.Log.d("CryDetection", "classifyAndVote called, votes so far: " + votes.size());
        android.util.Log.d("CryDetection", "Voting loop iteration");
    }

    private String findWinner(Map<String, Integer> votes) {
        return votes.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("Unknown");
    }

    private String findSecondPlace(Map<String, Integer> votes, String winner) {
        return votes.entrySet().stream()
                .filter(e -> !e.getKey().equals(winner))
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("Unknown");
    }

    private void broadcastResult(String top1, int pct1, String top2, int pct2) {
        Intent broadcast = new Intent(MainActivity.ACTION_CRY_RESULT);
        broadcast.putExtra(MainActivity.EXTRA_TOP1_LABEL,   top1);
        broadcast.putExtra(MainActivity.EXTRA_TOP1_PERCENT, pct1);
        broadcast.putExtra(MainActivity.EXTRA_TOP2_LABEL,   top2);
        broadcast.putExtra(MainActivity.EXTRA_TOP2_PERCENT, pct2);
        sendBroadcast(broadcast);
    }

    private void writeToRingBuffer(short[] src, int len) {
        for (int i = 0; i < len; i++) {
            ringBuffer[ringIndex] = src[i];
            if (++ringIndex >= CLIP_SAMPLES) { ringIndex = 0; ringFilled = true; }
        }
    }

    private short[] drainRingBuffer() {
        short[] clip = new short[CLIP_SAMPLES];
        if (!ringFilled) {
            System.arraycopy(ringBuffer, 0, clip, 0, ringIndex);
            return clip;
        }
        int pos = 0;
        for (int i = ringIndex; i < CLIP_SAMPLES; i++) clip[pos++] = ringBuffer[i];
        for (int i = 0; i < ringIndex;             i++) clip[pos++] = ringBuffer[i];
        return clip;
    }

    private static double computeRMS(short[] buf, int len) {
        double sum = 0;
        for (int i = 0; i < len; i++) sum += (double) buf[i] * buf[i];
        return Math.sqrt(sum / len);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Cry Detection", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Baby cry detection running in background");
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Intent openApp = new Intent(this, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Baby Cry Classifier")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }
}

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
 * Detection strategy:
 *   1. ML gate      — binary CryDetector TFLite model (3 consecutive passes required)
 *   2. Voting window — once cry confirmed, classifies every 2 seconds
 *                      for 10 seconds, votes on the most frequent result
 */
public class CryDetectionService extends Service {

    private static final String CHANNEL_ID          = "CryDetectionChannel";
    private static final int    NOTIF_ID             = 1;

    private static final int    CLIP_SECONDS         = 3;
    private static final int RECORD_SAMPLE_RATE = 48_000;  // what hardware actually uses
    private static final int MODEL_SAMPLE_RATE  = 16_000;  // what model expects
    private static final int CLIP_SAMPLES       = MODEL_SAMPLE_RATE * CLIP_SECONDS; // 16000 * 3 = 48000 samples at 16kH
    //private static final double ENERGY_THRESHOLD     = 500;
    //private static final int    REQUIRED_LOUD_CHUNKS = 2;
    private static final int    ML_CHECK_INTERVAL    = 1;

    private static final int    VOTING_WINDOW_MS     = 10_000;
    private static final int    VOTING_INTERVAL_MS   = 2_000;

    // ── Normalization constants
    // Target RMS level:  matches approximate RMS of Dunstan training clips.
    // If mic clips are too quiet/loud this brings them to the same level.
    private static final double TARGET_RMS           = 1500.0;
    // Pre-emphasis coefficient:  boosts high frequencies suppressed by mic.
    // Standard value used in speech/audio processing (0.95–0.97).
    private static final float  PRE_EMPHASIS         = 0.97f;

    private AudioRecord      audioRecord;
    private volatile boolean isListening   = false;
    private volatile boolean isVoting      = false;  // true during voting window

    // Ring buffer holds 48kHz samples
    private final short[] ringBuffer = new short[CLIP_SAMPLES * 3]; // 144000 samples at 48kHz
    private int     ringIndex        = 0;
    private boolean ringFilled       = false;
    //private int     loudChunkCounter = 0;
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

        int bufferSize = AudioRecord.getMinBufferSize(RECORD_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);

        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                RECORD_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufferSize);
        android.util.Log.d("SampleRate", "Requested: " + RECORD_SAMPLE_RATE
                + " State: " + audioRecord.getState()
                + " Actual format: " + audioRecord.getAudioFormat()
                + " Actual channel: " + audioRecord.getChannelCount()
                + " Actual rate: " + audioRecord.getSampleRate());

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            stopSelf();
            return;
        }

        ringIndex        = 0;
        ringFilled       = false;
        //loudChunkCounter = 0;
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
               /* double rms = computeRMS(buffer, read);
                if (rms > ENERGY_THRESHOLD) {
                    loudChunkCounter++;
                    android.util.Log.d("CryDetection",
                            "Loud chunk: " + loudChunkCounter + " RMS: " + rms);
                } else {
                    loudChunkCounter = 0;
                    mlCheckCounter   = 0;
                }

                if (loudChunkCounter < REQUIRED_LOUD_CHUNKS) continue;
                */

                // Gate 2: ML binary model
                if (mlCheckCounter % ML_CHECK_INTERVAL != 0) continue;

                short[] clip = drainRingBuffer();

                if (cryDetector != null) {
                    //float   prob  = cryDetector.crySoftmax(clip);
                    //boolean isCry = cryDetector.isCry(clip);

                    float prob = cryDetector.crySoftmax(clip);
                    android.util.Log.d("CryDetector", "cry prob: " + prob + " threshold: 0.4");
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
                //loudChunkCounter = 0;
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
    private static short[] downsampleTo16k(short[] input48k) {
        short[] out = new short[input48k.length / 3];
        for (int i = 0; i < out.length; i++) {
            int sum = input48k[i * 3];
            if (i * 3 + 1 < input48k.length) sum += input48k[i * 3 + 1];
            if (i * 3 + 2 < input48k.length) sum += input48k[i * 3 + 2];
            out[i] = (short)(sum / 3);
        }
        return out;
    }
    // ── Normalization

    /**
     * RMS normalization — scales the clip so its RMS energy matches TARGET_RMS.
     * This compensates for the mic being quieter or louder than the training data.
     */
    public static short[] rmsNormalize(short[] clip) {
        double rms = computeRMS(clip, clip.length);
        if (rms < 1.0) return clip;  // silence — don't amplify noise

        double gain = TARGET_RMS / rms;
        // Cap gain to avoid over-amplifying very quiet non-cry sounds
        gain = Math.min(gain, 10.0);

        short[] out = new short[clip.length];
        for (int i = 0; i < clip.length; i++) {
            out[i] = (short) Math.max(Short.MIN_VALUE,
                    Math.min(Short.MAX_VALUE, (long)(clip[i] * gain)));
        }
        android.util.Log.d("Normalization", "RMS: " + rms + " gain: " + gain);
        return out;
    }

    /**
     * Pre-emphasis filter — boosts high frequencies that the mic tends to suppress.
     * y[n] = x[n] - PRE_EMPHASIS * x[n-1]
     * Standard in speech/audio processing to compensate for mic roll-off.
     */
    public static short[] preEmphasis(short[] clip) {
        short[] out = new short[clip.length];
        out[0] = clip[0];
        for (int i = 1; i < clip.length; i++) {
            float val = clip[i] - PRE_EMPHASIS * clip[i - 1];
            out[i] = (short) Math.max(Short.MIN_VALUE,
                    Math.min(Short.MAX_VALUE, val));
        }
        return out;
    }

    // ── Ring buffer

    private void writeToRingBuffer(short[] src, int len) {
        int ringSize = CLIP_SAMPLES * 3;
        for (int i = 0; i < len; i++) {
            ringBuffer[ringIndex] = src[i];
            if (++ringIndex >= ringSize) { ringIndex = 0; ringFilled = true; }
        }
    }

    /**
     * Drains the ring buffer, downsamples to 16kHz, then applies:
     *   1. RMS normalization
     *   2. Pre-emphasis filter
     * before returning the clip to the classifier.
     */
    private short[] drainRingBuffer() {
        short[] clip48k = new short[CLIP_SAMPLES * 3];
        if (!ringFilled) {
            System.arraycopy(ringBuffer, 0, clip48k, 0, ringIndex);
        } else {
            int pos = 0;
            for (int i = ringIndex; i < clip48k.length; i++) clip48k[pos++] = ringBuffer[i];
            for (int i = 0; i < ringIndex;              i++) clip48k[pos++] = ringBuffer[i];
        }

        // Step 1 — downsample 48kHz → 16kHz
        short[] clip16k = downsampleTo16k(clip48k);

        // Step 2 — RMS normalization
        clip16k = rmsNormalize(clip16k);

        // Step 3 — pre-emphasis
        clip16k = preEmphasis(clip16k);

        android.util.Log.d("RingBuffer", "clip16k length=" + clip16k.length
                + " clip16k[0]=" + clip16k[0]);
        return clip16k;
    }


//Voting window
//   1. insert() now uses insertForId() so we get the database row id back
//   2. After voting ends, launches FlashActivity (full-screen result)
//      instead of only sending a broadcast — the broadcast now goes out
//      from FlashActivity itself so HomeFragment still updates correctly
//   3. The record id is passed through to FlashActivity → FeedbackActivity
//      so the 5-minute feedback answer can be saved to the right row

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
            //isVoting = false;
            return;
        }

        // Classify first clip immediately
        classifyAndVote(firstClip, classifier, votes, totalPct);

        // Classify every 2 seconds for 10-second window
        while (System.currentTimeMillis() - windowStart < VOTING_WINDOW_MS) {
            try { Thread.sleep(VOTING_INTERVAL_MS); } catch (InterruptedException ignored) {}
            if (System.currentTimeMillis() - windowStart >= VOTING_WINDOW_MS) break;
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
                "Voting done: " + winner + " (" + confidence + "%) from " + totalVotes + " votes");

        // ── Save to database and get the row id back
        int recordId = -1;
        try {
            java.util.concurrent.Future<Long> future =
                    CryRepository.getInstance(getApplicationContext())
                            .insertForId(new CryRecord(detectedAt, winner, confidence, second, secondPct,
                                    getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
                                            .getString(MainActivity.KEY_PARTICIPANT_ID, "unknown")));
            recordId = future.get().intValue();   // blocks briefly — we're already on a bg thread
        } catch (Exception e) {
            android.util.Log.e("CryDetection", "DB insert failed", e);
        }

        // ── Broadcast result to HomeFragment for in-screen overlay
        Intent resultIntent = new Intent(MainActivity.ACTION_CRY_RESULT);
        resultIntent.putExtra(MainActivity.EXTRA_TOP1_LABEL,   winner);
        resultIntent.putExtra(MainActivity.EXTRA_TOP1_PERCENT, confidence);
        resultIntent.putExtra(MainActivity.EXTRA_TOP2_LABEL,   second);
        resultIntent.putExtra(MainActivity.EXTRA_TOP2_PERCENT, secondPct);
        resultIntent.putExtra(FlashActivity.EXTRA_RECORD_ID,   recordId);
        sendBroadcast(resultIntent);

        updateNotification("Baby is crying: " + winner + " (" + confidence + "%)");

        // Cooldown — wait 30 seconds before listening for the next cry
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            isVoting = false;
        }, 30_000);
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
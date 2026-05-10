package de.uhd.ifi.babycryclassifier;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.app.PendingIntent;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

/**
 * CryDetectionService — continuously listens to the microphone.
 *
 * Detection strategy (two-gate):
 *   1. Energy gate  — fast, cheap: skip silent frames immediately.
 *   2. ML gate      — CryDetector (binary TFLite model) called on a
 *                     3-second window once energy crosses the threshold.
 *                     Only if the ML model agrees do we escalate to the
 *                     5-class CryClassifier.
 *
 * This avoids running the ML model on every chunk while still being
 * far more robust than a pure energy threshold.
 *
 *  After each cry is detected and classified:
 *    1. Result is saved to the local Room database (CryRepository)
 *    2. Result is broadcast to MainActivity for live UI update
 *    3. Detection loop restarts automatically — no stopSelf()
 *
 *  A partial wake lock prevents Android from killing the service
 *  when the screen turns off.
 */
public class CryDetectionService extends Service {

    private static final String CHANNEL_ID          = "CryDetectionChannel";
    private static final int    NOTIF_ID             = 1;

    private static final int    SAMPLE_RATE          = 16_000;
    private static final int    CLIP_SECONDS         = 3;
    private static final int    CLIP_SAMPLES         = SAMPLE_RATE * CLIP_SECONDS;

    private static final double ENERGY_THRESHOLD        = 500;
    private static final int    REQUIRED_LOUD_CHUNKS = 3;

    // How often to run the ML gate (every N loud windows)
    private static final int    ML_CHECK_INTERVAL  = 1;   // check every loud trigger
    private static final int COOLDOWN_MS = 10_000;  // 10 seconds


    private AudioRecord      audioRecord;
    private volatile boolean isListening = false;

    private final short[] ringBuffer = new short[CLIP_SAMPLES];
    private int     ringIndex        = 0;
    private boolean ringFilled       = false;
    private int     loudChunkCounter = 0;
    private int     mlCheckCounter    = 0;

    private CryDetector cryDetector;   // binary ML gate
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("Listening for baby crying…"));
        acquireWakeLock();

        // Initialise the binary detector once (cheap to keep in memory)
        try {
            cryDetector = new CryDetector(this);
        } catch (Exception e) {
            android.util.Log.w("CryDetection",
                    "Binary model not found — falling back to energy-only gate", e);
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
        // START_STICKY: if Android kills the service, restart it automatically
        return START_STICKY;
    }
    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    //Wake lock
    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "BabyCryClassifier::DetectionWakeLock");
            wakeLock.acquire();  // held until releaseWakeLock()
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }
    }

    //Detection loop
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

        // Reset ring buffer state for fresh detection session
        ringIndex        = 0;
        ringFilled       = false;
        loudChunkCounter = 0;
        mlCheckCounter   = 0;

        isListening = true;
        audioRecord.startRecording();
        updateNotification("Listening for baby crying…");

        new Thread(() -> {
            short[] buffer = new short[bufferSize];
            while (isListening) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read <= 0) continue;

                writeToRingBuffer(buffer, read);

                //Gate 1: energy
                double rms = computeRMS(buffer, read);
                if (rms > ENERGY_THRESHOLD) {
                    loudChunkCounter++;
                    android.util.Log.d("CryDetection", "Loud chunk: " + loudChunkCounter + " RMS: " + rms);
                } else {
                    loudChunkCounter = 0;
                    mlCheckCounter = 0;
                }

                if (loudChunkCounter < REQUIRED_LOUD_CHUNKS) continue;

                //Gate 2: ML binary model
                mlCheckCounter++;
                if (mlCheckCounter % ML_CHECK_INTERVAL != 0) continue;

                short[] clip = drainRingBuffer();

                if (cryDetector != null) {
                    float prob = cryDetector.crySoftmax(clip);
                    boolean isCry = cryDetector.isCry(clip);
                    android.util.Log.d("CryDetection",
                            "ML prob: " + prob + " isCry: " + isCry);

                    if (!isCry) {
                        android.util.Log.d("CryDetection", "ML rejected — resetting");
                        loudChunkCounter = 0;
                        mlCheckCounter = 0;
                        continue;
                    }

                    android.util.Log.d("CryDetection",
                            "ML PASSED — escalating to 5-class classifier");
                }

                // Both gates passed → classify
                onCryDetected(clip);
                break;
            }
        }, "cry-detection-thread").start();
}


    private void stopListening() {
        isListening = false;
        if (audioRecord != null) {
            try { audioRecord.stop(); } catch (IllegalStateException ignored) {}
            audioRecord.release();
            audioRecord = null;
        }
    }

    /**
     * Called after classification completes to restart the detection loop.
     * Recreates AudioRecord so we get a clean recording session.
     */
    private void resumeListening() {
        stopListening();
        updateNotification("Listening for baby crying…");
        new Thread(() -> {
            try {
                Thread.sleep(COOLDOWN_MS);
            } catch (InterruptedException ignored) {}
            if (!isListening) {
                startListening();
            }
        }, "cooldown-thread").start();
    }

    private void onCryDetected(short[] clip) {
        updateNotification("Cry detected! Classifying…");
        long detectedAt = System.currentTimeMillis();

        new Thread(() -> {
            CryClassifier.PredictionResult result = null;
            try {
                CryClassifier classifier = new CryClassifier(this);
                result = classifier.predict(clip);
            } catch (Exception e) {
                android.util.Log.e("CryDetection", "Classification failed", e);
            }

            // Save to local database
            if (result != null) {
                CryRecord record = new CryRecord(
                        detectedAt,
                        result.top1Label,
                        result.top1Percent,
                        result.top2Label,
                        result.top2Percent
                );
                CryRepository.getInstance(getApplicationContext()).insert(record);
            }


            Intent broadcast = new Intent(MainActivity.ACTION_CRY_RESULT);
            if (result != null) {
                broadcast.putExtra(MainActivity.EXTRA_TOP1_LABEL,   result.top1Label);
                broadcast.putExtra(MainActivity.EXTRA_TOP1_PERCENT, result.top1Percent);
                broadcast.putExtra(MainActivity.EXTRA_TOP2_LABEL,   result.top2Label);
                broadcast.putExtra(MainActivity.EXTRA_TOP2_PERCENT, result.top2Percent);
            } else {
                broadcast.putExtra(MainActivity.EXTRA_TOP1_LABEL,   "Unknown");
                broadcast.putExtra(MainActivity.EXTRA_TOP1_PERCENT, 0);
                broadcast.putExtra(MainActivity.EXTRA_TOP2_LABEL,   "Unknown");
                broadcast.putExtra(MainActivity.EXTRA_TOP2_PERCENT, 0);
            }
            sendBroadcast(broadcast);
            android.util.Log.d("CryDetection", "Broadcast sent with result: " +
                    (result != null ? result.top1Label + " " + result.top1Percent : "null"));

            //restart listening - no stopSelf()
            resumeListening();
        }, "classify-thread").start();
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
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Baby Cry Classifier")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pendingIntent)   // tap notification → open app
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)                  // can't be swiped away
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }
}
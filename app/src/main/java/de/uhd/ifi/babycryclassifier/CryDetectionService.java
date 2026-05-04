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

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

public class CryDetectionService extends Service {

    private static final String CHANNEL_ID          = "CryDetectionChannel";
    private static final int    NOTIF_ID             = 1;

    private static final int    SAMPLE_RATE          = 16_000;
    private static final int    CLIP_SECONDS         = 3;
    private static final int    CLIP_SAMPLES         = SAMPLE_RATE * CLIP_SECONDS;

    private static final double CRY_THRESHOLD        = 1_500;
    private static final int    REQUIRED_LOUD_CHUNKS = 4;

    private AudioRecord      audioRecord;
    private volatile boolean isListening = false;

    private final short[] ringBuffer = new short[CLIP_SAMPLES];
    private int     ringIndex        = 0;
    private boolean ringFilled       = false;
    private int     loudChunkCounter = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("Listening for baby crying…"));
        startListening();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopListening();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

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

        isListening = true;
        audioRecord.startRecording();

        new Thread(() -> {
            short[] buffer = new short[bufferSize];
            while (isListening) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read <= 0) continue;

                writeToRingBuffer(buffer, read);

                double rms = computeRMS(buffer, read);
                if (rms > CRY_THRESHOLD) {
                    loudChunkCounter++;
                } else {
                    loudChunkCounter = 0;
                }

                if (loudChunkCounter >= REQUIRED_LOUD_CHUNKS) {
                    short[] clip = drainRingBuffer();
                    onCryDetected(clip);
                    break;
                }
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

    private void onCryDetected(short[] clip) {
        stopListening();
        updateNotification("Cry detected! Classifying…");

        new Thread(() -> {
            CryClassifier.PredictionResult result = null;
            try {
                CryClassifier classifier = new CryClassifier(this);
                result = classifier.predict(clip);
            } catch (Exception e) {
                android.util.Log.e("CryDetection", "Classification failed", e);
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
            stopSelf();
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
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Baby Cry Classifier")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }
}
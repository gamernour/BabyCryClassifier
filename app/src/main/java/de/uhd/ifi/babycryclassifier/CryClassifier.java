package de.uhd.ifi.babycryclassifier;

import android.content.Context;
import android.content.res.AssetFileDescriptor;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;

public class CryClassifier {

    private static final String[] LABELS = {
            "Belly pain",
            "Need to burp",
            "Discomfort",
            "Hunger",
            "Tiredness"
    };

    private static final String[] CODES = {"eairh", "eh", "heh", "neh", "owh"};

    private final Interpreter interpreter;
    private final android.content.Context context;

    public CryClassifier(Context context) throws Exception {
        this.context = context;
        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(2);
        interpreter = new Interpreter(loadModelFile(context), options);

        android.util.Log.d("CryClassifier",
                "Input  shape: " + Arrays.toString(interpreter.getInputTensor(0).shape()));
        android.util.Log.d("CryClassifier",
                "Output shape: " + Arrays.toString(interpreter.getOutputTensor(0).shape()));
    }

    public PredictionResult predict(short[] audioClip) {
        float[][][][] input = AudioPreprocessor.preprocessAudioClip(audioClip);
        float[] probs = runInference(input);
        return buildResult(probs);
    }

    private float[] runInference(float[][][][] input) {
        float[][] output = new float[1][LABELS.length];
        interpreter.run(input, output);
        return output[0];
    }

    private PredictionResult buildResult(float[] probs) {
        int top1 = 0, top2 = 1;
        for (int i = 0; i < probs.length; i++) {
            if (probs[i] > probs[top1]) {
                top2 = top1;
                top1 = i;
            } else if (i != top1 && probs[i] > probs[top2]) {
                top2 = i;
            }
        }

        int pct1 = Math.round(probs[top1] * 100);
        int pct2 = Math.round(probs[top2] * 100);

        android.util.Log.d("CryClassifier",
                "Top-1: " + CODES[top1] + " (" + pct1 + "%)  "
                        + "Top-2: " + CODES[top2] + " (" + pct2 + "%)");

        return new PredictionResult(LABELS[top1], pct1, LABELS[top2], pct2);
    }

    private MappedByteBuffer loadModelFile(Context context) throws Exception {
        AssetFileDescriptor fd =
                context.getAssets().openFd("final_vgg16_mic_finetuned_quant.tflite");
        FileInputStream fis = new FileInputStream(fd.getFileDescriptor());
        FileChannel fc = fis.getChannel();
        MappedByteBuffer buf = fc.map(
                FileChannel.MapMode.READ_ONLY,
                fd.getStartOffset(),
                fd.getDeclaredLength());
        fis.close();
        fd.close();
        return buf;
    }

    // ── Asset test ────────────────────────────────────────────────────────────

    public static void testAssets(Context context) {
        String[] assets = {"belly_pain.wav", "need_to_burp.wav", "discomfort.wav", "hunger.wav", "tiredness.wav"};
        String[] names  = {"eairh (belly pain)", "eh (need to burp)", "heh (discomfort)", "neh (hunger)", "owh (tiredness)"};

        new Thread(() -> {
            try {
                CryClassifier classifier = new CryClassifier(context);
                android.util.Log.d("AssetTest", "=== STARTING ASSET TEST ===");

                for (int i = 0; i < assets.length; i++) {
                    try {
                        short[] pcm = decodeAssetToPcm(context, assets[i]);
                        android.util.Log.d("AssetTest",
                                names[i] + " decoded: " + pcm.length + " samples "
                                        + "(" + (pcm.length / 16000f) + "s at 16kHz)");

                        PredictionResult r = classifier.predict(pcm);
                        android.util.Log.d("AssetTest",
                                "  → Top1: " + r.top1Label + " (" + r.top1Percent + "%)"
                                        + "  Top2: " + r.top2Label + " (" + r.top2Percent + "%)");

                    } catch (Exception e) {
                        android.util.Log.e("AssetTest", names[i] + " FAILED: " + e.getMessage());
                    }
                }
                android.util.Log.d("AssetTest", "=== ASSET TEST DONE ===");

            } catch (Exception e) {
                android.util.Log.e("AssetTest", "Classifier init failed: " + e.getMessage());
            }
        }).start();
    }

    private static short[] decodeAssetToPcm(Context context, String assetName) throws Exception {
        AssetFileDescriptor afd = context.getAssets().openFd(assetName);

        android.media.MediaExtractor extractor = new android.media.MediaExtractor();
        extractor.setDataSource(afd.getFileDescriptor(),
                afd.getStartOffset(), afd.getDeclaredLength());
        extractor.selectTrack(0);

        android.media.MediaFormat format = extractor.getTrackFormat(0);
        String mime = format.getString(android.media.MediaFormat.KEY_MIME);
        int nativeSr = format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE);
        android.util.Log.d("AssetTest", assetName + " native SR: " + nativeSr);

        android.media.MediaCodec codec = android.media.MediaCodec.createDecoderByType(mime);
        codec.configure(format, null, null, 0);
        codec.start();

        java.util.ArrayList<Short> pcmList = new java.util.ArrayList<>();
        android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
        boolean inputDone = false;

        while (true) {
            if (!inputDone) {
                int inIdx = codec.dequeueInputBuffer(10000);
                if (inIdx >= 0) {
                    java.nio.ByteBuffer inBuf = codec.getInputBuffer(inIdx);
                    int sampleSize = extractor.readSampleData(inBuf, 0);
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0,
                                android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        codec.queueInputBuffer(inIdx, 0, sampleSize,
                                extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }
            }

            int outIdx = codec.dequeueOutputBuffer(info, 10000);
            if (outIdx >= 0) {
                java.nio.ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                while (outBuf.remaining() >= 2) {
                    pcmList.add(outBuf.getShort());
                }
                codec.releaseOutputBuffer(outIdx, false);
                if ((info.flags & android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
        }

        codec.stop();
        codec.release();
        extractor.release();
        afd.close();

        int channels = format.containsKey(android.media.MediaFormat.KEY_CHANNEL_COUNT)
                ? format.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT) : 1;
        android.util.Log.d("AssetTest", assetName + " channels: " + channels);

        short[] pcmNative = new short[pcmList.size()];
        for (int i = 0; i < pcmNative.length; i++) pcmNative[i] = pcmList.get(i);

        if (channels == 2) {
            short[] mono = new short[pcmNative.length / 2];
            for (int i = 0; i < mono.length; i++) {
                mono[i] = pcmNative[i * 2];
            }
            pcmNative = mono;
            android.util.Log.d("AssetTest", "Stereo→mono: " + (mono.length * 2) + " → " + mono.length);
        }

        if (nativeSr != 16000) {
            return resampleTo16k(pcmNative, nativeSr);
        }
        return pcmNative;
    }

    private static short[] resampleTo16k(short[] input, int nativeSr) {
        double ratio = (double) nativeSr / 16000.0;
        int outLen = (int)(input.length / ratio);
        short[] out = new short[outLen];
        for (int i = 0; i < outLen; i++) {
            double srcPos = i * ratio;
            int srcIdx = (int) srcPos;
            double frac = srcPos - srcIdx;
            int nextIdx = Math.min(srcIdx + 1, input.length - 1);
            out[i] = (short)(input[srcIdx] * (1.0 - frac) + input[nextIdx] * frac);
        }
        android.util.Log.d("AssetTest", "Resampled " + nativeSr + "Hz→16000Hz: "
                + input.length + " → " + out.length + " samples");
        return out;
    }

    // ── Result ────────────────────────────────────────────────────────────────

    public static class PredictionResult {
        public final String top1Label;
        public final int    top1Percent;
        public final String top2Label;
        public final int    top2Percent;

        PredictionResult(String l1, int p1, String l2, int p2) {
            top1Label   = l1;
            top1Percent = p1;
            top2Label   = l2;
            top2Percent = p2;
        }
    }
}
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

    public CryClassifier(Context context) throws Exception {
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
        float[]       probs = runInference(input);
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
                context.getAssets().openFd("final_vgg16_dynamic_quant.tflite");
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
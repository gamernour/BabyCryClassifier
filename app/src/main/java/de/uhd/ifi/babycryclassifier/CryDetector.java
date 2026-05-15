package de.uhd.ifi.babycryclassifier;

import android.content.Context;
import android.content.res.AssetFileDescriptor;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * CryDetector — lightweight binary cry / no-cry gate.
 *
 * Preprocessing mirrors preprocess_audio.py exactly:
 *   SR = 8 000 Hz, n_fft = 2048, hop = 512, n_mels = 128
 *   audio: z-score normalised
 *   mel:   power → power_to_db(ref=max, top_db=80) → normalise [0,255]
 *   image: bicubic resize to 64×64,  single channel
 *
 * Input  tensor : [1, 64, 64, 1]  float32
 * Output tensor : [1, 1]          float32  (sigmoid — >0.5 means CRY)
 */
public class CryDetector {

    private static final String MODEL_FILE = "cry_detector.tflite";
    private static final float  THRESHOLD  = 0.88f;   // TODO adjust after evaluating on real audio

    // Must match train_cry_detector.py
    private static final int   SR          = 8_000;
    private static final int   CLIP_SAMPLES = SR * 3;  // 24 000
    private static final int   N_FFT       = 2048;
    private static final int   HOP_LENGTH  = 512;
    private static final int   N_MELS      = 128;
    private static final float F_MAX       = SR / 2f;  // 4 000 Hz
    private static final int   IMG_SIZE    = 64;
    private static final float TOP_DB      = 80.0f;

    private static final float[][] MEL_FILTERS = buildMelFilterbank();


    private final Interpreter interpreter;

    public CryDetector(Context context) throws Exception {
        Interpreter.Options opts = new Interpreter.Options();
        opts.setNumThreads(2);
        interpreter = new Interpreter(loadModelFile(context), opts);

    }


    /** Returns true when the audio clip is classified as crying. */
    public boolean isCry(short[] audioClip) {
        return crySoftmax(audioClip) >= THRESHOLD;
    }

    /** Returns sigmoid probability of CRY  [0, 1]. */
    public float crySoftmax(short[] audioClip) {
        // The microphone records at 16 kHz; downsample to 8 kHz first
        short[] downsampled = downsampleBy2(audioClip);
        float[][][][] input  = preprocess(downsampled);
        float[][]     output = new float[1][1];
        interpreter.run(input, output);
        return output[0][0];
    }

    //Downsampling 16 kHz → 8 kHz (simple decimation by 2)
    // For a binary gate this is sufficient; no anti-aliasing filter needed
    // because the mel filterbank already acts as a low-pass.
    private static short[] downsampleBy2(short[] input) {
        short[] out = new short[input.length / 2];
        for (int i = 0; i < out.length; i++) out[i] = input[i * 2];
        return out;
    }

    //Preprocessing

    private static float[][][][] preprocess(short[] raw8k) {
        float[] audio = padOrCrop(raw8k);
        audio = zScore(audio);
        float[] hann = buildHannWindow(N_FFT);
        double[][] mel  = computeMelSpectrogram(audio, hann);
        double[][] db   = powerToDb(mel);
        double[][] norm = normaliseTo255(db);
        double[][] img  = bicubicResize(norm, IMG_SIZE, IMG_SIZE);
        return toTensor(img);
    }

    private static float[] padOrCrop(short[] input) {
        float[] audio = new float[CLIP_SAMPLES];
        int len = input.length;
        if (len < CLIP_SAMPLES) {
            for (int i = 0; i < len; i++) audio[i] = input[i] / 32768.0f;
        } else {
            int start = (len - CLIP_SAMPLES) / 2;
            for (int i = 0; i < CLIP_SAMPLES; i++)
                audio[i] = input[start + i] / 32768.0f;
        }
        return audio;
    }

    /** z-score: (y - mean) / (std + 1e-9)  — matches preprocess_audio.py */
    private static float[] zScore(float[] y) {
        double mean = 0;
        for (float v : y) mean += v;
        mean /= y.length;
        double var = 0;
        for (float v : y) var += (v - mean) * (v - mean);
        float std = (float) Math.sqrt(var / y.length);
        float[] out = new float[y.length];
        for (int i = 0; i < y.length; i++) out[i] = (float)((y[i] - mean) / (std + 1e-9));
        return out;
    }

    private static float[] buildHannWindow(int n) {
        float[] w = new float[n];
        for (int i = 0; i < n; i++)
            w[i] = (float)(0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (n - 1)));
        return w;
    }

    private static double[][] computeMelSpectrogram(float[] audio, float[] hann) {
        int numFrames = 1 + (CLIP_SAMPLES - N_FFT) / HOP_LENGTH;
        int halfFft   = N_FFT / 2 + 1;

        double[][] power = new double[halfFft][numFrames];
        double[] re = new double[N_FFT];
        double[] im = new double[N_FFT];

        for (int t = 0; t < numFrames; t++) {
            int offset = t * HOP_LENGTH;
            for (int n = 0; n < N_FFT; n++) {
                int idx = offset + n;
                re[n] = (idx < audio.length) ? audio[idx] * hann[n] : 0.0;
                im[n] = 0.0;
            }
            fftInPlace(re, im, N_FFT);
            for (int k = 0; k < halfFft; k++)
                power[k][t] = re[k]*re[k] + im[k]*im[k];
        }

        double[][] mel = new double[N_MELS][numFrames];
        for (int m = 0; m < N_MELS; m++)
            for (int t = 0; t < numFrames; t++) {
                double s = 0;
                for (int k = 0; k < halfFft; k++) s += MEL_FILTERS[m][k] * power[k][t];
                mel[m][t] = s;
            }
        return mel;
    }

    /** power_to_db(ref=max, top_db=80) — matches librosa */
    private static double[][] powerToDb(double[][] mel) {
        double maxVal = 1e-10;
        for (double[] row : mel) for (double v : row) if (v > maxVal) maxVal = v;

        int rows = mel.length, cols = mel[0].length;
        double[][] db = new double[rows][cols];
        for (int m = 0; m < rows; m++)
            for (int t = 0; t < cols; t++) {
                db[m][t] = 10.0 * Math.log10(mel[m][t] / maxVal + 1e-10);
                if (db[m][t] < -TOP_DB) db[m][t] = -TOP_DB;
            }
        return db;
    }

    private static double[][] normaliseTo255(double[][] in) {
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (double[] row : in) for (double v : row) {
            if (v < min) min = v; if (v > max) max = v;
        }
        double range = max - min + 1e-8;
        int rows = in.length, cols = in[0].length;
        double[][] out = new double[rows][cols];
        for (int m = 0; m < rows; m++)
            for (int t = 0; t < cols; t++)
                out[m][t] = (in[m][t] - min) / range * 255.0;
        return out;
    }

    private static double[][] bicubicResize(double[][] src, int outH, int outW) {
        int inH = src.length, inW = src[0].length;
        double[][] dst = new double[outH][outW];
        for (int y = 0; y < outH; y++) {
            double fy = (y + 0.5) * inH / outH - 0.5;
            int iy = (int) Math.floor(fy); double dy = fy - iy;
            for (int x = 0; x < outW; x++) {
                double fx = (x + 0.5) * inW / outW - 0.5;
                int ix = (int) Math.floor(fx); double dx = fx - ix;
                double val = 0;
                for (int mm = -1; mm <= 2; mm++) {
                    double wy = cubicW(dy - mm); int sy = clamp(iy+mm, 0, inH-1);
                    for (int nn = -1; nn <= 2; nn++)
                        val += wy * cubicW(dx-nn) * src[sy][clamp(ix+nn, 0, inW-1)];
                }
                dst[y][x] = Math.max(0, Math.min(255, val));
            }
        }
        return dst;
    }

    private static double cubicW(double t) {
        double a = Math.abs(t);
        if (a <= 1) return (1.5*a - 2.5)*a*a + 1;
        if (a <  2) return ((-0.5*a + 2.5)*a - 4)*a + 2;
        return 0;
    }

    /** Single-channel tensor  [1, IMG_SIZE, IMG_SIZE, 1] */
    private static float[][][][] toTensor(double[][] img) {
        float[][][][] t = new float[1][IMG_SIZE][IMG_SIZE][1];
        for (int y = 0; y < IMG_SIZE; y++)
            for (int x = 0; x < IMG_SIZE; x++)
                t[0][y][x][0] = (float) img[y][x];   // already [0,255], no mean-sub
        return t;
    }

    // Mel filterbank (8 kHz)

    private static float[][] buildMelFilterbank() {
        int halfFft = N_FFT / 2 + 1;
        float[][] filters = new float[N_MELS][halfFft];

        double[] fftFreqs = new double[halfFft];
        for (int k = 0; k < halfFft; k++)
            fftFreqs[k] = (double) k * SR / N_FFT;

        double melMin = hzToMel(0), melMax = hzToMel(F_MAX);
        double[] pts = new double[N_MELS + 2];
        for (int i = 0; i < N_MELS + 2; i++)
            pts[i] = melToHz(melMin + i * (melMax - melMin) / (N_MELS + 1));

        for (int m = 0; m < N_MELS; m++) {
            double lo = pts[m], ctr = pts[m+1], hi = pts[m+2];
            for (int k = 0; k < halfFft; k++) {
                double f = fftFreqs[k]; float w = 0;
                if      (f >= lo && f <= ctr) w = (float)((f-lo)/(ctr-lo));
                else if (f >  ctr && f <= hi) w = (float)((hi-f)/(hi-ctr));
                filters[m][k] = w;
            }
        }
        return filters;
    }

    private static double hzToMel(double hz) { return 2595*Math.log10(1+hz/700); }
    private static double melToHz(double mel) { return 700*(Math.pow(10,mel/2595)-1); }

    private static void fftInPlace(double[] re, double[] im, int n) {
        for (int i=1,j=0; i<n; i++) {
            int bit = n>>1;
            for (; (j&bit)!=0; bit>>=1) j^=bit;
            j^=bit;
            if (i<j) { double t=re[i];re[i]=re[j];re[j]=t; t=im[i];im[i]=im[j];im[j]=t; }
        }
        for (int len=2; len<=n; len<<=1) {
            double ang = -2*Math.PI/len, wRe=Math.cos(ang), wIm=Math.sin(ang);
            for (int i=0; i<n; i+=len) {
                double cRe=1, cIm=0;
                for (int j=0; j<len/2; j++) {
                    int u=i+j, v=i+j+len/2;
                    double tRe=cRe*re[v]-cIm*im[v], tIm=cRe*im[v]+cIm*re[v];
                    re[v]=re[u]-tRe; im[v]=im[u]-tIm; re[u]+=tRe; im[u]+=tIm;
                    double nr=cRe*wRe-cIm*wIm; cIm=cRe*wIm+cIm*wRe; cRe=nr;
                }
            }
        }
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo,Math.min(hi,v)); }

    private MappedByteBuffer loadModelFile(Context ctx) throws Exception {
        AssetFileDescriptor fd = ctx.getAssets().openFd(MODEL_FILE);
        FileInputStream fis = new FileInputStream(fd.getFileDescriptor());
        MappedByteBuffer buf = fis.getChannel().map(
                FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength());
        fis.close(); fd.close();
        return buf;
    }

}

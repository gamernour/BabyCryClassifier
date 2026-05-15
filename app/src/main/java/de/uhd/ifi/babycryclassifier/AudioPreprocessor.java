package de.uhd.ifi.babycryclassifier;

/**
 * AudioPreprocessor — matches the Python training pipeline exactly:
 *
 *   1. Load mono PCM at 16 kHz
 *   2. Pad (zeros at end) or center-crop to exactly 3 seconds (48 000 samples)
 *   3. Compute power Mel-spectrogram:
 *        n_fft=1024, hop_length=256, n_mels=128
 *   4. Convert to dB:  10 * log10(mel / max(mel) + 1e-8)   [power_to_db ref=max]
 *   5. Normalise to [0, 255]
 *   6. Bicubic resize to 224×224
 *   7. Convert to RGB (replicate channel)
 *   8. VGG16 preprocess_input: subtract ImageNet BGR means
 *        R-channel: value - 123.68
 *        G-channel: value - 116.779
 *        B-channel: value - 103.939
 *
 * Output tensor shape: [1, 224, 224, 3]  (float32, batch-first)
 */
public class AudioPreprocessor {


    private static final int SAMPLE_RATE   = 16_000;
    private static final float CLIP_SEC    = 3.0f;
    private static final int CLIP_SAMPLES  = (int)(SAMPLE_RATE * CLIP_SEC); // 48 000


    private static final int N_FFT      = 1024;
    private static final int HOP_LENGTH = 256;
    private static final int N_MELS     = 128;
    private static final float F_MIN    = 0f;
    private static final float F_MAX    = SAMPLE_RATE / 2f;   // 8 000 Hz

    private static final int IMAGE_SIZE = 224;

    // Pre-computed Mel filterbank  [N_MELS × (N_FFT/2+1)]
    private static final float[][] MEL_FILTERS = buildMelFilterbank();

    /**
     * @param audioClip  Raw 16-bit PCM samples at 16 kHz (any length).
     * @return           Float32 tensor [1][224][224][3] ready for TFLite.
     */
    public static float[][][][] preprocessAudioClip(short[] audioClip) {
        float[] audio    = padOrCrop(audioClip);
        float[] hann     = buildHannWindow(N_FFT);
        double[][] mel   = computeMelSpectrogram(audio, hann);
        double[][] norm  = normaliseTo255(mel);
        double[][] quant = quantiseToUint8(norm);
        double[][] img   = bicubicResize(quant, IMAGE_SIZE, IMAGE_SIZE); // ← quant not norm
        android.util.Log.d("AudioPreprocessor", "mel range: [" + getMin(mel) + ", " + getMax(mel) + "]");
        android.util.Log.d("AudioPreprocessor", "norm range: [" + getMin(norm) + ", " + getMax(norm) + "]");
        android.util.Log.d("AudioPreprocessor", "img[0][0]=" + img[0][0] + " img[112][112]=" + img[112][112]);
        return toVgg16Input(img);
    }

    private static double getMin(double[][] a) {
        double min = Double.MAX_VALUE;
        for (double[] row : a) for (double v : row) if (v < min) min = v;
        return min;
    }

    private static double getMax(double[][] a) {
        double max = -Double.MAX_VALUE;
        for (double[] row : a) for (double v : row) if (v > max) max = v;
        return max;
    }

    // Matches Python: mel.astype(np.uint8) — truncates to integer values
    private static double[][] quantiseToUint8(double[][] input) {
        int rows = input.length;
        int cols = input[0].length;
        double[][] out = new double[rows][cols];
        for (int m = 0; m < rows; m++)
            for (int t = 0; t < cols; t++)
                out[m][t] = (double)((int) input[m][t]);  // truncate to integer
        return out;
    }
    // Step 1 — pad / center-crop to CLIP_SAMPLES

    private static float[] padOrCrop(short[] input) {
        float[] audio = new float[CLIP_SAMPLES];
        int len = input.length;

        if (len < CLIP_SAMPLES) {
            // Zero-pad at the end  (matches Python: np.pad mode="constant")
            for (int i = 0; i < len; i++) {
                audio[i] = input[i] / 32768.0f;
            }
            // remaining samples are already 0.0f
        } else {
            // Center-crop  (matches Python: start = (len - target) // 2)
            int start = (len - CLIP_SAMPLES) / 2;
            for (int i = 0; i < CLIP_SAMPLES; i++) {
                audio[i] = input[start + i] / 32768.0f;
            }
        }
        return audio;
    }

    // Step 2 — Hann window

    private static float[] buildHannWindow(int n) {
        float[] w = new float[n];
        for (int i = 0; i < n; i++) {
            w[i] = (float)(0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (n - 1)));
        }
        return w;
    }

    // Step 3 — Power Mel-spectrogram
    //   frames = 1 + (CLIP_SAMPLES - N_FFT) / HOP_LENGTH  = 184
    //   output: [N_MELS × frames]

    private static double[][] computeMelSpectrogram(float[] audio, float[] hann) {
        int numFrames = 1 + (CLIP_SAMPLES - N_FFT) / HOP_LENGTH;
        int halfFft   = N_FFT / 2 + 1;

        // Power spectrogram  [halfFft × numFrames]
        double[][] power = new double[halfFft][numFrames];

        double[] frameReal = new double[N_FFT];
        double[] frameImag = new double[N_FFT];

        for (int t = 0; t < numFrames; t++) {
            int offset = t * HOP_LENGTH;

            // Apply window and zero-copy into work arrays
            for (int n = 0; n < N_FFT; n++) {
                frameReal[n] = audio[offset + n] * hann[n];
                frameImag[n] = 0.0;
            }

            // In-place Cooley-Tukey radix-2 DIT FFT
            fftInPlace(frameReal, frameImag, N_FFT);

            double windowSumSq = 383.625;  // sum of squared Hann window values for N_FFT=1024
            for (int k = 0; k < halfFft; k++) {
                power[k][t] = (frameReal[k] * frameReal[k]
                        + frameImag[k] * frameImag[k]) / windowSumSq;
            }
        }

        // Apply Mel filterbank  →  [N_MELS × numFrames]
        double[][] mel = new double[N_MELS][numFrames];
        for (int m = 0; m < N_MELS; m++) {
            for (int t = 0; t < numFrames; t++) {
                double sum = 0.0;
                for (int k = 0; k < halfFft; k++) {
                    sum += MEL_FILTERS[m][k] * power[k][t];
                }
                mel[m][t] = sum;
            }
        }
        return mel;
    }

    // Step 4 — power_to_db(mel, ref=np.max)
    //   db = 10 * log10(mel / ref_max + 1e-8) — then clip to 80 dB dynamic range

    private static double[][] powerToDb(double[][] mel) {
        int rows = mel.length;
        int cols = mel[0].length;

        // Find global max
        double maxVal = 1e-10;
        for (double[] row : mel) {
            for (double v : row) {
                if (v > maxVal) maxVal = v;
            }
        }

        double[][] db = new double[rows][cols];
        for (int m = 0; m < rows; m++) {
            for (int t = 0; t < cols; t++) {
                db[m][t] = 10.0 * Math.log10(mel[m][t] / maxVal + 1e-8);
            }
        }

        // librosa clips to top_db=80 below max (which is 0 dB after ref=max)
        for (int m = 0; m < rows; m++) {
            for (int t = 0; t < cols; t++) {
                if (db[m][t] < -80.0) db[m][t] = -80.0;
            }
        }
        return db;
    }

    // Step 5 — normalise to [0, 255]

    private static double[][] normaliseTo255(double[][] input) {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;

        for (double[] row : input) {
            for (double v : row) {
                if (v < min) min = v;
                if (v > max) max = v;
            }
        }

        double range = max - min + 1e-8;
        int rows = input.length;
        int cols = input[0].length;
        double[][] out = new double[rows][cols];

        for (int m = 0; m < rows; m++) {
            for (int t = 0; t < cols; t++) {
                out[m][t] = (input[m][t] - min) / range * 255.0;
            }
        }
        return out;
    }

    // Step 6 — Bicubic resize  [inH × inW]  →  [outH × outW]

    private static double[][] bicubicResize(double[][] src, int outH, int outW) {
        int inH = src.length;
        int inW = src[0].length;
        double[][] dst = new double[outH][outW];

        for (int y = 0; y < outH; y++) {
            double fy = (y + 0.5) * inH / outH - 0.5;
            int iy = (int) Math.floor(fy);
            double dy = fy - iy;

            for (int x = 0; x < outW; x++) {
                double fx = (x + 0.5) * inW / outW - 0.5;
                int ix = (int) Math.floor(fx);
                double dx = fx - ix;

                double value = 0.0;
                for (int m = -1; m <= 2; m++) {
                    double wy = cubicWeight(dy - m);
                    int sy = clamp(iy + m, 0, inH - 1);
                    for (int n = -1; n <= 2; n++) {
                        double wx = cubicWeight(dx - n);
                        int sx = clamp(ix + n, 0, inW - 1);
                        value += wy * wx * src[sy][sx];
                    }
                }
                dst[y][x] = clampDouble(value, 0.0, 255.0);
            }
        }
        return dst;
    }

    private static double cubicWeight(double t) {
        // Keys cubic kernel  a = -0.75
        double at = Math.abs(t);
        if (at <= 1.0) {
            return (1.5 * at - 2.5) * at * at + 1.0;
        } else if (at < 2.0) {
            return ((-0.5 * at + 2.5) * at - 4.0) * at + 2.0;
        }
        return 0.0;
    }

    // Step 7 & 8 — RGB tensor + VGG16 preprocess_input
    //   preprocess_input converts RGB → BGR then subtracts ImageNet means:
    //     BGR[0] (was R) -= 103.939
    //     BGR[1] (was G) -= 116.779
    //     BGR[2] (was B) -= 123.680
    //   In the stored tensor the channel order is RGB so:
    //     channel 0 (R) -= 123.680
    //     channel 1 (G) -= 116.779
    //     channel 2 (B) -= 103.939

    private static float[][][][] toVgg16Input(double[][] image) {
        float[][][][] input = new float[1][IMAGE_SIZE][IMAGE_SIZE][3];

        for (int y = 0; y < IMAGE_SIZE; y++) {
            for (int x = 0; x < IMAGE_SIZE; x++) {
                float pixel = (float) image[y][x];   // already in [0, 255]
                // Same single-channel value replicated to R, G, B before mean-sub
                input[0][y][x][0] = pixel - 123.680f;  // R
                input[0][y][x][1] = pixel - 116.779f;  // G
                input[0][y][x][2] = pixel - 103.939f;  // B
            }
        }
        return input;
    }

    // Mel filterbank  [N_MELS × (N_FFT/2+1)]  — HTK triangular filters
    // Matches librosa.filters.mel(sr, n_fft, n_mels, fmin, fmax, norm=None)

    private static float[][] buildMelFilterbank() {
        int halfFft = N_FFT / 2 + 1;
        float[][] filters = new float[N_MELS][halfFft];

        // Linear frequency of each FFT bin
        double[] fftFreqs = new double[halfFft];
        for (int k = 0; k < halfFft; k++) {
            fftFreqs[k] = (double) k * SAMPLE_RATE / N_FFT;
        }

        // N_MELS+2 Mel-scale centre frequencies mapped back to Hz
        double melMin = hzToMel(F_MIN);
        double melMax = hzToMel(F_MAX);
        double[] melPoints = new double[N_MELS + 2];
        for (int i = 0; i < N_MELS + 2; i++) {
            melPoints[i] = melToHz(melMin + i * (melMax - melMin) / (N_MELS + 1));
        }

        for (int m = 0; m < N_MELS; m++) {
            double lower  = melPoints[m];
            double center = melPoints[m + 1];
            double upper  = melPoints[m + 2];

            for (int k = 0; k < halfFft; k++) {
                double f = fftFreqs[k];
                float w = 0f;
                if (f >= lower && f <= center) {
                    w = (float)((f - lower) / (center - lower));
                } else if (f > center && f <= upper) {
                    w = (float)((upper - f) / (upper - center));
                }
                filters[m][k] = w;
            }
        }
        return filters;
    }

    private static double hzToMel(double hz) {
        return 2595.0 * Math.log10(1.0 + hz / 700.0);
    }

    private static double melToHz(double mel) {
        return 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0);
    }

    private static void fftInPlace(double[] re, double[] im, int n) {
        // Bit-reversal permutation
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double tmp = re[i]; re[i] = re[j]; re[j] = tmp;
                tmp = im[i]; im[i] = im[j]; im[j] = tmp;
            }
        }
        // Butterfly passes
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2.0 * Math.PI / len;
            double wRe = Math.cos(angle);
            double wIm = Math.sin(angle);
            for (int i = 0; i < n; i += len) {
                double curRe = 1.0, curIm = 0.0;
                for (int j = 0; j < len / 2; j++) {
                    int u = i + j;
                    int v = i + j + len / 2;
                    double tRe = curRe * re[v] - curIm * im[v];
                    double tIm = curRe * im[v] + curIm * re[v];
                    re[v] = re[u] - tRe;  im[v] = im[u] - tIm;
                    re[u] += tRe;          im[u] += tIm;
                    double nextRe = curRe * wRe - curIm * wIm;
                    curIm = curRe * wIm + curIm * wRe;
                    curRe = nextRe;
                }
            }
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double clampDouble(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
//Only needed to send audio to the server, not needed anymore
/*
package de.uhd.ifi.babycryclassifier;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * WavEncoder
 *
 * Converts raw 16-bit PCM samples (from AudioRecord) into a valid WAV file
 * byte array that can be sent to the Flask API.
 *
 * WAV format:
 *   RIFF header (44 bytes) + raw PCM data
 */
/*public class WavEncoder {

    public static byte[] encode(short[] pcm, int sampleRate) throws IOException {
        int numSamples   = pcm.length;
        int numChannels  = 1;           // mono
        int bitsPerSample = 16;
        int byteRate     = sampleRate * numChannels * bitsPerSample / 8;
        int blockAlign   = numChannels * bitsPerSample / 8;
        int dataSize     = numSamples * blockAlign;
        int chunkSize    = 36 + dataSize;

        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + dataSize);

        // ── RIFF chunk descriptor ────────────────────────────────────────────
        out.write("RIFF".getBytes());
        out.write(intToBytes(chunkSize));
        out.write("WAVE".getBytes());

        // ── fmt sub-chunk ────────────────────────────────────────────────────
        out.write("fmt ".getBytes());
        out.write(intToBytes(16));              // sub-chunk size (PCM = 16)
        out.write(shortToBytes((short) 1));     // audio format  (PCM = 1)
        out.write(shortToBytes((short) numChannels));
        out.write(intToBytes(sampleRate));
        out.write(intToBytes(byteRate));
        out.write(shortToBytes((short) blockAlign));
        out.write(shortToBytes((short) bitsPerSample));

        // ── data sub-chunk ───────────────────────────────────────────────────
        out.write("data".getBytes());
        out.write(intToBytes(dataSize));

        // PCM samples — little-endian 16-bit
        ByteBuffer buf = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN);
        for (short s : pcm) buf.putShort(s);
        out.write(buf.array());

        return out.toByteArray();
    }

    private static byte[] intToBytes(int v) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array();
    }

    private static byte[] shortToBytes(short v) {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v).array();
    }
}
*/
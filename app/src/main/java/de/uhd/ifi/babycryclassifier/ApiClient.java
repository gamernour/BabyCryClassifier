//API client not needed anymore, from when I was running the server online
/*package de.uhd.ifi.babycryclassifier;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * ApiClient
 *
 * Sends a WAV file to the CryHop Flask API and returns a PredictionResult.
 *
 * Usage (on a background thread):
 *   ApiClient.PredictionResult result = ApiClient.classify(wavBytes);
 */
/* public class ApiClient {

    private static final String SERVER_URL = "https://cryhop-api.onrender.com";

    private static final String ENDPOINT       = SERVER_URL + "/classify";
    private static final String BOUNDARY       = "----CryHopBoundary";
    private static final int    TIMEOUT_MS     = 120_000;

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

    /**
     * Sends WAV bytes to the server and returns top-2 predictions.
     * Must be called from a background thread.
     *
     * @param wavBytes  WAV file as byte array (from WavEncoder)
     * @return          PredictionResult, or null on error
     */
   /* public static PredictionResult classify(byte[] wavBytes) {
        try {
            // Build multipart/form-data body
            String boundaryLine = "--" + BOUNDARY;
            String crlf         = "\r\n";

            byte[] header = (
                    boundaryLine + crlf
                            + "Content-Disposition: form-data; name=\"audio\"; filename=\"cry.wav\"" + crlf
                            + "Content-Type: audio/wav" + crlf
                            + crlf
            ).getBytes(StandardCharsets.UTF_8);

            byte[] footer = (crlf + boundaryLine + "--" + crlf)
                    .getBytes(StandardCharsets.UTF_8);

            // Open connection
            HttpURLConnection conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Content-Type",
                    "multipart/form-data; boundary=" + BOUNDARY);
            conn.setRequestProperty("Content-Length",
                    String.valueOf(header.length + wavBytes.length + footer.length));

            // Write body
            try (OutputStream os = conn.getOutputStream()) {
                os.write(header);
                os.write(wavBytes);
                os.write(footer);
                os.flush();
            }

            // Read response
            int status = conn.getResponseCode();
            if (status != 200) {
                android.util.Log.e("ApiClient", "Server returned HTTP " + status);
                return null;
            }

            byte[] responseBytes = conn.getInputStream().readAllBytes();
            String responseJson  = new String(responseBytes, StandardCharsets.UTF_8);
            android.util.Log.d("ApiClient", "Response: " + responseJson);

            // Parse JSON
            JSONObject json = new JSONObject(responseJson);
            return new PredictionResult(
                    json.getString("top1_label"),
                    json.getInt("top1_percent"),
                    json.getString("top2_label"),
                    json.getInt("top2_percent")
            );

        } catch (Exception e) {
            android.util.Log.e("ApiClient", "classify() failed", e);
            return null;
        }
    }
} */

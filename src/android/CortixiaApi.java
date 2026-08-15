package dz.cortixia.kyc;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * Cortixia KYC REST client — the single place that talks to
 * https://www.e-kyc.online/api/sdk/v1/*.
 *
 * All auth (X-API-Key), error translation and JSON handling live here so the
 * plugin's flow code and the two host platforms behave identically. A failure
 * is always a {@link CortixiaException} carrying a stable code and a French
 * message fit to show a user — never a raw HTTP status.
 *
 * Uses HttpURLConnection (no third-party HTTP dependency) to keep the plugin's
 * footprint minimal for host builds like OutSystems.
 */
class CortixiaApi {

    private final String baseUrl;
    private final String apiToken;

    static final String SDK_VERSION = "cordova-0.1.3";

    CortixiaApi(String baseUrl, String apiToken) {
        // Trim a trailing slash so path concatenation is predictable.
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiToken = apiToken;
    }

    /** A typed failure with a code and a user-facing French message. */
    static class CortixiaException extends Exception {
        final String code;
        CortixiaException(String code, String message) {
            super(message);
            this.code = code;
        }
        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("code", code);
                o.put("message", getMessage());
            } catch (JSONException ignored) { }
            return o;
        }
    }

    static String newSessionId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 32);
    }

    // -- endpoints -----------------------------------------------------------

    JSONObject init(String platform) throws CortixiaException {
        JSONObject body = new JSONObject();
        try {
            body.put("sdk_version", SDK_VERSION);
            body.put("platform", platform);
        } catch (JSONException e) { throw wrap(e); }
        return postJson("/api/sdk/v1/init", body);
    }

    void event(String eventType, String documentType, String sessionId, boolean success) {
        // Telemetry is best-effort — never fails a flow.
        try {
            JSONObject body = new JSONObject();
            body.put("event_type", eventType);
            body.put("document_type", documentType == null ? "" : documentType);
            body.put("session_id", sessionId == null ? "" : sessionId);
            body.put("success", success);
            body.put("sdk_version", SDK_VERSION);
            body.put("platform", "android");
            postJson("/api/sdk/v1/events", body);
        } catch (Exception ignored) { }
    }

    /** Validate MRZ lines. Returns the parsed fields + mrz_keys (BAC inputs). */
    JSONObject mrz(String documentType, JSONArray lines, String sessionId) throws CortixiaException {
        JSONObject body = new JSONObject();
        try {
            body.put("document_type", documentType);
            body.put("lines", lines);
            body.put("session_id", sessionId == null ? "" : sessionId);
            body.put("sdk_version", SDK_VERSION);
            body.put("platform", "android");
        } catch (JSONException e) { throw wrap(e); }
        return postJson("/api/sdk/v1/mrz", body);
    }

    /** Decode raw chip datagroups (base64). {@code datagroups} maps key→raw bytes. */
    JSONObject decode(String documentType, Map<String, byte[]> datagroups, String sessionId)
            throws CortixiaException {
        JSONObject dg = new JSONObject();
        try {
            for (Map.Entry<String, byte[]> e : datagroups.entrySet()) {
                if (e.getValue() != null && e.getValue().length > 0) {
                    dg.put(e.getKey(), Base64.encodeToString(e.getValue(), Base64.NO_WRAP));
                }
            }
            JSONObject body = new JSONObject();
            body.put("document_type", documentType);
            body.put("datagroups", dg);
            body.put("session_id", sessionId == null ? "" : sessionId);
            body.put("sdk_version", SDK_VERSION);
            body.put("platform", "android");
            return postJson("/api/sdk/v1/decode", body);
        } catch (JSONException e) { throw wrap(e); }
    }

    /** Liveness. Multipart: face (jpg bytes) + video (mp4 bytes). */
    JSONObject liveness(byte[] faceJpeg, byte[] videoMp4, String sessionId) throws CortixiaException {
        String boundary = "----cortixia" + System.nanoTime();
        try {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            writeField(buf, boundary, "question", "neutral");
            writeField(buf, boundary, "session_id", sessionId == null ? "" : sessionId);
            writeField(buf, boundary, "sdk_version", SDK_VERSION);
            writeField(buf, boundary, "platform", "android");
            writeFile(buf, boundary, "face", "face.jpg", "image/jpeg", faceJpeg);
            writeFile(buf, boundary, "video", "video.mp4", "video/mp4", videoMp4);
            buf.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return postMultipart("/api/sdk/v1/liveness", boundary, buf.toByteArray());
        } catch (IOException e) {
            throw new CortixiaException("network_error",
                    "Connexion au service impossible. Vérifiez votre réseau et réessayez.");
        }
    }

    // -- transport -----------------------------------------------------------

    private JSONObject postJson(String path, JSONObject body) throws CortixiaException {
        return send(path, "application/json", body.toString().getBytes(StandardCharsets.UTF_8), null);
    }

    private JSONObject postMultipart(String path, String boundary, byte[] payload)
            throws CortixiaException {
        return send(path, "multipart/form-data; boundary=" + boundary, payload, null);
    }

    private JSONObject send(String path, String contentType, byte[] payload, Void unused)
            throws CortixiaException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(baseUrl + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(90000);
            conn.setDoOutput(true);
            conn.setRequestProperty("X-API-Key", apiToken);
            conn.setRequestProperty("Content-Type", contentType);
            conn.setRequestProperty("Accept", "application/json");

            OutputStream os = conn.getOutputStream();
            os.write(payload);
            os.flush();
            os.close();

            int status = conn.getResponseCode();
            String responseBody = readBody(status < 400 ? conn.getInputStream() : conn.getErrorStream());

            if (status >= 200 && status < 300) {
                return responseBody.isEmpty() ? new JSONObject() : new JSONObject(responseBody);
            }
            throw errorFrom(status, responseBody);
        } catch (IOException e) {
            throw new CortixiaException("network_error",
                    "Connexion au service impossible. Vérifiez votre réseau et réessayez.");
        } catch (JSONException e) {
            throw new CortixiaException("bad_response",
                    "Réponse inattendue du service. Réessayez plus tard.");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Map an HTTP error to a typed, user-facing failure. Always prefers the
     * server's French {@code message} over the raw status — the Flutter run's
     * lesson (a bare "HTTP 502" must never reach a user).
     */
    private CortixiaException errorFrom(int status, String responseBody) {
        String error = "";
        String message = "";
        try {
            JSONObject json = new JSONObject(responseBody);
            error = json.optString("error", "");
            message = json.optString("message", "");
        } catch (JSONException ignored) { }

        if (message.isEmpty()) {
            switch (status) {
                case 401: message = "Jeton API invalide."; error = "invalid_token"; break;
                case 402: message = "Abonnement ou quota insuffisant."; break;
                case 502: message = "Service momentanément indisponible. Réessayez.";
                          error = "liveness_unavailable"; break;
                default:  message = "Le service a refusé la demande. Réessayez plus tard.";
            }
        }
        if (error.isEmpty()) error = "http_" + status;
        return new CortixiaException(error, message);
    }

    // -- helpers -------------------------------------------------------------

    private static String readBody(InputStream in) throws IOException {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        return sb.toString();
    }

    private static void writeField(java.io.ByteArrayOutputStream buf, String boundary,
                                   String name, String value) throws IOException {
        buf.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        buf.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        buf.write(value.getBytes(StandardCharsets.UTF_8));
        buf.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void writeFile(java.io.ByteArrayOutputStream buf, String boundary, String name,
                                  String filename, String mime, byte[] data) throws IOException {
        buf.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        buf.write(("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n")
                .getBytes(StandardCharsets.UTF_8));
        buf.write(("Content-Type: " + mime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        buf.write(data);
        buf.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static CortixiaException wrap(JSONException e) {
        return new CortixiaException("client_error", "Erreur interne du plugin.");
    }
}

package dz.cortixia.kyc;

import android.content.Intent;
import android.content.pm.PackageManager;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Cortixia KYC — Android Cordova plugin entry point.
 *
 * Java (not Kotlin) on purpose: this plugin is imported into arbitrary host
 * builds — including the OutSystems build pipeline — where Kotlin support
 * cannot be assumed. Java compiles with zero host config.
 *
 * Phase 1 wires {@code initialize} (token validation via the REST client) and
 * declares the guided-flow methods. The MRZ and liveness camera Activities are
 * added next; {@code scanIdCard}/{@code scanPassport} compose MRZ → NFC →
 * liveness once those land.
 */
public class CortixiaKycPlugin extends CordovaPlugin {

    public static final String PLUGIN_VERSION = "0.1.0";
    private static final String DEFAULT_BASE_URL = "https://www.e-kyc.online";

    private static final int REQ_MRZ = 5001;
    private static final int REQ_LIVENESS = 5002;
    private static final int REQ_NFC = 5003;

    private CortixiaApi api;
    private String apiToken;
    private String baseUrl = DEFAULT_BASE_URL;

    // In-flight guided-flow state (one flow at a time).
    private CallbackContext pendingCallback;
    private String pendingDocType;

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callback)
            throws JSONException {
        switch (action) {
            case "ping":
                ping(callback);
                return true;
            case "initialize":
                initialize(args.optJSONObject(0), callback);
                return true;
            case "scanMrz":
                scanMrz(args.optString(0, "idcard"), callback);
                return true;
            case "readChip":
                readChip(args.optJSONObject(0), callback);
                return true;
            case "checkLiveness":
                checkLiveness(callback);
                return true;
            case "scanIdCard":
            case "scanPassport":
                // Composed MRZ→NFC→liveness flow — arrives with Phase 2 (NFC).
                callback.error(notImplemented(action));
                return true;
            default:
                return false;
        }
    }

    /** Launch the guided liveness capture, then verify server-side. */
    private void checkLiveness(CallbackContext callback) {
        if (api == null) {
            callback.error(err("not_initialized", "Appelez initialize() avant la vérification."));
            return;
        }
        pendingCallback = callback;
        Intent intent = new Intent(cordova.getActivity(), LivenessActivity.class);
        cordova.setActivityResultCallback(this);
        cordova.getActivity().startActivityForResult(intent, REQ_LIVENESS);
    }

    /** Launch the guided NFC read, then decode the datagroups server-side. */
    private void readChip(JSONObject options, CallbackContext callback) {
        if (api == null) {
            callback.error(err("not_initialized", "Appelez initialize() avant la lecture NFC."));
            return;
        }
        JSONObject keys = options != null ? options.optJSONObject("mrzKeys") : null;
        String docNumber = keys != null ? keys.optString("document_number", "") : "";
        String dob = keys != null ? keys.optString("birth_date", "") : "";
        String doe = keys != null ? keys.optString("expiry_date", "") : "";
        if (docNumber.isEmpty() || dob.isEmpty() || doe.isEmpty()) {
            callback.error(err("bad_config",
                    "readChip() nécessite mrzKeys (document_number, birth_date, expiry_date) issus de scanMrz()."));
            return;
        }
        pendingCallback = callback;
        pendingDocType = options.optString("documentType", "idcard");

        Intent intent = new Intent(cordova.getActivity(), NfcReadActivity.class);
        intent.putExtra(NfcReadActivity.EXTRA_DOC_TYPE, pendingDocType);
        intent.putExtra(NfcReadActivity.EXTRA_DOC_NUMBER, docNumber);
        intent.putExtra(NfcReadActivity.EXTRA_DOB, dob);
        intent.putExtra(NfcReadActivity.EXTRA_DOE, doe);
        cordova.setActivityResultCallback(this);
        cordova.getActivity().startActivityForResult(intent, REQ_NFC);
    }

    /** Launch the guided MRZ scanner, then validate the lines server-side. */
    private void scanMrz(String documentType, CallbackContext callback) {
        if (api == null) {
            callback.error(err("not_initialized", "Appelez initialize() avant de scanner."));
            return;
        }
        pendingCallback = callback;
        pendingDocType = documentType;
        Intent intent = new Intent(cordova.getActivity(), MrzScanActivity.class);
        intent.putExtra(MrzScanActivity.EXTRA_DOC_TYPE, documentType);
        cordova.setActivityResultCallback(this);
        cordova.getActivity().startActivityForResult(intent, REQ_MRZ);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (pendingCallback == null) return;
        final CallbackContext callback = pendingCallback;
        pendingCallback = null;
        boolean ok = resultCode == android.app.Activity.RESULT_OK && data != null;
        String message = data != null ? data.getStringExtra("message") : null;

        if (requestCode == REQ_MRZ) {
            if (!ok) {
                callback.error(err("cancelled", message != null ? message : "Lecture MRZ annulée."));
                return;
            }
            handleMrzResult(data, callback);
        } else if (requestCode == REQ_LIVENESS) {
            if (!ok) {
                callback.error(err("cancelled", message != null ? message : "Vérification annulée."));
                return;
            }
            handleLivenessResult(data, callback);
        } else if (requestCode == REQ_NFC) {
            if (!ok) {
                String code = data != null ? data.getStringExtra("code") : null;
                callback.error(err(code != null ? code : "cancelled",
                        message != null ? message : "Lecture NFC annulée."));
                return;
            }
            handleNfcResult(data, callback);
        }
    }

    private void handleNfcResult(Intent data, CallbackContext callback) {
        final String dg2 = data.getStringExtra(NfcReadActivity.EXTRA_DG2_PATH);
        final String dg7 = data.getStringExtra(NfcReadActivity.EXTRA_DG7_PATH);
        final String dg11 = data.getStringExtra(NfcReadActivity.EXTRA_DG11_PATH);
        final String dg12 = data.getStringExtra(NfcReadActivity.EXTRA_DG12_PATH);
        final String docType = pendingDocType;
        cordova.getThreadPool().execute(() -> {
            java.util.List<java.io.File> temp = new java.util.ArrayList<>();
            try {
                java.util.Map<String, byte[]> dgs = new java.util.LinkedHashMap<>();
                putDg(dgs, temp, "dg2", dg2);
                putDg(dgs, temp, "dg7", dg7);
                putDg(dgs, temp, "dg11", dg11);
                putDg(dgs, temp, "dg12", dg12);
                if (dgs.isEmpty()) {
                    callback.error(err("nfc_read_failed", "Aucune donnée lue sur la puce. Réessayez."));
                    return;
                }
                callback.success(api.decode(docType, dgs, CortixiaApi.newSessionId()));
            } catch (CortixiaApi.CortixiaException e) {
                callback.error(e.toJson());
            } finally {
                // Raw chip datagroups never outlive the decode call on the device.
                for (java.io.File f : temp) f.delete();
            }
        });
    }

    private void putDg(java.util.Map<String, byte[]> dgs, java.util.List<java.io.File> temp,
                       String key, String path) {
        if (path == null) return;
        java.io.File f = new java.io.File(path);
        temp.add(f);
        byte[] bytes = readFile(f);
        if (bytes != null && bytes.length > 0) dgs.put(key, bytes);
    }

    private void handleMrzResult(Intent data, CallbackContext callback) {
        String[] lines = data.getStringArrayExtra(MrzScanActivity.EXTRA_LINES);
        if (lines == null || lines.length == 0) {
            callback.error(err("no_mrz", "Aucune MRZ détectée."));
            return;
        }
        final JSONArray lineArr = new JSONArray();
        for (String l : lines) lineArr.put(l);
        final String docType = pendingDocType;
        cordova.getThreadPool().execute(() -> {
            try {
                callback.success(api.mrz(docType, lineArr, CortixiaApi.newSessionId()));
            } catch (CortixiaApi.CortixiaException e) {
                callback.error(e.toJson());
            }
        });
    }

    private void handleLivenessResult(Intent data, CallbackContext callback) {
        final String videoPath = data.getStringExtra(LivenessActivity.EXTRA_VIDEO_PATH);
        final String facePath = data.getStringExtra(LivenessActivity.EXTRA_FACE_PATH);
        cordova.getThreadPool().execute(() -> {
            java.io.File video = videoPath != null ? new java.io.File(videoPath) : null;
            java.io.File face = facePath != null ? new java.io.File(facePath) : null;
            try {
                byte[] faceBytes = readFile(face);
                byte[] videoBytes = readFile(video);
                if (faceBytes == null || videoBytes == null) {
                    callback.error(err("capture_failed", "Capture incomplète. Réessayez."));
                    return;
                }
                callback.success(api.liveness(faceBytes, videoBytes, CortixiaApi.newSessionId()));
            } catch (CortixiaApi.CortixiaException e) {
                callback.error(e.toJson());
            } finally {
                // Subject media is never kept on the device longer than the call.
                if (video != null) video.delete();
                if (face != null) face.delete();
            }
        });
    }

    private static byte[] readFile(java.io.File f) {
        if (f == null || !f.exists()) return null;
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) out.write(chunk, 0, n);
            in.close();
            return out.toByteArray();
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private void ping(CallbackContext callback) throws JSONException {
        JSONObject info = new JSONObject();
        info.put("plugin", "cordova-plugin-cortixia-kyc");
        info.put("version", PLUGIN_VERSION);
        info.put("platform", "android");
        info.put("nfcAvailable", hasNfc());
        info.put("initialized", api != null);
        callback.success(info);
    }

    /**
     * Configure the plugin and validate the token against the licensing server.
     * Runs the network call off the WebView thread; resolves with the license
     * / entitlements payload or rejects with a typed French error.
     */
    private void initialize(JSONObject config, CallbackContext callback) {
        if (config == null || config.optString("apiToken").isEmpty()) {
            callback.error(err("bad_config", "initialize() nécessite { apiToken }."));
            return;
        }
        apiToken = config.optString("apiToken");
        baseUrl = config.optString("baseUrl", DEFAULT_BASE_URL);
        api = new CortixiaApi(baseUrl, apiToken);

        cordova.getThreadPool().execute(() -> {
            try {
                JSONObject license = api.init("android");
                callback.success(license);
            } catch (CortixiaApi.CortixiaException e) {
                // A bad token leaves the plugin unconfigured so a later scan
                // fails fast rather than silently reusing a rejected token.
                api = null;
                callback.error(e.toJson());
            }
        });
    }

    private boolean hasNfc() {
        PackageManager pm = cordova.getActivity().getPackageManager();
        return pm.hasSystemFeature(PackageManager.FEATURE_NFC);
    }

    private JSONObject notImplemented(String action) {
        return err("not_implemented",
                "« " + action + " » sera disponible dans une prochaine phase du plugin.");
    }

    private JSONObject err(String code, String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("code", code);
            o.put("message", message);
        } catch (JSONException ignored) { }
        return o;
    }
}

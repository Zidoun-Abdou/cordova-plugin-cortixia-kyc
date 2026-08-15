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

    public static final String PLUGIN_VERSION = "0.1.2";
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

    // Composed scanIdCard/scanPassport state machine: MRZ → NFC → liveness.
    // When composedFlow is true the step handlers advance to the next screen
    // instead of resolving; the accumulated pieces become one KycResult.
    private boolean composedFlow = false;
    private JSONObject flowMrz;
    private JSONObject flowDecoded;

    private void resetFlow() {
        composedFlow = false;
        flowMrz = null;
        flowDecoded = null;
    }

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
                scanDocument("idcard", callback);
                return true;
            case "scanPassport":
                scanDocument("passport", callback);
                return true;
            default:
                return false;
        }
    }

    /**
     * Full guided flow: MRZ scan → NFC chip read → liveness → one KycResult.
     * Each step's server call (mrz/decode/liveness) is billed as before; a
     * cancel or failure at any step ends the flow with a typed French error.
     */
    private void scanDocument(String documentType, CallbackContext callback) {
        if (api == null) {
            callback.error(err("not_initialized", "Appelez initialize() avant de scanner."));
            return;
        }
        resetFlow();
        composedFlow = true;
        launchMrz(documentType, callback);
    }

    // -- UI-thread activity launchers (used by the composed flow) ------------

    private void launchMrz(String documentType, CallbackContext callback) {
        cordova.getActivity().runOnUiThread(() -> {
            pendingCallback = callback;
            pendingDocType = documentType;
            Intent intent = new Intent(cordova.getActivity(), MrzScanActivity.class);
            intent.putExtra(MrzScanActivity.EXTRA_DOC_TYPE, documentType);
            cordova.setActivityResultCallback(this);
            cordova.getActivity().startActivityForResult(intent, REQ_MRZ);
        });
    }

    private void launchNfc(String documentType, String docNumber, String dob, String doe,
                           CallbackContext callback) {
        cordova.getActivity().runOnUiThread(() -> {
            pendingCallback = callback;
            pendingDocType = documentType;
            Intent intent = new Intent(cordova.getActivity(), NfcReadActivity.class);
            intent.putExtra(NfcReadActivity.EXTRA_DOC_TYPE, documentType);
            intent.putExtra(NfcReadActivity.EXTRA_DOC_NUMBER, docNumber);
            intent.putExtra(NfcReadActivity.EXTRA_DOB, dob);
            intent.putExtra(NfcReadActivity.EXTRA_DOE, doe);
            cordova.setActivityResultCallback(this);
            cordova.getActivity().startActivityForResult(intent, REQ_NFC);
        });
    }

    private void launchLiveness(CallbackContext callback) {
        cordova.getActivity().runOnUiThread(() -> {
            pendingCallback = callback;
            Intent intent = new Intent(cordova.getActivity(), LivenessActivity.class);
            cordova.setActivityResultCallback(this);
            cordova.getActivity().startActivityForResult(intent, REQ_LIVENESS);
        });
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
                resetFlow();
                callback.error(err("cancelled", message != null ? message : "Lecture MRZ annulée."));
                return;
            }
            handleMrzResult(data, callback);
        } else if (requestCode == REQ_LIVENESS) {
            if (!ok) {
                resetFlow();
                callback.error(err("cancelled", message != null ? message : "Vérification annulée."));
                return;
            }
            handleLivenessResult(data, callback);
        } else if (requestCode == REQ_NFC) {
            if (!ok) {
                resetFlow();
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
        final boolean flow = composedFlow;
        cordova.getThreadPool().execute(() -> {
            java.util.List<java.io.File> temp = new java.util.ArrayList<>();
            try {
                java.util.Map<String, byte[]> dgs = new java.util.LinkedHashMap<>();
                putDg(dgs, temp, "dg2", dg2);
                putDg(dgs, temp, "dg7", dg7);
                putDg(dgs, temp, "dg11", dg11);
                putDg(dgs, temp, "dg12", dg12);
                if (dgs.isEmpty()) {
                    if (flow) resetFlow();
                    callback.error(err("nfc_read_failed", "Aucune donnée lue sur la puce. Réessayez."));
                    return;
                }
                JSONObject decoded = api.decode(docType, dgs, CortixiaApi.newSessionId());
                if (!flow) {
                    callback.success(decoded);
                    return;
                }
                // Composed flow: carry the decoded identity forward to liveness.
                flowDecoded = decoded;
                launchLiveness(callback);
            } catch (CortixiaApi.CortixiaException e) {
                if (flow) resetFlow();
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
        final boolean flow = composedFlow;
        cordova.getThreadPool().execute(() -> {
            try {
                JSONObject mrzResult = api.mrz(docType, lineArr, CortixiaApi.newSessionId());
                if (!flow) {
                    callback.success(mrzResult);
                    return;
                }
                // Composed flow: carry the fields forward, use the BAC keys for NFC.
                flowMrz = mrzResult;
                JSONObject keys = mrzResult.optJSONObject("mrz_keys");
                String docNumber = keys != null ? keys.optString("document_number", "") : "";
                String dob = keys != null ? keys.optString("birth_date", "") : "";
                String doe = keys != null ? keys.optString("expiry_date", "") : "";
                if (docNumber.isEmpty() || dob.isEmpty() || doe.isEmpty()) {
                    resetFlow();
                    callback.error(err("invalid_mrz",
                            "MRZ illisible pour la lecture de la puce. Réessayez."));
                    return;
                }
                launchNfc(docType, docNumber, dob, doe, callback);
            } catch (CortixiaApi.CortixiaException e) {
                resetFlow();
                callback.error(e.toJson());
            }
        });
    }

    private void handleLivenessResult(Intent data, CallbackContext callback) {
        final String videoPath = data.getStringExtra(LivenessActivity.EXTRA_VIDEO_PATH);
        final String facePath = data.getStringExtra(LivenessActivity.EXTRA_FACE_PATH);
        final boolean flow = composedFlow;
        cordova.getThreadPool().execute(() -> {
            java.io.File video = videoPath != null ? new java.io.File(videoPath) : null;
            java.io.File face = facePath != null ? new java.io.File(facePath) : null;
            try {
                // In the composed flow the reference face is the chip portrait
                // (DG2) — the real identity check is "live selfie == chip photo".
                // Standalone liveness uses the selfie's own frame (PAD demo).
                byte[] faceBytes = flow ? chipPortraitBytes() : null;
                if (faceBytes == null) faceBytes = readFile(face);
                byte[] videoBytes = readFile(video);
                if (faceBytes == null || videoBytes == null) {
                    if (flow) resetFlow();
                    callback.error(err("capture_failed", "Capture incomplète. Réessayez."));
                    return;
                }
                JSONObject liveness = api.liveness(faceBytes, videoBytes, CortixiaApi.newSessionId());
                if (!flow) {
                    callback.success(liveness);
                    return;
                }
                callback.success(buildKycResult(liveness));
            } catch (CortixiaApi.CortixiaException e) {
                if (flow) resetFlow();
                callback.error(e.toJson());
            } finally {
                // Subject media is never kept on the device longer than the call.
                if (video != null) video.delete();
                if (face != null) face.delete();
            }
        });
    }

    /** The chip portrait (DG2) as JPEG bytes, from the decode response, or null. */
    private byte[] chipPortraitBytes() {
        try {
            if (flowDecoded == null) return null;
            JSONObject decoded = flowDecoded.optJSONObject("decoded");
            JSONObject dg2 = decoded != null ? decoded.optJSONObject("dg2") : null;
            String b64 = dg2 != null ? dg2.optString("face", "") : "";
            if (b64.isEmpty()) return null;
            return android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
        } catch (Exception e) {
            return null;
        }
    }

    /** Assemble the composed-flow result and clear the state machine. */
    private JSONObject buildKycResult(JSONObject liveness) {
        JSONObject result = new JSONObject();
        try {
            result.put("status", "success");
            result.put("document_type", pendingDocType);
            // MRZ-parsed fields (surname, document_number, dates, …).
            result.put("mrz", flowMrz != null ? flowMrz.opt("fields") : null);
            // Server-decoded chip identity (portrait, DG11/DG12 fields).
            result.put("decoded", flowDecoded);
            // Liveness decision + FaceGuard block.
            result.put("liveness", liveness);
        } catch (JSONException ignored) { }
        resetFlow();
        return result;
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

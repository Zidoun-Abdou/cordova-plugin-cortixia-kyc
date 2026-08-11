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
            case "scanIdCard":
            case "scanPassport":
            case "checkLiveness":
                // Full composed flows / liveness — next Phase 1/2 steps.
                callback.error(notImplemented(action));
                return true;
            default:
                return false;
        }
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
        if (requestCode != REQ_MRZ || pendingCallback == null) return;
        final CallbackContext callback = pendingCallback;
        pendingCallback = null;

        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            String message = data != null ? data.getStringExtra("message") : null;
            callback.error(err("cancelled",
                    message != null ? message : "Lecture MRZ annulée."));
            return;
        }
        String[] lines = data.getStringArrayExtra(MrzScanActivity.EXTRA_LINES);
        if (lines == null || lines.length == 0) {
            callback.error(err("no_mrz", "Aucune MRZ détectée."));
            return;
        }
        final JSONArray lineArr = new JSONArray();
        for (String l : lines) lineArr.put(l);
        final String docType = pendingDocType;

        // Validate server-side off the WebView thread.
        cordova.getThreadPool().execute(() -> {
            try {
                JSONObject result = api.mrz(docType, lineArr, CortixiaApi.newSessionId());
                callback.success(result);
            } catch (CortixiaApi.CortixiaException e) {
                callback.error(e.toJson());
            }
        });
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

package dz.cortixia.kyc;

import android.content.pm.PackageManager;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Cortixia KYC — Android Cordova plugin entry point.
 *
 * Written in Java (not Kotlin) on purpose: this plugin is imported into
 * arbitrary host builds — including the OutSystems build pipeline — where
 * Kotlin support cannot be assumed. Java compiles with zero host config.
 *
 * Phase 0: `ping` proves the JS↔native bridge and the packaging. The scan
 * methods are declared so the JS surface is stable, returning a structured
 * "not implemented yet" error until Phase 1/2 wire the guided flows + REST.
 */
public class CortixiaKycPlugin extends CordovaPlugin {

    public static final String PLUGIN_VERSION = "0.1.0";

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callback)
            throws JSONException {
        switch (action) {
            case "ping": {
                JSONObject info = new JSONObject();
                info.put("plugin", "cordova-plugin-cortixia-kyc");
                info.put("version", PLUGIN_VERSION);
                info.put("platform", "android");
                info.put("nfcAvailable", hasNfc());
                callback.success(info);
                return true;
            }
            case "initialize":
            case "scanIdCard":
            case "scanPassport":
            case "scanMrz":
            case "checkLiveness":
                // Declared now so the contract is stable; implemented in later phases.
                callback.error(notImplemented(action));
                return true;
            default:
                return false; // unknown action → Cordova reports it to JS
        }
    }

    private boolean hasNfc() {
        PackageManager pm = cordova.getActivity().getPackageManager();
        return pm.hasSystemFeature(PackageManager.FEATURE_NFC);
    }

    private JSONObject notImplemented(String action) throws JSONException {
        JSONObject err = new JSONObject();
        err.put("code", "not_implemented");
        err.put("message", "« " + action + " » sera disponible dans une prochaine phase du plugin.");
        return err;
    }
}

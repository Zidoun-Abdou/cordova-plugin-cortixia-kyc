/**
 * Cortixia KYC — Cordova JS interface.
 *
 * Promise-based API mirroring the Flutter SDK's surface so the result shape is
 * identical across platforms:
 *
 *   await cortixiaKyc.initialize({ apiToken: 'ck_live_...' });
 *   const result = await cortixiaKyc.scanIdCard();   // guided native flow
 *   if (result.status === 'success') { ... result.personal / .document / .liveness }
 *
 * Every method delegates to the native side (Android/iOS) via cordova.exec.
 * The native layer owns the scanning UI and the REST calls, so this file is a
 * thin, stable bridge.
 */
var exec = require('cordova/exec');

var SERVICE = 'CortixiaKyc';

function call(action, args) {
  return new Promise(function (resolve, reject) {
    exec(resolve, reject, SERVICE, action, args || []);
  });
}

var cortixiaKyc = {
  /**
   * Configure and validate the API token against the licensing server.
   * @param {{apiToken: string, baseUrl?: string, debugMode?: boolean}} config
   * @returns {Promise<object>} license/entitlements info
   */
  initialize: function (config) {
    if (!config || !config.apiToken) {
      return Promise.reject(new Error('initialize() requires { apiToken }'));
    }
    return call('initialize', [config]);
  },

  /** Full guided flow for the ID card: MRZ → NFC → liveness → result. */
  scanIdCard: function (options) {
    return call('scanIdCard', [options || {}]);
  },

  /** Full guided flow for the passport. */
  scanPassport: function (options) {
    return call('scanPassport', [options || {}]);
  },

  /** MRZ read only (the MRZ pack). Returns the parsed fields + BAC keys. */
  scanMrz: function (documentType, options) {
    return call('scanMrz', [documentType, options || {}]);
  },

  /**
   * NFC chip read only (the NFC pack). Reads the eMRTD chip using the BAC keys
   * from a prior scanMrz() and returns the server-decoded identity.
   * @param {{documentType: 'idcard'|'passport', mrzKeys: {document_number, birth_date, expiry_date}}} options
   * @returns {Promise<object>} decoded identity map
   */
  readChip: function (options) {
    return call('readChip', [options || {}]);
  },

  /** Liveness only, against a reference face you supply (base64 JPEG). */
  checkLiveness: function (options) {
    return call('checkLiveness', [options || {}]);
  },

  /** Plugin + native health probe (Phase 0). Returns a version string. */
  ping: function () {
    return call('ping', []);
  },
};

module.exports = cortixiaKyc;

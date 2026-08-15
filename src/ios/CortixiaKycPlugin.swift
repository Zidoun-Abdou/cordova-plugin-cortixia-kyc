import Foundation
import AVFoundation

//
// Cortixia KYC — iOS Cordova plugin entry point.
//
// Phase 3a wires initialize (token validation via the REST client), the guided
// MRZ scanner (Vision) and liveness (AVFoundation). scanIdCard/scanPassport and
// readChip land in Phase 3b with the CoreNFC eMRTD read; they report
// not_implemented until then so the JS surface matches Android throughout.
//
@objc(CortixiaKycPlugin)
class CortixiaKycPlugin: CDVPlugin {

    static let pluginVersion = "0.1.3"
    private static let defaultBaseUrl = "https://www.e-kyc.online"

    private var api: CortixiaApi?
    // Retains the in-flight NFC reader for the duration of a chip read.
    private var nfcReader: AnyObject?

    // -- ping ----------------------------------------------------------------

    @objc(ping:)
    func ping(_ command: CDVInvokedUrlCommand) {
        let info: [String: Any] = [
            "plugin": "cordova-plugin-cortixia-kyc",
            "version": CortixiaKycPlugin.pluginVersion,
            "platform": "ios",
            "nfcAvailable": nfcAvailable(),
            "initialized": api != nil,
        ]
        commandDelegate.send(CDVPluginResult(status: .ok, messageAs: info), callbackId: command.callbackId)
    }

    // -- initialize ----------------------------------------------------------

    @objc(initialize:)
    func initialize(_ command: CDVInvokedUrlCommand) {
        guard let config = command.argument(at: 0) as? [String: Any],
              let token = config["apiToken"] as? String, !token.isEmpty else {
            reject(command, "bad_config", "initialize() nécessite { apiToken }.")
            return
        }
        let baseUrl = (config["baseUrl"] as? String) ?? CortixiaKycPlugin.defaultBaseUrl
        let client = CortixiaApi(baseUrl: baseUrl, apiToken: token)
        client.initSession(platform: "ios") { [weak self] result in
            guard let self = self else { return }
            switch result {
            case .success(let license):
                self.api = client
                self.resolve(command, license)
            case .failure(let e):
                // Bad token leaves the plugin unconfigured so a later scan fails fast.
                self.api = nil
                self.reject(command, e)
            }
        }
    }

    // -- scanMrz -------------------------------------------------------------

    @objc(scanMrz:)
    func scanMrz(_ command: CDVInvokedUrlCommand) {
        guard let api = api else {
            reject(command, "not_initialized", "Appelez initialize() avant de scanner."); return
        }
        let docType = (command.argument(at: 0) as? String) ?? "idcard"
        withCameraPermission(command) {
            let vc = MrzScanViewController()
            vc.documentType = docType
            vc.modalPresentationStyle = .fullScreen
            vc.onResult = { [weak self] lines in
                guard let self = self else { return }
                guard let lines = lines, !lines.isEmpty else {
                    self.reject(command, "cancelled", "Lecture MRZ annulée."); return
                }
                api.mrz(documentType: docType, lines: lines, sessionId: CortixiaApi.newSessionId()) { r in
                    self.deliver(command, r)
                }
            }
            self.present(vc)
        }
    }

    // -- checkLiveness -------------------------------------------------------

    @objc(checkLiveness:)
    func checkLiveness(_ command: CDVInvokedUrlCommand) {
        guard let api = api else {
            reject(command, "not_initialized", "Appelez initialize() avant la vérification."); return
        }
        withCameraPermission(command) {
            let vc = LivenessViewController()
            vc.modalPresentationStyle = .fullScreen
            vc.onResult = { [weak self] captured in
                guard let self = self else { return }
                guard let captured = captured else {
                    self.reject(command, "cancelled", "Vérification annulée."); return
                }
                api.liveness(face: captured.face, video: captured.video,
                             sessionId: CortixiaApi.newSessionId()) { r in
                    self.deliver(command, r)
                }
            }
            self.present(vc)
        }
    }

    // -- NFC + composed flows ------------------------------------------------

    @objc(scanIdCard:) func scanIdCard(_ c: CDVInvokedUrlCommand) { scanDocument("idcard", c) }
    @objc(scanPassport:) func scanPassport(_ c: CDVInvokedUrlCommand) { scanDocument("passport", c) }

    /// NFC chip read only, using the BAC keys from a prior scanMrz().
    @objc(readChip:)
    func readChip(_ command: CDVInvokedUrlCommand) {
        guard let api = api else {
            reject(command, "not_initialized", "Appelez initialize() avant la lecture NFC."); return
        }
        let options = command.argument(at: 0) as? [String: Any]
        guard let keys = bacKeys(options?["mrzKeys"] as? [String: Any]) else {
            reject(command, "bad_config",
                   "readChip() nécessite mrzKeys (document_number, birth_date, expiry_date) issus de scanMrz().")
            return
        }
        let docType = (options?["documentType"] as? String) ?? "idcard"
        readChipThenDecode(command, docType: docType, keys: keys, api: api) { [weak self] decoded in
            self?.resolve(command, decoded)
        }
    }

    /// Full guided flow: MRZ → NFC chip → liveness → one KycResult.
    private func scanDocument(_ docType: String, _ command: CDVInvokedUrlCommand) {
        guard let api = api else {
            reject(command, "not_initialized", "Appelez initialize() avant de scanner."); return
        }
        withCameraPermission(command) {
            let vc = MrzScanViewController()
            vc.documentType = docType
            vc.modalPresentationStyle = .fullScreen
            vc.onResult = { [weak self] lines in
                guard let self = self else { return }
                guard let lines = lines, !lines.isEmpty else {
                    self.reject(command, "cancelled", "Lecture MRZ annulée."); return
                }
                api.mrz(documentType: docType, lines: lines, sessionId: CortixiaApi.newSessionId()) { r in
                    switch r {
                    case .failure(let e): self.reject(command, e)
                    case .success(let mrz):
                        guard let keys = self.bacKeys(mrz["mrz_keys"] as? [String: Any]) else {
                            self.reject(command, "invalid_mrz",
                                        "MRZ illisible pour la lecture de la puce. Réessayez."); return
                        }
                        self.readChipThenDecode(command, docType: docType, keys: keys, api: api) { decoded in
                            self.runComposedLiveness(command, docType: docType, mrz: mrz, decoded: decoded, api: api)
                        }
                    }
                }
            }
            self.present(vc)
        }
    }

    /// Read the chip, decode server-side, then hand the decoded map to `onDecoded`.
    private func readChipThenDecode(_ command: CDVInvokedUrlCommand, docType: String,
                                    keys: (String, String, String), api: CortixiaApi,
                                    onDecoded: @escaping ([String: Any]) -> Void) {
        let reader = EMRTDReader(docNumber: keys.0, dob: keys.1, doe: keys.2)
        nfcReader = reader
        reader.read { [weak self] result in
            guard let self = self else { return }
            self.nfcReader = nil
            switch result {
            case .failure(let e): self.reject(command, e)
            case .success(let dgs):
                api.decode(documentType: docType, datagroups: dgs, sessionId: CortixiaApi.newSessionId()) { r in
                    switch r {
                    case .failure(let e): self.reject(command, e)
                    case .success(let decoded): onDecoded(decoded)
                    }
                }
            }
        }
    }

    /// Liveness step of the composed flow — reference face is the chip portrait.
    private func runComposedLiveness(_ command: CDVInvokedUrlCommand, docType: String,
                                     mrz: [String: Any], decoded: [String: Any], api: CortixiaApi) {
        DispatchQueue.main.async {
            let vc = LivenessViewController()
            vc.modalPresentationStyle = .fullScreen
            vc.onResult = { [weak self] captured in
                guard let self = self else { return }
                guard let captured = captured else {
                    self.reject(command, "cancelled", "Vérification annulée."); return
                }
                let face = self.chipPortrait(decoded) ?? captured.face
                api.liveness(face: face, video: captured.video, sessionId: CortixiaApi.newSessionId()) { r in
                    switch r {
                    case .failure(let e): self.reject(command, e)
                    case .success(let liveness):
                        var result: [String: Any] = [
                            "status": "success", "document_type": docType,
                            "decoded": decoded, "liveness": liveness,
                        ]
                        if let fields = mrz["fields"] { result["mrz"] = fields }
                        self.resolve(command, result)
                    }
                }
            }
            self.present(vc)
        }
    }

    /// (document_number, birth_date, expiry_date) from an mrz_keys map, if complete.
    private func bacKeys(_ keys: [String: Any]?) -> (String, String, String)? {
        guard let keys = keys,
              let d = keys["document_number"] as? String, !d.isEmpty,
              let b = keys["birth_date"] as? String, !b.isEmpty,
              let e = keys["expiry_date"] as? String, !e.isEmpty else { return nil }
        return (d, b, e)
    }

    /// The chip portrait (DG2) JPEG from the decode response, or nil.
    private func chipPortrait(_ decoded: [String: Any]) -> Data? {
        guard let inner = decoded["decoded"] as? [String: Any],
              let dg2 = inner["dg2"] as? [String: Any],
              let b64 = dg2["face"] as? String,
              let data = Data(base64Encoded: b64) else { return nil }
        return data
    }

    // -- helpers -------------------------------------------------------------

    private func nfcAvailable() -> Bool {
        // Phase 3a ships MRZ + liveness only; real CoreNFC availability
        // detection arrives with the Phase 3b chip read (which also adds the
        // CoreNFC framework + NFC entitlement).
        return false
    }

    /// Request camera access (once), then run the block on the main thread — or
    /// reject with a French message if the user has denied the camera.
    private func withCameraPermission(_ command: CDVInvokedUrlCommand, _ block: @escaping () -> Void) {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            DispatchQueue.main.async(execute: block)
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { granted in
                if granted { DispatchQueue.main.async(execute: block) }
                else { self.reject(command, "camera_denied", "Autorisation caméra refusée.") }
            }
        default:
            reject(command, "camera_denied", "Autorisation caméra refusée. Activez-la dans les réglages.")
        }
    }

    private func present(_ vc: UIViewController) {
        self.viewController.present(vc, animated: true)
    }

    private func notImplemented(_ action: String, _ command: CDVInvokedUrlCommand) {
        reject(command, "not_implemented",
               "« \(action) » sera disponible dans une prochaine phase du plugin.")
    }

    // -- result plumbing -----------------------------------------------------

    private func deliver(_ command: CDVInvokedUrlCommand, _ result: Result<[String: Any], CortixiaError>) {
        switch result {
        case .success(let dict): resolve(command, dict)
        case .failure(let e): reject(command, e)
        }
    }

    private func resolve(_ command: CDVInvokedUrlCommand, _ dict: [String: Any]) {
        commandDelegate.send(CDVPluginResult(status: .ok, messageAs: dict), callbackId: command.callbackId)
    }

    private func reject(_ command: CDVInvokedUrlCommand, _ code: String, _ message: String) {
        commandDelegate.send(CDVPluginResult(status: .error, messageAs: ["code": code, "message": message]),
                             callbackId: command.callbackId)
    }

    private func reject(_ command: CDVInvokedUrlCommand, _ e: CortixiaError) {
        commandDelegate.send(CDVPluginResult(status: .error, messageAs: e.asDict), callbackId: command.callbackId)
    }
}

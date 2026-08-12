import Foundation
import AVFoundation
#if canImport(CoreNFC)
import CoreNFC
#endif

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

    static let pluginVersion = "0.1.0"
    private static let defaultBaseUrl = "https://www.e-kyc.online"

    private var api: CortixiaApi?

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

    // -- Phase 3b (NFC) ------------------------------------------------------

    @objc(scanIdCard:) func scanIdCard(_ c: CDVInvokedUrlCommand) { notImplemented("scanIdCard", c) }
    @objc(scanPassport:) func scanPassport(_ c: CDVInvokedUrlCommand) { notImplemented("scanPassport", c) }
    @objc(readChip:) func readChip(_ c: CDVInvokedUrlCommand) { notImplemented("readChip", c) }

    // -- helpers -------------------------------------------------------------

    private func nfcAvailable() -> Bool {
        // CoreNFC's availability symbol isn't present on the simulator SDK.
        #if !targetEnvironment(simulator)
        if #available(iOS 13.0, *) { return NFCReaderSessionAvailable() }
        #endif
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

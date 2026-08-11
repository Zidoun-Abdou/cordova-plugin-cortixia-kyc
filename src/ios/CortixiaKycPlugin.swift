import Foundation

//
// Cortixia KYC — iOS Cordova plugin entry point.
//
// Phase 0 stub: `ping` proves the bridge; the scan methods are declared so the
// JS surface matches Android. Phase 3 implements the guided flows with Vision
// (MRZ), NFCPassportReader (chip) and AVFoundation (liveness).
//
@objc(CortixiaKycPlugin)
class CortixiaKycPlugin: CDVPlugin {

    static let pluginVersion = "0.1.0"

    @objc(ping:)
    func ping(_ command: CDVInvokedUrlCommand) {
        let info: [String: Any] = [
            "plugin": "cordova-plugin-cortixia-kyc",
            "version": CortixiaKycPlugin.pluginVersion,
            "platform": "ios",
            "nfcAvailable": nfcAvailable(),
        ]
        let result = CDVPluginResult(status: .ok, messageAs: info)
        commandDelegate.send(result, callbackId: command.callbackId)
    }

    @objc(initialize:) func initialize(_ c: CDVInvokedUrlCommand) { notImplemented("initialize", c) }
    @objc(scanIdCard:) func scanIdCard(_ c: CDVInvokedUrlCommand) { notImplemented("scanIdCard", c) }
    @objc(scanPassport:) func scanPassport(_ c: CDVInvokedUrlCommand) { notImplemented("scanPassport", c) }
    @objc(scanMrz:) func scanMrz(_ c: CDVInvokedUrlCommand) { notImplemented("scanMrz", c) }
    @objc(checkLiveness:) func checkLiveness(_ c: CDVInvokedUrlCommand) { notImplemented("checkLiveness", c) }

    private func nfcAvailable() -> Bool {
        if #available(iOS 13.0, *) {
            return NFCReaderSessionAvailable()
        }
        return false
    }

    private func notImplemented(_ action: String, _ command: CDVInvokedUrlCommand) {
        let body: [String: Any] = [
            "code": "not_implemented",
            "message": "« \(action) » sera disponible dans une prochaine phase du plugin.",
        ]
        let result = CDVPluginResult(status: .error, messageAs: body)
        commandDelegate.send(result, callbackId: command.callbackId)
    }
}

#if canImport(CoreNFC)
import CoreNFC
#endif

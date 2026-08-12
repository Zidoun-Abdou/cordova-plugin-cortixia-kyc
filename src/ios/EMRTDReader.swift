import Foundation
import CoreNFC
import CommonCrypto

//
// Standard ICAO 9303 eMRTD reader (passport + Algerian ID card) over CoreNFC —
// the iOS counterpart to the Android jMRTD path. Hand-rolled BAC + secure
// messaging (3DES + ISO 9797-1 Retail MAC) so the plugin carries no third-party
// eMRTD dependency (clean licensing) and returns the RAW datagroup bytes, which
// the plugin posts to /api/sdk/v1/decode (decoding stays server-side).
//
// Reads DG2/DG7/DG11/DG12; DG2 (portrait) is required, the rest best-effort.
//
@available(iOS 13.0, *)
final class EMRTDReader: NSObject, NFCTagReaderSessionDelegate {

    private let docNumber: String
    private let dob: String   // YYMMDD
    private let doe: String    // YYMMDD
    private var completion: ((Result<[String: Data], CortixiaError>) -> Void)?
    private var session: NFCTagReaderSession?

    // Secure-messaging session state (set after BAC).
    private var ksEnc = [UInt8]()
    private var ksMac = [UInt8]()
    private var ssc = [UInt8](repeating: 0, count: 8)

    private static let aid: [UInt8] = [0xA0, 0x00, 0x00, 0x02, 0x47, 0x10, 0x01]
    // (name, file id) — DG2 portrait, DG7 signature, DG11/DG12 detail.
    private static let dgs: [(String, [UInt8])] = [
        ("dg2", [0x01, 0x02]), ("dg7", [0x01, 0x07]),
        ("dg11", [0x01, 0x0B]), ("dg12", [0x01, 0x0C]),
    ]

    init(docNumber: String, dob: String, doe: String) {
        self.docNumber = docNumber; self.dob = dob; self.doe = doe
    }

    func read(completion: @escaping (Result<[String: Data], CortixiaError>) -> Void) {
        guard NFCTagReaderSession.readingAvailable else {
            completion(.failure(CortixiaError(code: "nfc_unavailable",
                message: "Le NFC n'est pas disponible sur cet appareil."))); return
        }
        self.completion = completion
        session = NFCTagReaderSession(pollingOption: .iso14443, delegate: self, queue: nil)
        session?.alertMessage = "Tenez votre document contre le haut du téléphone."
        session?.begin()
    }

    // -- NFCTagReaderSessionDelegate -----------------------------------------

    func tagReaderSessionDidBecomeActive(_ session: NFCTagReaderSession) {}

    func tagReaderSession(_ session: NFCTagReaderSession, didInvalidateWithError error: Error) {
        // Only surface if we haven't already delivered a result.
        finish(.failure(CortixiaError(code: "cancelled", message: "Lecture NFC annulée.")))
    }

    func tagReaderSession(_ session: NFCTagReaderSession, didDetect tags: [NFCTag]) {
        guard let tag = tags.first, case let .iso7816(passportTag) = tag else {
            session.restartPolling(); return
        }
        session.connect(to: tag) { [weak self] error in
            guard let self = self else { return }
            if error != nil {
                self.fail(session, "Connexion à la puce impossible. Réessayez."); return
            }
            DispatchQueue.global().async { self.runSession(session, passportTag) }
        }
    }

    // -- flow (background) ---------------------------------------------------

    private func runSession(_ session: NFCTagReaderSession, _ tag: NFCISO7816Tag) {
        do {
            try selectAID(tag)
            try doBAC(tag)
            var out = [String: Data]()
            for (name, fid) in EMRTDReader.dgs {
                session.alertMessage = "Lecture des données… (\(name.uppercased()))"
                if let data = try? readDataGroup(tag, fid: fid) { out[name] = data }
            }
            guard out["dg2"] != nil else {
                fail(session, "Lecture de la puce incomplète. Réessayez."); return
            }
            session.alertMessage = "Lecture terminée ✓"
            session.invalidate()
            finish(.success(out))
        } catch let e as CortixiaError {
            fail(session, e.message, code: e.code)
        } catch {
            fail(session, "Lecture de la puce échouée. Réessayez.")
        }
    }

    // -- APDU exchange (synchronous over the async CoreNFC API) --------------

    private func transceive(_ tag: NFCISO7816Tag, _ apdu: NFCISO7816APDU) throws -> ([UInt8], UInt8, UInt8) {
        let sem = DispatchSemaphore(value: 0)
        var out = [UInt8](); var sw1: UInt8 = 0; var sw2: UInt8 = 0; var err: Error?
        tag.sendCommand(apdu: apdu) { data, r1, r2, e in
            out = [UInt8](data); sw1 = r1; sw2 = r2; err = e; sem.signal()
        }
        sem.wait()
        if let err = err { throw CortixiaError(code: "nfc_read_failed", message: err.localizedDescription) }
        return (out, sw1, sw2)
    }

    private func selectAID(_ tag: NFCISO7816Tag) throws {
        let apdu = NFCISO7816APDU(instructionClass: 0x00, instructionCode: 0xA4,
                                  p1Parameter: 0x04, p2Parameter: 0x0C,
                                  data: Data(EMRTDReader.aid), expectedResponseLength: -1)
        let (_, sw1, sw2) = try transceive(tag, apdu)
        guard sw1 == 0x90 && sw2 == 0x00 else {
            throw CortixiaError(code: "nfc_read_failed", message: "Document non reconnu (applet).")
        }
    }

    // -- BAC -----------------------------------------------------------------

    private func doBAC(_ tag: NFCISO7816Tag) throws {
        let kmrz = mrzInfo()
        let kseed = Array(sha1(Array(kmrz.utf8))[0..<16])
        let kenc = deriveKey(kseed, counter: 1)
        let kmac = deriveKey(kseed, counter: 2)

        // GET CHALLENGE → RND.ICC
        let getChallenge = NFCISO7816APDU(instructionClass: 0x00, instructionCode: 0x84,
                                          p1Parameter: 0x00, p2Parameter: 0x00,
                                          data: Data(), expectedResponseLength: 8)
        let (rndIcc, c1, c2) = try transceive(tag, getChallenge)
        guard c1 == 0x90 && c2 == 0x00, rndIcc.count == 8 else {
            throw CortixiaError(code: "bac_failed", message: "Échec de la lecture (challenge).")
        }

        let rndIfd = randomBytes(8)
        let kIfd = randomBytes(16)
        let s = rndIfd + rndIcc + kIfd
        let eIfd = des3CBC(s, key: kenc, iv: [UInt8](repeating: 0, count: 8), encrypt: true)
        // ISO 9797-1 MAC alg 3 with padding method 2: always append 0x80 00…
        // (E.IFD is already a multiple of 8, so this adds a full trailing block).
        let mIfd = retailMAC(pad(eIfd), key: kmac)
        let cmdData = eIfd + mIfd

        let extAuth = NFCISO7816APDU(instructionClass: 0x00, instructionCode: 0x82,
                                     p1Parameter: 0x00, p2Parameter: 0x00,
                                     data: Data(cmdData), expectedResponseLength: 40)
        let (resp, s1, s2) = try transceive(tag, extAuth)
        guard s1 == 0x90 && s2 == 0x00, resp.count >= 40 else {
            throw CortixiaError(code: "bac_failed",
                message: "Échec d'authentification de la puce. Refaites la lecture MRZ.")
        }

        let eIcc = Array(resp[0..<32])
        let decrypted = des3CBC(eIcc, key: kenc, iv: [UInt8](repeating: 0, count: 8), encrypt: false)
        // R = RND.ICC || RND.IFD || K.ICC — verify our RND.IFD came back.
        guard Array(decrypted[8..<16]) == rndIfd else {
            throw CortixiaError(code: "bac_failed", message: "Vérification de la puce échouée.")
        }
        let kIcc = Array(decrypted[16..<32])
        let kSeedSession = zip(kIfd, kIcc).map { $0 ^ $1 }
        ksEnc = deriveKey(kSeedSession, counter: 1)
        ksMac = deriveKey(kSeedSession, counter: 2)
        ssc = Array(rndIcc[4..<8]) + Array(rndIfd[4..<8])
    }

    /// Kmrz = docNumber+cd || dob+cd || doe+cd (each field with its check digit).
    private func mrzInfo() -> String {
        let d = docNumber + String(checkDigit(docNumber))
        let b = dob + String(checkDigit(dob))
        let e = doe + String(checkDigit(doe))
        return d + b + e
    }

    // -- datagroup read under secure messaging -------------------------------

    private func readDataGroup(_ tag: NFCISO7816Tag, fid: [UInt8]) throws -> Data {
        try smSelectEF(tag, fid: fid)
        // Read the header first to learn the total length, then the remainder.
        let head = try smReadBinary(tag, offset: 0, length: 4)
        guard head.count >= 2 else { throw CortixiaError(code: "nfc_read_failed", message: "EF illisible.") }
        let total = asn1TotalLength(head)
        var data = head
        var offset = data.count
        while offset < total {
            let want = min(0xDF, total - offset)
            let chunk = try smReadBinary(tag, offset: offset, length: want)
            if chunk.isEmpty { break }
            data.append(contentsOf: chunk)
            offset += chunk.count
        }
        return Data(data.prefix(total))
    }

    private func smSelectEF(_ tag: NFCISO7816Tag, fid: [UInt8]) throws {
        let (_, sw1, sw2) = try smTransceive(tag, cla: 0x0C, ins: 0xA4, p1: 0x02, p2: 0x0C,
                                             data: fid, le: nil)
        guard sw1 == 0x90 && sw2 == 0x00 else {
            throw CortixiaError(code: "nfc_read_failed", message: "Sélection du fichier échouée.")
        }
    }

    private func smReadBinary(_ tag: NFCISO7816Tag, offset: Int, length: Int) throws -> [UInt8] {
        let p1 = UInt8((offset >> 8) & 0x7F)
        let p2 = UInt8(offset & 0xFF)
        let (data, sw1, sw2) = try smTransceive(tag, cla: 0x0C, ins: 0xB0, p1: p1, p2: p2,
                                                data: nil, le: length)
        guard sw1 == 0x90 && sw2 == 0x00 else {
            if sw1 == 0x6B { return [] } // wrong offset / end of file
            throw CortixiaError(code: "nfc_read_failed", message: "Lecture du fichier échouée.")
        }
        return data
    }

    /// Wrap a command in secure messaging, send it, verify + decrypt the reply.
    private func smTransceive(_ tag: NFCISO7816Tag, cla: UInt8, ins: UInt8, p1: UInt8, p2: UInt8,
                              data: [UInt8]?, le: Int?) throws -> ([UInt8], UInt8, UInt8) {
        incrementSSC()
        let header = [cla, ins, p1, p2]
        let paddedHeader = pad(header)

        var do87 = [UInt8]()
        if let data = data, !data.isEmpty {
            let enc = des3CBC(pad(data), key: ksEnc, iv: [UInt8](repeating: 0, count: 8), encrypt: true)
            let body = [0x01] + enc
            do87 = [0x87] + berLength(body.count) + body
        }
        var do97 = [UInt8]()
        if let le = le {
            do97 = [0x97, 0x01, UInt8(le & 0xFF)]
        }

        let m = ssc + paddedHeader + do87 + do97
        let mac = retailMAC(pad(m), key: ksMac)
        let do8e = [0x8E, 0x08] + mac

        let apduData = do87 + do97 + do8e
        let apdu = NFCISO7816APDU(instructionClass: cla, instructionCode: ins,
                                  p1Parameter: p1, p2Parameter: p2,
                                  data: Data(apduData), expectedResponseLength: 256)
        let (resp, sw1, sw2) = try transceive(tag, apdu)

        // Verify response MAC, decrypt DO87 if present.
        incrementSSC()
        var i = 0
        var encData = [UInt8]()
        var do99 = [UInt8]()
        var respMac = [UInt8]()
        while i < resp.count {
            let tagByte = resp[i]
            if tagByte == 0x87 {
                let (len, adv) = readBerLength(resp, i + 1)
                let body = Array(resp[(i + 1 + adv)..<(i + 1 + adv + len)])
                encData = Array(body.dropFirst()) // strip 0x01 padding indicator
                i += 1 + adv + len
            } else if tagByte == 0x99 {
                let len = Int(resp[i + 1])
                do99 = Array(resp[i..<(i + 2 + len)])
                i += 2 + len
            } else if tagByte == 0x8E {
                let len = Int(resp[i + 1])
                respMac = Array(resp[(i + 2)..<(i + 2 + len)])
                i += 2 + len
            } else { break }
        }

        // Recompute + compare MAC over SSC || DO87 || DO99.
        var k = ssc
        if !encData.isEmpty {
            let body = [0x01] + encData
            k += [0x87] + berLength(body.count) + body
        }
        k += do99
        let expected = retailMAC(pad(k), key: ksMac)
        guard expected == respMac else {
            throw CortixiaError(code: "nfc_read_failed", message: "Intégrité de la puce compromise.")
        }

        var plain = [UInt8]()
        if !encData.isEmpty {
            let dec = des3CBC(encData, key: ksEnc, iv: [UInt8](repeating: 0, count: 8), encrypt: false)
            plain = unpad(dec)
        }
        let rsw1 = do99.count >= 4 ? do99[2] : sw1
        let rsw2 = do99.count >= 4 ? do99[3] : sw2
        return (plain, rsw1, rsw2)
    }

    // -- helpers -------------------------------------------------------------

    private func incrementSSC() {
        var i = ssc.count - 1
        while i >= 0 { if ssc[i] == 0xFF { ssc[i] = 0; i -= 1 } else { ssc[i] += 1; break } }
    }

    private func fail(_ session: NFCTagReaderSession, _ message: String, code: String = "nfc_read_failed") {
        session.invalidate(errorMessage: message)
        finish(.failure(CortixiaError(code: code, message: message)))
    }

    private func finish(_ result: Result<[String: Data], CortixiaError>) {
        guard let c = completion else { return }
        completion = nil
        DispatchQueue.main.async { c(result) }
    }

    // -- crypto --------------------------------------------------------------

    private func sha1(_ data: [UInt8]) -> [UInt8] {
        var out = [UInt8](repeating: 0, count: Int(CC_SHA1_DIGEST_LENGTH))
        CC_SHA1(data, CC_LONG(data.count), &out)
        return out
    }

    private func deriveKey(_ seed: [UInt8], counter: UInt8) -> [UInt8] {
        let d = sha1(seed + [0, 0, 0, counter])
        let ka = adjustParity(Array(d[0..<8]))
        let kb = adjustParity(Array(d[8..<16]))
        return ka + kb + ka   // 24-byte 3DES key (K1 K2 K1)
    }

    private func adjustParity(_ key: [UInt8]) -> [UInt8] {
        key.map { b -> UInt8 in
            var v = b & 0xFE
            let ones = (0..<7).reduce(0) { $0 + Int((v >> UInt8($1 + 1)) & 1) }
            if ones % 2 == 0 { v |= 1 }
            return v
        }
    }

    private func des3CBC(_ data: [UInt8], key: [UInt8], iv: [UInt8], encrypt: Bool) -> [UInt8] {
        var out = [UInt8](repeating: 0, count: data.count + kCCBlockSize3DES)
        var moved = 0
        let op = encrypt ? CCOperation(kCCEncrypt) : CCOperation(kCCDecrypt)
        CCCrypt(op, CCAlgorithm(kCCAlgorithm3DES), CCOptions(0),
                key, key.count, iv, data, data.count, &out, out.count, &moved)
        return Array(out[0..<moved])
    }

    private func desECB(_ block: [UInt8], key: [UInt8], encrypt: Bool) -> [UInt8] {
        var out = [UInt8](repeating: 0, count: 8 + kCCBlockSizeDES)
        var moved = 0
        let op = encrypt ? CCOperation(kCCEncrypt) : CCOperation(kCCDecrypt)
        CCCrypt(op, CCAlgorithm(kCCAlgorithmDES), CCOptions(kCCOptionECBMode),
                key, key.count, nil, block, 8, &out, out.count, &moved)
        return Array(out[0..<8])
    }

    /// ISO 9797-1 MAC Algorithm 3 (Retail MAC) with DES, key = 16 bytes (Ka|Kb).
    private func retailMAC(_ data: [UInt8], key: [UInt8]) -> [UInt8] {
        let ka = Array(key[0..<8]); let kb = Array(key[8..<16])
        var y = [UInt8](repeating: 0, count: 8)
        var i = 0
        while i < data.count {
            let block = Array(data[i..<i + 8])
            y = desECB(zip(y, block).map { $0 ^ $1 }, key: ka, encrypt: true)
            i += 8
        }
        return desECB(desECB(y, key: kb, encrypt: false), key: ka, encrypt: true)
    }

    private func pad(_ data: [UInt8]) -> [UInt8] {
        var out = data + [0x80]
        while out.count % 8 != 0 { out.append(0x00) }
        return out
    }

    private func unpad(_ data: [UInt8]) -> [UInt8] {
        var i = data.count - 1
        while i >= 0 && data[i] == 0x00 { i -= 1 }
        if i >= 0 && data[i] == 0x80 { return Array(data[0..<i]) }
        return data
    }

    private func randomBytes(_ n: Int) -> [UInt8] {
        var b = [UInt8](repeating: 0, count: n)
        _ = SecRandomCopyBytes(kSecRandomDefault, n, &b)
        return b
    }

    private func checkDigit(_ s: String) -> Int {
        let weights = [7, 3, 1]
        var sum = 0
        for (i, ch) in s.uppercased().enumerated() {
            let v: Int
            if let d = ch.wholeNumberValue, ch.isNumber { v = d }
            else if ch == "<" { v = 0 }
            else if let a = ch.asciiValue, a >= 65, a <= 90 { v = Int(a) - 55 }
            else { v = 0 }
            sum += v * weights[i % 3]
        }
        return sum % 10
    }

    private func berLength(_ n: Int) -> [UInt8] {
        if n < 0x80 { return [UInt8(n)] }
        if n < 0x100 { return [0x81, UInt8(n)] }
        return [0x82, UInt8((n >> 8) & 0xFF), UInt8(n & 0xFF)]
    }

    private func readBerLength(_ data: [UInt8], _ i: Int) -> (Int, Int) {
        let b = data[i]
        if b < 0x80 { return (Int(b), 1) }
        let count = Int(b & 0x7F)
        var len = 0
        for j in 0..<count { len = (len << 8) | Int(data[i + 1 + j]) }
        return (len, 1 + count)
    }

    /// Total encoded length of a DG from its leading tag+length bytes.
    private func asn1TotalLength(_ head: [UInt8]) -> Int {
        // Tag may be 1 or 2 bytes; DG tags here are single-byte (0x60/0x75/0x6B/…).
        var idx = 1
        let b = head[idx]
        if b < 0x80 { return idx + 1 + Int(b) }
        let count = Int(b & 0x7F)
        var len = 0
        for j in 0..<count { len = (len << 8) | Int(head[idx + 1 + j]) }
        return idx + 1 + count + len
    }
}

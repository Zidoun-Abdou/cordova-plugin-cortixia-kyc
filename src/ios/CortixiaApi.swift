import Foundation

//
// Cortixia KYC REST client (iOS) — the single place that talks to
// https://www.e-kyc.online/api/sdk/v1/*. Mirrors the Android CortixiaApi so both
// platforms behave identically: X-API-Key auth, French error translation (never a
// raw HTTP status), JSON + multipart. URLSession only — no third-party HTTP dep.
//

struct CortixiaError: Error {
    let code: String
    let message: String
    var asDict: [String: Any] { ["code": code, "message": message] }

    static let network = CortixiaError(
        code: "network_error",
        message: "Connexion au service impossible. Vérifiez votre réseau et réessayez.")
    static let badResponse = CortixiaError(
        code: "bad_response",
        message: "Réponse inattendue du service. Réessayez plus tard.")
}

final class CortixiaApi {

    static let sdkVersion = "cordova-ios-0.1.0"

    private let baseUrl: String
    private let apiToken: String

    init(baseUrl: String, apiToken: String) {
        self.baseUrl = baseUrl.hasSuffix("/") ? String(baseUrl.dropLast()) : baseUrl
        self.apiToken = apiToken
    }

    static func newSessionId() -> String {
        UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
    }

    // -- endpoints -----------------------------------------------------------

    func initSession(platform: String, completion: @escaping (Result<[String: Any], CortixiaError>) -> Void) {
        postJson("/api/sdk/v1/init",
                 body: ["sdk_version": CortixiaApi.sdkVersion, "platform": platform],
                 completion: completion)
    }

    /// Validate MRZ lines. Returns parsed fields + mrz_keys (the BAC inputs).
    func mrz(documentType: String, lines: [String], sessionId: String,
             completion: @escaping (Result<[String: Any], CortixiaError>) -> Void) {
        postJson("/api/sdk/v1/mrz",
                 body: ["document_type": documentType, "lines": lines,
                        "session_id": sessionId, "sdk_version": CortixiaApi.sdkVersion,
                        "platform": "ios"],
                 completion: completion)
    }

    /// Decode raw chip datagroups. `datagroups` maps key→raw bytes.
    func decode(documentType: String, datagroups: [String: Data], sessionId: String,
                completion: @escaping (Result<[String: Any], CortixiaError>) -> Void) {
        var dg: [String: String] = [:]
        for (k, v) in datagroups where !v.isEmpty {
            dg[k] = v.base64EncodedString()
        }
        postJson("/api/sdk/v1/decode",
                 body: ["document_type": documentType, "datagroups": dg,
                        "session_id": sessionId, "sdk_version": CortixiaApi.sdkVersion,
                        "platform": "ios"],
                 completion: completion)
    }

    /// Liveness. Multipart: face (jpg) + video (mp4) + question=neutral.
    func liveness(face: Data, video: Data, sessionId: String,
                  completion: @escaping (Result<[String: Any], CortixiaError>) -> Void) {
        let boundary = "----cortixia\(UInt64.random(in: 0...UInt64.max))"
        var body = Data()
        func field(_ name: String, _ value: String) {
            body.append("--\(boundary)\r\n")
            body.append("Content-Disposition: form-data; name=\"\(name)\"\r\n\r\n")
            body.append("\(value)\r\n")
        }
        func file(_ name: String, _ filename: String, _ mime: String, _ data: Data) {
            body.append("--\(boundary)\r\n")
            body.append("Content-Disposition: form-data; name=\"\(name)\"; filename=\"\(filename)\"\r\n")
            body.append("Content-Type: \(mime)\r\n\r\n")
            body.append(data)
            body.append("\r\n")
        }
        field("question", "neutral")
        field("session_id", sessionId)
        field("sdk_version", CortixiaApi.sdkVersion)
        field("platform", "ios")
        file("face", "face.jpg", "image/jpeg", face)
        file("video", "video.mp4", "video/mp4", video)
        body.append("--\(boundary)--\r\n")

        send("/api/sdk/v1/liveness",
             contentType: "multipart/form-data; boundary=\(boundary)",
             payload: body, completion: completion)
    }

    // -- transport -----------------------------------------------------------

    private func postJson(_ path: String, body: [String: Any],
                          completion: @escaping (Result<[String: Any], CortixiaError>) -> Void) {
        guard let payload = try? JSONSerialization.data(withJSONObject: body) else {
            completion(.failure(.badResponse)); return
        }
        send(path, contentType: "application/json", payload: payload, completion: completion)
    }

    private func send(_ path: String, contentType: String, payload: Data,
                      completion: @escaping (Result<[String: Any], CortixiaError>) -> Void) {
        guard let url = URL(string: baseUrl + path) else {
            completion(.failure(.network)); return
        }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.timeoutInterval = 90
        req.setValue(apiToken, forHTTPHeaderField: "X-API-Key")
        req.setValue(contentType, forHTTPHeaderField: "Content-Type")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        req.httpBody = payload

        URLSession.shared.dataTask(with: req) { data, response, error in
            if error != nil { completion(.failure(.network)); return }
            guard let http = response as? HTTPURLResponse else {
                completion(.failure(.network)); return
            }
            let text = data.flatMap { String(data: $0, encoding: .utf8) } ?? ""
            if (200..<300).contains(http.statusCode) {
                let json = (try? JSONSerialization.jsonObject(with: data ?? Data())) as? [String: Any]
                completion(.success(json ?? [:]))
            } else {
                completion(.failure(CortixiaApi.errorFrom(status: http.statusCode, body: text)))
            }
        }.resume()
    }

    /// Map an HTTP error to a typed, user-facing failure — always prefers the
    /// server's French `message` over the raw status.
    private static func errorFrom(status: Int, body: String) -> CortixiaError {
        var error = ""
        var message = ""
        if let data = body.data(using: .utf8),
           let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] {
            error = json["error"] as? String ?? ""
            message = json["message"] as? String ?? ""
        }
        if message.isEmpty {
            switch status {
            case 401: message = "Jeton API invalide."; error = "invalid_token"
            case 402: message = "Abonnement ou quota insuffisant."
            case 502: message = "Service momentanément indisponible. Réessayez."
                      error = "liveness_unavailable"
            default:  message = "Le service a refusé la demande. Réessayez plus tard."
            }
        }
        if error.isEmpty { error = "http_\(status)" }
        return CortixiaError(code: error, message: message)
    }
}

private extension Data {
    mutating func append(_ string: String) {
        if let d = string.data(using: .utf8) { append(d) }
    }
}

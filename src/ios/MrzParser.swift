import Foundation

//
// Turns raw Vision OCR text into MRZ candidate lines — port of the Android
// MrzDetector (itself the Flutter SDK's MrzPrefilter). The server validates the
// check digits; this only decides whether a frame shows a plausible, stable MRZ
// so /mrz is called once per candidate, not per frame.
//
//  - Filler-glyph normalisation: OCR renders the MRZ filler `<` as guillemets.
//    `«`/`≪` are DOUBLE and expand to `<<` or `<` depending on which lands the
//    line on its expected length — a wrong guess shifts every fixed field after.
//  - Two consecutive identical candidates before accepting (stability).
//
final class MrzParser {

    enum DocType { case idCard, passport }

    private let docType: DocType
    private let lineCount: Int
    private let lineLength: Int   // TD1/ID = 30, TD3/passport = 44
    private var lastCandidate: [String]?

    init(_ docType: DocType) {
        self.docType = docType
        self.lineCount = docType == .passport ? 2 : 3
        self.lineLength = docType == .passport ? 44 : 30
    }

    private static let singleGlyphs = Set("‹›〈〉＜＞˂˃")
    private static let doubleGlyphs = Set("«»≪≫")

    /// Uppercase, strip whitespace, map filler glyphs. Length-aware on doubles.
    private func normalise(_ raw: String) -> String {
        var out = raw.uppercased().filter { !$0.isWhitespace }
        out = String(out.map { MrzParser.singleGlyphs.contains($0) ? "<" : $0 })
        if out.contains(where: { MrzParser.doubleGlyphs.contains($0) }) {
            let asDouble = expand(out, to: "<<")
            let asSingle = expand(out, to: "<")
            if asDouble.count == lineLength { out = asDouble }
            else if asSingle.count == lineLength { out = asSingle }
            else { out = asDouble } // a double chevron is the commoner reading
        }
        return out
    }

    private func expand(_ s: String, to replacement: String) -> String {
        var r = ""
        for ch in s {
            r += MrzParser.doubleGlyphs.contains(ch) ? replacement : String(ch)
        }
        return r
    }

    private func lineShapeOk(_ line: String) -> Bool {
        let allowed = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789<")
        guard line.unicodeScalars.allSatisfy({ allowed.contains($0) }) else { return false }
        return docType == .passport ? (42...46).contains(line.count) : (28...32).contains(line.count)
    }

    private func prefixOk(_ firstLine: String) -> Bool {
        docType == .passport
            ? firstLine.hasPrefix("P")
            : (firstLine.hasPrefix("ID") || firstLine.hasPrefix("I<"))
    }

    /// Given all OCR lines from one frame, return the MRZ lines when a stable,
    /// plausible set is confirmed across two frames, else nil.
    func offer(_ ocrLines: [String]) -> [String]? {
        let candidates = ocrLines.map { normalise($0) }.filter { lineShapeOk($0) }
        var i = 0
        while i + lineCount <= candidates.count {
            defer { i += 1 }
            guard prefixOk(candidates[i]) else { continue }
            let set = Array(candidates[i..<(i + lineCount)])
            if let last = lastCandidate, last == set {
                lastCandidate = nil        // consume; don't re-emit the same set
                return set
            }
            lastCandidate = set
            return nil                     // first sighting waits for confirmation
        }
        return nil
    }
}

package dz.cortixia.kyc;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Turns raw OCR text into MRZ candidate lines.
 *
 * The server validates the check digits; this only decides whether a frame
 * shows a plausible, stable MRZ so we call /mrz once per candidate, not per
 * frame. Ports two hard-won rules from the Flutter SDK's MrzPrefilter:
 *
 *  - Filler-glyph normalisation: OCR renders the MRZ filler `<` as guillemets
 *    and other angle glyphs. `«`/`≪` are DOUBLE and expand to `<<` or `<`
 *    depending on which lands the line on its expected length — guessing wrong
 *    shifts every fixed-position field after it.
 *  - Two consecutive identical candidates before accepting (stability).
 */
class MrzDetector {

    enum DocType { ID_CARD, PASSPORT }

    private final DocType docType;
    private final int lineCount;
    private final int lineLength;   // TD1/ID = 30, TD3/passport = 44
    private String[] lastCandidate;

    MrzDetector(DocType docType) {
        this.docType = docType;
        this.lineCount = docType == DocType.PASSPORT ? 2 : 3;
        this.lineLength = docType == DocType.PASSPORT ? 44 : 30;
    }

    private static final Pattern SINGLE_GLYPH =
            Pattern.compile("[‹›〈〉〈〉＜＞˂˃]");
    private static final Pattern DOUBLE_GLYPH =
            Pattern.compile("[«»≪≫]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern TD1_LINE = Pattern.compile("^[A-Z0-9<]{28,32}$");
    private static final Pattern TD3_LINE = Pattern.compile("^[A-Z0-9<]{42,46}$");

    /** Uppercase, strip whitespace, map filler glyphs. Length-aware on doubles. */
    private String normalise(String raw) {
        String out = WHITESPACE.matcher(raw).replaceAll("").toUpperCase();
        out = SINGLE_GLYPH.matcher(out).replaceAll("<");
        if (DOUBLE_GLYPH.matcher(out).find()) {
            String asDouble = DOUBLE_GLYPH.matcher(out).replaceAll("<<");
            String asSingle = DOUBLE_GLYPH.matcher(out).replaceAll("<");
            if (asDouble.length() == lineLength) out = asDouble;
            else if (asSingle.length() == lineLength) out = asSingle;
            else out = asDouble; // a double chevron is the commoner reading
        }
        return out;
    }

    private boolean lineShapeOk(String line) {
        Pattern p = docType == DocType.PASSPORT ? TD3_LINE : TD1_LINE;
        return p.matcher(line).matches();
    }

    private boolean prefixOk(String firstLine) {
        return docType == DocType.PASSPORT
                ? firstLine.startsWith("P")
                : (firstLine.startsWith("ID") || firstLine.startsWith("I<"));
    }

    /**
     * Given all OCR text lines from one frame, return the MRZ lines if a
     * stable, plausible set is found (confirmed across two frames), else null.
     */
    String[] offer(List<String> ocrLines) {
        List<String> candidates = new ArrayList<>();
        for (String raw : ocrLines) {
            String norm = normalise(raw);
            if (lineShapeOk(norm)) candidates.add(norm);
        }
        // Find `lineCount` consecutive candidate lines whose first has the
        // right document prefix.
        for (int i = 0; i + lineCount <= candidates.size(); i++) {
            if (!prefixOk(candidates.get(i))) continue;
            String[] set = new String[lineCount];
            for (int j = 0; j < lineCount; j++) set[j] = candidates.get(i + j);

            if (lastCandidate != null && sameAs(set)) {
                lastCandidate = null; // consume; do not re-emit the same set
                return set;
            }
            lastCandidate = set;
            return null; // first sighting waits for confirmation
        }
        return null;
    }

    private boolean sameAs(String[] set) {
        if (lastCandidate == null || lastCandidate.length != set.length) return false;
        for (int i = 0; i < set.length; i++) {
            if (!lastCandidate[i].equals(set[i])) return false;
        }
        return true;
    }
}

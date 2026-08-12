package dz.cortixia.kyc;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Color;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import net.sf.scuba.smartcards.CardService;

import org.jmrtd.BACKey;
import org.jmrtd.BACKeySpec;
import org.jmrtd.PassportService;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Guided NFC chip read — standard ICAO 9303 eMRTD (passport + Algerian ID card,
 * AID A0000002471001). Uses jMRTD over scuba-sc-android: SELECT applet → BAC
 * (keys derived from the MRZ) → secure messaging → READ BINARY of the raw
 * datagroup EFs. The raw bytes are handed back to the plugin, which posts them
 * to /api/sdk/v1/decode — decoding stays server-side, so this screen never
 * parses datagroup content (and pulls no image-decoding dependencies).
 *
 * NFC ReaderMode is used (not intent filters), so the callback already runs off
 * the UI thread and the app never auto-launches on tag tap.
 */
public class NfcReadActivity extends AppCompatActivity implements NfcAdapter.ReaderCallback {

    public static final String EXTRA_DOC_TYPE = "docType";      // "idcard" | "passport"
    public static final String EXTRA_DOC_NUMBER = "docNumber";  // BAC input
    public static final String EXTRA_DOB = "dob";               // YYMMDD
    public static final String EXTRA_DOE = "doe";               // YYMMDD

    // Returned: absolute cache paths for each datagroup that was read (or null).
    public static final String EXTRA_DG2_PATH = "dg2Path";
    public static final String EXTRA_DG7_PATH = "dg7Path";
    public static final String EXTRA_DG11_PATH = "dg11Path";
    public static final String EXTRA_DG12_PATH = "dg12Path";

    private NfcAdapter nfcAdapter;
    private TextView status;
    private String docNumber, dob, doe;
    private volatile boolean finished = false;

    static {
        // jMRTD's BAC/secure-messaging needs a full JCE provider with 3DES.
        // Android ships a stripped "BC" stub — replace it with the real
        // BouncyCastle bundled by jMRTD. Safe on API 26+ (TLS uses Conscrypt).
        try {
            java.security.Security.removeProvider("BC");
            java.security.Security.insertProviderAt(
                    new org.bouncycastle.jce.provider.BouncyCastleProvider(), 1);
        } catch (Throwable ignored) { }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        docNumber = getIntent().getStringExtra(EXTRA_DOC_NUMBER);
        dob = getIntent().getStringExtra(EXTRA_DOB);
        doe = getIntent().getStringExtra(EXTRA_DOE);

        setContentView(buildUi());

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter == null) {
            fail("nfc_unavailable", "Le NFC n'est pas disponible sur cet appareil.");
            return;
        }
        if (!nfcAdapter.isEnabled()) {
            fail("nfc_disabled", "Activez le NFC dans les réglages, puis réessayez.");
        }
    }

    private FrameLayout buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0B0B0F"));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setPadding(dp(32), dp(32), dp(32), dp(32));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        col.setLayoutParams(clp);

        TextView title = new TextView(this);
        title.setText("Lecture de la puce NFC");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setGravity(Gravity.CENTER);
        col.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Placez votre document contre le dos du téléphone et ne bougez pas.");
        hint.setTextColor(Color.parseColor("#C9C9D2"));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(16), 0, dp(28));
        col.addView(hint);

        ProgressBar spinner = new ProgressBar(this);
        spinner.setIndeterminate(true);
        col.addView(spinner);

        status = new TextView(this);
        status.setText("En attente du document…");
        status.setTextColor(Color.WHITE);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(28), 0, 0);
        col.addView(status);

        root.addView(col);
        return root;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nfcAdapter != null && nfcAdapter.isEnabled() && !finished) {
            Bundle opts = new Bundle();
            // Some chips need a slower poll to answer reliably.
            opts.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 300);
            nfcAdapter.enableReaderMode(this, this,
                    NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B
                            | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                    opts);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (nfcAdapter != null) nfcAdapter.disableReaderMode(this);
    }

    /** ReaderMode callback — already off the UI thread. */
    @Override
    public void onTagDiscovered(Tag tag) {
        if (finished) return;
        IsoDep isoDep = IsoDep.get(tag);
        if (isoDep == null) {
            // Not an ISO-DEP (14443-4) card — wrong document against the phone.
            uiStatus("Document non reconnu. Réessayez.");
            return;
        }
        try {
            isoDep.setTimeout(20000);
            uiStatus("Lecture de la puce… ne bougez pas");

            CardService cardService = CardService.getInstance(isoDep);
            cardService.open();
            PassportService service = new PassportService(
                    cardService,
                    PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
                    PassportService.DEFAULT_MAX_BLOCKSIZE,
                    false,   // isSFIEnabled
                    false);  // shouldCheckMAC (jMRTD still verifies SM MACs)
            service.open();
            service.sendSelectApplet(false);           // BAC path (no PACE)

            BACKeySpec bacKey = new BACKey(docNumber, dob, doe);
            service.doBAC(bacKey);

            // DG2 (portrait) is required; the rest are best-effort — a document
            // missing one shouldn't abort the whole read.
            Map<String, byte[]> dgs = new LinkedHashMap<>();
            byte[] dg2 = readEf(service, PassportService.EF_DG2);
            dgs.put("dg2", dg2);
            uiStatus("Lecture des données…");
            dgs.put("dg7", readEfOptional(service, PassportService.EF_DG7));
            dgs.put("dg11", readEfOptional(service, PassportService.EF_DG11));
            dgs.put("dg12", readEfOptional(service, PassportService.EF_DG12));

            deliver(dgs);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (msg.contains("lost") || msg.contains("tag was lost") || msg.contains("connection")) {
                // Recoverable: keep the reader active for another tap.
                uiStatus("Puce perdue. Repositionnez le document et ne bougez pas.");
            } else if (msg.contains("6300") || msg.contains("6982") || msg.contains("authentication")
                    || msg.contains("mac")) {
                fail("bac_failed",
                        "Échec d'authentification de la puce. Refaites la lecture MRZ, puis réessayez.");
            } else {
                fail("nfc_read_failed", "Lecture de la puce échouée. Réessayez.");
            }
        }
    }

    private void deliver(Map<String, byte[]> dgs) {
        try {
            Intent data = new Intent();
            data.putExtra(EXTRA_DG2_PATH, writeCache("cx_dg2", dgs.get("dg2")));
            data.putExtra(EXTRA_DG7_PATH, writeCache("cx_dg7", dgs.get("dg7")));
            data.putExtra(EXTRA_DG11_PATH, writeCache("cx_dg11", dgs.get("dg11")));
            data.putExtra(EXTRA_DG12_PATH, writeCache("cx_dg12", dgs.get("dg12")));
            finished = true;
            runOnUiThread(() -> {
                if (nfcAdapter != null) nfcAdapter.disableReaderMode(this);
                setResult(RESULT_OK, data);
                finish();
            });
        } catch (Exception e) {
            fail("nfc_read_failed", "Impossible d'enregistrer les données lues. Réessayez.");
        }
    }

    /** Read a datagroup EF fully as raw bytes. */
    private static byte[] readEf(PassportService service, short fid) throws Exception {
        net.sf.scuba.smartcards.CardFileInputStream in = service.getInputStream(fid);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[512];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }

    /** Optional DG — returns null if the EF is absent or unreadable. */
    private static byte[] readEfOptional(PassportService service, short fid) {
        try {
            return readEf(service, fid);
        } catch (Exception e) {
            return null;
        }
    }

    private String writeCache(String prefix, byte[] data) throws Exception {
        if (data == null || data.length == 0) return null;
        File f = new File(getCacheDir(), prefix + "_" + System.nanoTime() + ".bin");
        FileOutputStream fos = new FileOutputStream(f);
        fos.write(data);
        fos.close();
        return f.getAbsolutePath();
    }

    private void uiStatus(String msg) {
        runOnUiThread(() -> { if (status != null) status.setText(msg); });
    }

    private void fail(String code, String message) {
        if (finished) return;
        finished = true;
        runOnUiThread(() -> {
            if (nfcAdapter != null) nfcAdapter.disableReaderMode(this);
            Intent data = new Intent();
            data.putExtra("code", code);
            data.putExtra("message", message);
            setResult(RESULT_CANCELED, data);
            finish();
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

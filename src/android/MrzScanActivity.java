package dz.cortixia.kyc;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.os.Bundle;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Guided MRZ scanner — CameraX preview + ML Kit text recognition, with a
 * dimmed "Zone MRZ" frame overlay. Same UX intent as the Flutter SDK: the user
 * lines the bottom of the document up with the frame; the MRZ auto-detects (no
 * shutter), is confirmed across two frames, and returns.
 *
 * The analysis runs at 1280x720 (not CameraX's ~640x480 default) so the small
 * MRZ glyphs are legible and ML Kit locks on in a few frames instead of many —
 * the fix for "it took too long".
 */
public class MrzScanActivity extends AppCompatActivity {

    public static final String EXTRA_DOC_TYPE = "docType";   // "idcard" | "passport"
    public static final String EXTRA_LINES = "lines";
    private static final int PERM_REQUEST = 4711;

    private MrzDetector detector;
    private TextRecognizer recognizer;
    private ExecutorService analysisExecutor;
    private PreviewView previewView;
    private TextView status;
    private boolean finished = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String docType = getIntent().getStringExtra(EXTRA_DOC_TYPE);
        detector = new MrzDetector("passport".equals(docType)
                ? MrzDetector.DocType.PASSPORT : MrzDetector.DocType.ID_CARD);
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        analysisExecutor = Executors.newSingleThreadExecutor();

        setContentView(buildUi());

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, PERM_REQUEST);
        } else {
            startCamera();
        }
    }

    private FrameLayout buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        previewView = new PreviewView(this);
        previewView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(previewView);

        // Dimmed overlay with a clear MRZ frame cut out of it.
        FrameOverlay overlay = new FrameOverlay(this);
        overlay.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(overlay);

        TextView guide = new TextView(this);
        guide.setText("Alignez la zone de texte (bas du document) dans le cadre");
        guide.setTextColor(Color.WHITE);
        guide.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        guide.setGravity(Gravity.CENTER);
        guide.setPadding(dp(24), dp(56), dp(24), dp(24));
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gp.gravity = Gravity.TOP;
        guide.setLayoutParams(gp);
        root.addView(guide);

        status = new TextView(this);
        status.setText("Recherche de la MRZ…");
        status.setTextColor(Color.WHITE);
        status.setBackgroundColor(Color.argb(160, 0, 0, 0));
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(16), dp(16), dp(16), dp(40));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.gravity = Gravity.BOTTOM;
        status.setLayoutParams(sp);
        root.addView(status);

        return root;
    }

    /** Dims the whole view except a rounded MRZ band, with corner accents. */
    private static class FrameOverlay extends View {
        private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF frame = new RectF();

        FrameOverlay(Context c) {
            super(c);
            setLayerType(LAYER_TYPE_SOFTWARE, null); // needed for PorterDuff CLEAR
            dim.setColor(Color.argb(140, 0, 0, 0));
            clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            border.setStyle(Paint.Style.STROKE);
            border.setColor(Color.WHITE);
            border.setStrokeWidth(dp(2));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight();
            // MRZ band: wide + short, centred, in the lower-middle where the
            // bottom of a held document naturally sits.
            float bandW = w * 0.9f;
            float bandH = bandW * 0.42f;   // room for 3 TD1 lines with margin
            float left = (w - bandW) / 2f;
            float top = h * 0.42f;
            frame.set(left, top, left + bandW, top + bandH);

            canvas.drawRect(0, 0, w, h, dim);
            float r = dp(14);
            canvas.drawRoundRect(frame, r, r, clear);
            canvas.drawRoundRect(frame, r, r, border);
        }

        private float dp(int v) {
            return v * getResources().getDisplayMetrics().density;
        }
    }

    private void startCamera() {
        final com.google.common.util.concurrent.ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        // Higher than the ~640x480 default so small MRZ glyphs
                        // are legible and OCR locks on quickly.
                        .setTargetResolution(new Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(analysisExecutor, this::analyze);

                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                        preview, analysis);
            } catch (Exception e) {
                fail("Impossible d'ouvrir la caméra.");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyze(@NonNull ImageProxy proxy) {
        if (finished || proxy.getImage() == null) {
            proxy.close();
            return;
        }
        InputImage image = InputImage.fromMediaImage(
                proxy.getImage(), proxy.getImageInfo().getRotationDegrees());
        recognizer.process(image)
                .addOnSuccessListener(text -> {
                    handleText(text);
                    proxy.close();
                })
                .addOnFailureListener(e -> proxy.close());
    }

    private void handleText(Text text) {
        if (finished) return;
        List<String> lines = new ArrayList<>();
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                lines.add(line.getText());
            }
        }
        String[] mrz = detector.offer(lines);
        if (mrz != null) {
            finished = true;
            runOnUiThread(() -> {
                status.setText("MRZ détectée ✓");
                android.content.Intent data = new android.content.Intent();
                data.putExtra(EXTRA_LINES, mrz);
                setResult(RESULT_OK, data);
                finish();
            });
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERM_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                fail("Autorisation caméra refusée.");
            }
        }
    }

    private void fail(String message) {
        android.content.Intent data = new android.content.Intent();
        data.putExtra("message", message);
        setResult(RESULT_CANCELED, data);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (analysisExecutor != null) analysisExecutor.shutdown();
        if (recognizer != null) recognizer.close();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

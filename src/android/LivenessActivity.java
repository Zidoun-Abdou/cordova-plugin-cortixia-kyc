package dz.cortixia.kyc;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;

/**
 * Guided liveness capture — front-camera preview with a face oval, records a
 * short selfie video (no audio → no RECORD_AUDIO permission), then extracts a
 * face frame. Same UX intent as the Flutter SDK's liveness page.
 *
 * Returns file paths for the video + the extracted face JPEG; the plugin posts
 * them to /liveness. In the full document flow (Phase 2) the reference face is
 * the chip portrait; standalone, the extracted selfie frame is the reference
 * so the endpoint (PAD + matching) is exercised end to end.
 */
public class LivenessActivity extends AppCompatActivity {

    public static final String EXTRA_VIDEO_PATH = "videoPath";
    public static final String EXTRA_FACE_PATH = "facePath";
    private static final int PERM_REQUEST = 4712;
    private static final long RECORD_MS = 3500;

    private PreviewView previewView;
    private TextView status;
    private VideoCapture<Recorder> videoCapture;
    private Recording recording;
    private File videoFile;
    private boolean finished = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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

        OvalOverlay overlay = new OvalOverlay(this);
        overlay.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(overlay);

        TextView guide = new TextView(this);
        guide.setText("Placez votre visage dans l'ovale, regardez la caméra");
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
        status.setText("Préparation…");
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

    private static class OvalOverlay extends View {
        private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();

        OvalOverlay(Context c) {
            super(c);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            dim.setColor(Color.argb(140, 0, 0, 0));
            clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            border.setStyle(Paint.Style.STROKE);
            border.setColor(Color.WHITE);
            border.setStrokeWidth(c.getResources().getDisplayMetrics().density * 2);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight();
            float ovalW = w * 0.66f;
            float ovalH = ovalW * 1.3f;
            float left = (w - ovalW) / 2f;
            float top = h * 0.22f;
            oval.set(left, top, left + ovalW, top + ovalH);
            canvas.drawRect(0, 0, w, h, dim);
            canvas.drawOval(oval, clear);
            canvas.drawOval(oval, border);
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

                Recorder recorder = new Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.SD))
                        .build();
                videoCapture = VideoCapture.withOutput(recorder);

                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA,
                        preview, videoCapture);

                previewView.postDelayed(this::record, 600);
            } catch (Exception e) {
                fail("Impossible d'ouvrir la caméra frontale.");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void record() {
        if (finished) return;
        videoFile = new File(getCacheDir(), "cx_live_" + System.nanoTime() + ".mp4");
        FileOutputOptions out = new FileOutputOptions.Builder(videoFile).build();
        status.setText("Enregistrement… restez immobile");

        // No audio: keeps RECORD_AUDIO out of the plugin's permission set.
        recording = videoCapture.getOutput()
                .prepareRecording(this, out)
                .start(ContextCompat.getMainExecutor(this), event -> {
                    if (event instanceof VideoRecordEvent.Finalize) {
                        onRecordingDone((VideoRecordEvent.Finalize) event);
                    }
                });

        previewView.postDelayed(() -> {
            if (recording != null) recording.stop();
        }, RECORD_MS);
    }

    private void onRecordingDone(VideoRecordEvent.Finalize event) {
        if (finished) return;
        if (event.hasError()) {
            fail("Échec de l'enregistrement. Réessayez.");
            return;
        }
        status.setText("Analyse…");
        try {
            File faceFile = extractFace(videoFile);
            finished = true;
            android.content.Intent data = new android.content.Intent();
            data.putExtra(EXTRA_VIDEO_PATH, videoFile.getAbsolutePath());
            data.putExtra(EXTRA_FACE_PATH, faceFile.getAbsolutePath());
            setResult(RESULT_OK, data);
            finish();
        } catch (Exception e) {
            fail("Impossible d'extraire l'image du visage.");
        }
    }

    /** Grab a mid-clip frame as the reference face JPEG. */
    private File extractFace(File video) throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(video.getAbsolutePath());
            Bitmap frame = retriever.getFrameAtTime(
                    RECORD_MS * 1000 / 2, MediaMetadataRetriever.OPTION_CLOSEST);
            if (frame == null) throw new IllegalStateException("no frame");
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            frame.compress(Bitmap.CompressFormat.JPEG, 90, buf);
            File faceFile = new File(getCacheDir(), "cx_face_" + System.nanoTime() + ".jpg");
            FileOutputStream fos = new FileOutputStream(faceFile);
            fos.write(buf.toByteArray());
            fos.close();
            frame.recycle();
            return faceFile;
        } finally {
            retriever.release();
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
        if (finished) return;
        finished = true;
        android.content.Intent data = new android.content.Intent();
        data.putExtra("message", message);
        setResult(RESULT_CANCELED, data);
        finish();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

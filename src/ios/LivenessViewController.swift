import UIKit
import AVFoundation

//
// Guided liveness capture — front-camera preview with a face oval, records a
// short selfie video (no audio), then extracts a mid-clip face frame. Same UX
// intent as the Android LivenessActivity. Returns the video + face JPEG bytes;
// the plugin posts them to /liveness.
//
final class LivenessViewController: UIViewController, AVCaptureFileOutputRecordingDelegate {

    /// nil = cancelled / failed.
    var onResult: (((video: Data, face: Data)?) -> Void)?

    private let session = AVCaptureSession()
    private let movieOutput = AVCaptureMovieFileOutput()
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private let statusLabel = UILabel()
    private let overlay = OvalOverlayView()
    private var finished = false
    private let recordSeconds = 3.5

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        buildUi()
        configureSession()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        if !session.isRunning {
            DispatchQueue.global(qos: .userInitiated).async {
                self.session.startRunning()
                self.beginRecordingWhenReady()
            }
        }
    }

    /// Start recording only once the video connection is active. In the composed
    /// flow the front camera is spun up right after the MRZ camera + NFC session,
    /// so a fixed delay isn't enough — recording would silently never start and
    /// the screen would hang on "Enregistrement…". Poll readiness (up to ~5 s).
    private func beginRecordingWhenReady(attempt: Int = 0) {
        if finished { return }
        let ready = session.isRunning && (movieOutput.connection(with: .video)?.isActive ?? false)
        if ready {
            DispatchQueue.main.async { self.startRecording() }
        } else if attempt < 50 {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
                self.beginRecordingWhenReady(attempt: attempt + 1)
            }
        } else {
            fail()
        }
    }

    private func buildUi() {
        let guide = UILabel()
        guide.text = "Placez votre visage dans l'ovale, regardez la caméra"
        guide.textColor = .white
        guide.font = .systemFont(ofSize: 16)
        guide.numberOfLines = 0
        guide.textAlignment = .center
        guide.translatesAutoresizingMaskIntoConstraints = false

        statusLabel.text = "Préparation…"
        statusLabel.textColor = .white
        statusLabel.font = .systemFont(ofSize: 14)
        statusLabel.textAlignment = .center
        statusLabel.backgroundColor = UIColor(white: 0, alpha: 0.6)
        statusLabel.translatesAutoresizingMaskIntoConstraints = false

        overlay.translatesAutoresizingMaskIntoConstraints = false

        let cancel = UIButton(type: .system)
        cancel.setTitle("Annuler", for: .normal)
        cancel.setTitleColor(.white, for: .normal)
        cancel.addTarget(self, action: #selector(cancelTapped), for: .touchUpInside)
        cancel.translatesAutoresizingMaskIntoConstraints = false

        view.addSubview(overlay)
        view.addSubview(guide)
        view.addSubview(statusLabel)
        view.addSubview(cancel)

        NSLayoutConstraint.activate([
            overlay.topAnchor.constraint(equalTo: view.topAnchor),
            overlay.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            overlay.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            overlay.trailingAnchor.constraint(equalTo: view.trailingAnchor),

            guide.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 24),
            guide.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
            guide.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),

            cancel.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 8),
            cancel.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),

            statusLabel.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            statusLabel.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            statusLabel.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            statusLabel.heightAnchor.constraint(equalToConstant: 72),
        ])
    }

    private func configureSession() {
        session.sessionPreset = .medium
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .front),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else {
            fail(); return
        }
        session.addInput(input)
        guard session.canAddOutput(movieOutput) else { fail(); return }
        session.addOutput(movieOutput)

        let preview = AVCaptureVideoPreviewLayer(session: session)
        preview.videoGravity = .resizeAspectFill
        preview.frame = view.bounds
        view.layer.insertSublayer(preview, at: 0)
        previewLayer = preview
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
    }

    private func startRecording() {
        if finished { return }
        statusLabel.text = "Enregistrement… restez immobile"
        let url = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("cx_live_\(UUID().uuidString).mov")
        movieOutput.startRecording(to: url, recordingDelegate: self)
        DispatchQueue.main.asyncAfter(deadline: .now() + recordSeconds) {
            if self.movieOutput.isRecording { self.movieOutput.stopRecording() }
        }
        // Safety net: if the recording never finalizes, don't hang forever.
        DispatchQueue.main.asyncAfter(deadline: .now() + recordSeconds + 4) {
            if !self.finished { self.fail() }
        }
    }

    func fileOutput(_ output: AVCaptureFileOutput, didFinishRecordingTo outputFileURL: URL,
                    from connections: [AVCaptureConnection], error: Error?) {
        if finished { return }
        DispatchQueue.main.async { self.statusLabel.text = "Analyse…" }
        session.stopRunning()

        guard error == nil,
              let videoData = try? Data(contentsOf: outputFileURL),
              let faceData = extractFace(from: outputFileURL) else {
            try? FileManager.default.removeItem(at: outputFileURL)
            fail(); return
        }
        try? FileManager.default.removeItem(at: outputFileURL)
        finished = true
        DispatchQueue.main.async {
            self.dismiss(animated: true) { self.onResult?((video: videoData, face: faceData)) }
        }
    }

    /// Grab a mid-clip frame as the reference face JPEG.
    private func extractFace(from url: URL) -> Data? {
        let asset = AVURLAsset(url: url)
        let gen = AVAssetImageGenerator(asset: asset)
        gen.appliesPreferredTrackTransform = true
        let mid = CMTime(seconds: recordSeconds / 2, preferredTimescale: 600)
        guard let cg = try? gen.copyCGImage(at: mid, actualTime: nil) else { return nil }
        return UIImage(cgImage: cg).jpegData(compressionQuality: 0.9)
    }

    @objc private func cancelTapped() {
        if finished { return }
        finished = true
        if movieOutput.isRecording { movieOutput.stopRecording() }
        session.stopRunning()
        dismiss(animated: true) { self.onResult?(nil) }
    }

    private func fail() {
        if finished { return }
        finished = true
        DispatchQueue.main.async { self.dismiss(animated: true) { self.onResult?(nil) } }
    }
}

/// Dims the whole view except a clear face oval.
final class OvalOverlayView: UIView {
    override init(frame: CGRect) { super.init(frame: frame); isOpaque = false; backgroundColor = .clear }
    required init?(coder: NSCoder) { super.init(coder: coder); isOpaque = false; backgroundColor = .clear }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        UIColor(white: 0, alpha: 0.55).setFill()
        ctx.fill(rect)

        let ovalW = rect.width * 0.66
        let ovalH = ovalW * 1.3
        let oval = CGRect(x: (rect.width - ovalW) / 2, y: rect.height * 0.22, width: ovalW, height: ovalH)
        let path = UIBezierPath(ovalIn: oval)
        ctx.setBlendMode(.clear)
        path.fill()

        ctx.setBlendMode(.normal)
        UIColor.white.setStroke()
        path.lineWidth = 2
        path.stroke()
    }
}

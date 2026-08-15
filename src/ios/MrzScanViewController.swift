import UIKit
import AVFoundation
import Vision

//
// Guided MRZ scanner — AVFoundation camera + Vision text recognition, with a
// dimmed "Zone MRZ" band overlay. Same UX intent as the Android MrzScanActivity:
// the user lines the bottom of the document up with the band; the MRZ
// auto-detects (no shutter), is confirmed across two frames, and returns.
//
final class MrzScanViewController: UIViewController, AVCaptureVideoDataOutputSampleBufferDelegate {

    var documentType: String = "idcard"
    /// nil result = user cancelled.
    var onResult: (([String]?) -> Void)?

    private let session = AVCaptureSession()
    private let sampleQueue = DispatchQueue(label: "dz.cortixia.kyc.mrz")
    private lazy var parser = MrzParser(documentType == "passport" ? .passport : .idCard)
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private let textRequest = VNRecognizeTextRequest()
    private var finished = false
    private var busy = false

    private let statusLabel = UILabel()
    private let overlay = BandOverlayView()

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        textRequest.recognitionLevel = .accurate
        textRequest.usesLanguageCorrection = false
        buildUi()
        configureSession()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        if !session.isRunning { sampleQueue.async { self.session.startRunning() } }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        if session.isRunning { session.stopRunning() }
    }

    private func buildUi() {
        let guide = UILabel()
        guide.text = "Alignez la zone de texte (bas du document) dans le cadre"
        guide.textColor = .white
        guide.font = .systemFont(ofSize: 16)
        guide.numberOfLines = 0
        guide.textAlignment = .center
        guide.translatesAutoresizingMaskIntoConstraints = false

        statusLabel.text = "Recherche de la MRZ…"
        statusLabel.textColor = .white
        statusLabel.font = .systemFont(ofSize: 14)
        statusLabel.textAlignment = .center
        statusLabel.backgroundColor = UIColor(white: 0, alpha: 0.6)
        statusLabel.translatesAutoresizingMaskIntoConstraints = false

        overlay.translatesAutoresizingMaskIntoConstraints = false

        let cancel = UIButton(type: .system)
        cancel.setTitle("Annuler", for: .normal)
        cancel.setTitleColor(.white, for: .normal)
        cancel.titleLabel?.font = .systemFont(ofSize: 16)
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
        // 1080p, not 720p: a passport TD3 line is 44 glyphs across — at 720p
        // that's ~15 px/glyph and Vision never locks on (the 30-glyph TD1 line
        // just squeaked by, which hid this until the first passport test).
        session.sessionPreset = .hd1920x1080
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else {
            fail("Impossible d'ouvrir la caméra."); return
        }
        session.addInput(input)

        let output = AVCaptureVideoDataOutput()
        output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        output.alwaysDiscardsLateVideoFrames = true
        output.setSampleBufferDelegate(self, queue: sampleQueue)
        guard session.canAddOutput(output) else { fail("Caméra indisponible."); return }
        session.addOutput(output)

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

    // -- frame processing ----------------------------------------------------

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer,
                       from connection: AVCaptureConnection) {
        if finished || busy { return }
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        busy = true
        let handler = VNImageRequestHandler(cvPixelBuffer: pixelBuffer, orientation: .right, options: [:])
        do {
            try handler.perform([textRequest])
            let lines = (textRequest.results ?? []).compactMap { $0.topCandidates(1).first?.string }
            if let mrz = parser.offer(lines) {
                finished = true
                DispatchQueue.main.async { [weak self] in
                    self?.statusLabel.text = "MRZ détectée ✓"
                    self?.session.stopRunning()
                    self?.dismiss(animated: true) { self?.onResult?(mrz) }
                }
            }
        } catch {
            // transient Vision error — just skip the frame
        }
        busy = false
    }

    @objc private func cancelTapped() {
        if finished { return }
        finished = true
        session.stopRunning()
        dismiss(animated: true) { self.onResult?(nil) }
    }

    private func fail(_ message: String) {
        if finished { return }
        finished = true
        DispatchQueue.main.async {
            self.dismiss(animated: true) { self.onResult?(nil) }
        }
    }
}

/// Dims the whole view except a rounded MRZ band in the lower-middle.
final class BandOverlayView: UIView {
    override init(frame: CGRect) { super.init(frame: frame); isOpaque = false; backgroundColor = .clear }
    required init?(coder: NSCoder) { super.init(coder: coder); isOpaque = false; backgroundColor = .clear }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        UIColor(white: 0, alpha: 0.55).setFill()
        ctx.fill(rect)

        let bandW = rect.width * 0.9
        let bandH = bandW * 0.42
        let band = CGRect(x: (rect.width - bandW) / 2, y: rect.height * 0.42, width: bandW, height: bandH)
        let path = UIBezierPath(roundedRect: band, cornerRadius: 14)
        ctx.setBlendMode(.clear)
        path.fill()

        ctx.setBlendMode(.normal)
        UIColor.white.setStroke()
        path.lineWidth = 2
        path.stroke()
    }
}

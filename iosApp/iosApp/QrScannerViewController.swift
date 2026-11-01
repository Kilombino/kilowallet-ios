import UIKit
import AVFoundation

/// Camera QR scanner (AVFoundation). Shows the camera full screen with a cancel button and returns
/// the first QR code's text, or, for an animated QR (a signed PSBT as ur:crypto-psbt parts), hands
/// every new code to [onPart] until it says the message is complete.
final class QrScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    private let session = AVCaptureSession()
    private var completion: ((String?) -> Void)?
    private var finished = false
    private var onPart: ((String) -> Bool)?
    private var lastPart: String?
    private let progress = UILabel()

    /// Keep scanning: each new code goes to [onPart] (true = complete, close); [onEnd] when closed.
    static func presentParts(from parent: UIViewController, onPart: @escaping (String) -> Bool, onEnd: @escaping () -> Void) {
        present(from: parent, onPart: onPart) { _ in onEnd() }
    }

    static func present(from parent: UIViewController, onPart: ((String) -> Bool)? = nil, completion: @escaping (String?) -> Void) {
        func show() {
            let vc = QrScannerViewController()
            vc.completion = completion
            vc.onPart = onPart
            vc.modalPresentationStyle = .fullScreen
            parent.present(vc, animated: true)
        }
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: show()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { ok in
                DispatchQueue.main.async { ok ? show() : completion(nil) }
            }
        default:
            let alert = UIAlertController(title: "Camera not allowed",
                message: "Allow the camera for Kilowallet in Settings to scan QR codes, or paste the address instead.",
                preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: "OK", style: .default) { _ in completion(nil) })
            parent.present(alert, animated: true)
        }
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else { finish(nil); return }
        session.addInput(input)
        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else { finish(nil); return }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        output.metadataObjectTypes = [.qr]

        let preview = AVCaptureVideoPreviewLayer(session: session)
        preview.videoGravity = .resizeAspectFill
        preview.frame = view.layer.bounds
        view.layer.addSublayer(preview)

        let cancel = UIButton(type: .system)
        cancel.setTitle("CANCEL", for: .normal)
        cancel.titleLabel?.font = .monospacedSystemFont(ofSize: 16, weight: .bold)
        cancel.tintColor = UIColor(red: 0.725, green: 0.42, blue: 1.0, alpha: 1)
        cancel.backgroundColor = UIColor(white: 0, alpha: 0.6)
        cancel.layer.cornerRadius = 14
        cancel.translatesAutoresizingMaskIntoConstraints = false
        cancel.addTarget(self, action: #selector(cancelTapped), for: .touchUpInside)
        view.addSubview(cancel)
        progress.textColor = .white
        progress.font = .monospacedSystemFont(ofSize: 15, weight: .regular)
        progress.textAlignment = .center
        progress.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(progress)
        NSLayoutConstraint.activate([
            progress.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            progress.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 24),
        ])
        NSLayoutConstraint.activate([
            cancel.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            cancel.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -24),
            cancel.widthAnchor.constraint(equalToConstant: 200),
            cancel.heightAnchor.constraint(equalToConstant: 52),
        ])
        DispatchQueue.global(qos: .userInitiated).async { [session] in session.startRunning() }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        view.layer.sublayers?.compactMap { $0 as? AVCaptureVideoPreviewLayer }.forEach { $0.frame = view.layer.bounds }
    }

    @objc private func cancelTapped() { finish(nil) }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject],
                        from connection: AVCaptureConnection) {
        guard let obj = metadataObjects.first as? AVMetadataMachineReadableCodeObject, let text = obj.stringValue else { return }
        if let onPart = onPart {
            // An animated QR shows each part many times: only a new one counts.
            guard text != lastPart, !finished else { return }
            lastPart = text
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            progress.text = text.lowercased().hasPrefix("ur:") ? "reading the animated QR…" : ""
            if onPart(text) { UINotificationFeedbackGenerator().notificationOccurred(.success); finish(text) }
            return
        }
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        finish(text)
    }

    private func finish(_ text: String?) {
        guard !finished else { return }
        finished = true
        session.stopRunning()
        let done = completion
        dismiss(animated: true) { done?(text) }
    }
}

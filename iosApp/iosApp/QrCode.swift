import UIKit
import CoreImage
import CoreImage.CIFilterBuiltins

/// QR codes with Apple's own CoreImage generator (no third-party code draws a receive address).
enum QrCode {
    static func png(_ text: String) -> Data? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let image = filter.outputImage else { return nil }
        let scaled = image.transformed(by: CGAffineTransform(scaleX: 12, y: 12))
        guard let cg = CIContext().createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cg).pngData()
    }
}

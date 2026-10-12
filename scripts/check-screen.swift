import Foundation
import Vision

// Check rendered text, not merely a model readiness marker. Never log recognized content.
guard (3...4).contains(CommandLine.arguments.count) else { exit(2) }
let image = URL(fileURLWithPath: CommandLine.arguments[1])
let expected = CommandLine.arguments[2].lowercased()
let request = VNRecognizeTextRequest()
request.recognitionLevel = .accurate
request.usesLanguageCorrection = false
do {
    try VNImageRequestHandler(url: image, options: [:]).perform([request])
    let text = (request.results ?? []).compactMap { $0.topCandidates(1).first?.string }
        .joined(separator: " ").lowercased()
    guard text.contains(expected) else {
        FileHandle.standardError.write(Data("Expected screen text not visible.\n".utf8))
        exit(1)
    }
    if CommandLine.arguments.count == 4 {
        let barcode = VNDetectBarcodesRequest()
        barcode.symbologies = [.qr]
        try VNImageRequestHandler(url: image, options: [:]).perform([barcode])
        let expectedSuffix = CommandLine.arguments[3]
        guard (barcode.results ?? []).contains(where: {
            guard let value = $0.payloadStringValue else { return false }
            return value.hasPrefix("bitcoin:bc1") && value.hasSuffix(expectedSuffix)
        }) else {
            FileHandle.standardError.write(Data("Payment request QR did not encode the expected amount/chain.\n".utf8))
            exit(1)
        }
    }
} catch {
    FileHandle.standardError.write(Data("Could not inspect screenshot.\n".utf8))
    exit(2)
}

import Foundation
import Vision

// Check rendered text, not merely a model readiness marker. Never log recognized content.
guard (3...4).contains(CommandLine.arguments.count) else { exit(2) }
let image = URL(fileURLWithPath: CommandLine.arguments[1])
func normalized(_ text: String) -> String {
    text.lowercased().components(separatedBy: .whitespacesAndNewlines).filter { !$0.isEmpty }.joined(separator: " ")
}
let expected = normalized(CommandLine.arguments[2])
let request = VNRecognizeTextRequest()
request.recognitionLevel = .accurate
request.recognitionLanguages = ["en-US"]
request.usesLanguageCorrection = true
do {
    try VNImageRequestHandler(url: image, options: [:]).perform([request])
    let observations = request.results ?? []
    let text = normalized(observations.compactMap { $0.topCandidates(1).first?.string }.joined(separator: " "))
    // Vision may return column groups instead of reading order. Reconstruct visual lines
    // by their baselines, then read left-to-right. Keep the complete phrase requirement.
    var lines: [[VNRecognizedTextObservation]] = []
    for observation in observations.sorted(by: { $0.boundingBox.midY > $1.boundingBox.midY }) {
        if let row = lines.firstIndex(where: {
            abs($0[0].boundingBox.midY - observation.boundingBox.midY) <
                min($0[0].boundingBox.height, observation.boundingBox.height) * 0.5
        }) {
            lines[row].append(observation)
        } else {
            lines.append([observation])
        }
    }
    let spatialText = normalized(lines.map { line in
        line.sorted(by: { $0.boundingBox.minX < $1.boundingBox.minX })
            .compactMap { $0.topCandidates(1).first?.string }.joined(separator: " ")
    }.joined(separator: "\n"))
    let alternatives = observations.flatMap { $0.topCandidates(3).map { normalized($0.string) } }
    guard text.contains(expected) || spatialText.contains(expected) || alternatives.contains(where: { $0.contains(expected) }) else {
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

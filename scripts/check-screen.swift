import Foundation
import Vision

// Check rendered text, not merely a model readiness marker. Never log recognized content.
guard CommandLine.arguments.count == 3 else { exit(2) }
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
} catch {
    FileHandle.standardError.write(Data("Could not inspect screenshot.\n".utf8))
    exit(2)
}

import Foundation
import UIKit
import UniformTypeIdentifiers

/// Saving and opening the backup file (.kab) with the system's document picker: the file goes
/// where the user chooses (Files, iCloud Drive, a USB drive…), never anywhere by itself.
final class FileBridge: NSObject, UIDocumentPickerDelegate {
    private static var current: FileBridge?
    private var onSaved: ((String?) -> Void)?
    private var onOpened: ((Data?) -> Void)?
    private var tempURL: URL?

    /// Ask where to save [data] as [name]; calls back with nil when saved, or an error / "cancelled".
    static func save(name: String, data: Data, completion: @escaping (String?) -> Void) {
        DispatchQueue.main.async {
            guard let top = Bridge.topViewController() else { completion("No window to show the picker in."); return }
            let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
            do {
                try data.write(to: url, options: [.atomic, .completeFileProtection])
            } catch {
                completion(error.localizedDescription); return
            }
            let b = FileBridge()
            b.onSaved = completion
            b.tempURL = url
            current = b
            let picker = UIDocumentPickerViewController(forExporting: [url], asCopy: true)
            picker.delegate = b
            top.present(picker, animated: true)
        }
    }

    /// Let the user pick a file; calls back with its bytes, or nil when cancelled or unreadable.
    static func open(completion: @escaping (Data?) -> Void) {
        DispatchQueue.main.async {
            guard let top = Bridge.topViewController() else { completion(nil); return }
            let b = FileBridge()
            b.onOpened = completion
            current = b
            let picker = UIDocumentPickerViewController(forOpeningContentTypes: [UTType.data], asCopy: true)
            picker.delegate = b
            picker.allowsMultipleSelection = false
            top.present(picker, animated: true)
        }
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        if let done = onSaved {
            cleanUp(); done(nil)
        } else if let done = onOpened {
            let data = urls.first.flatMap { url -> Data? in
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                return try? Data(contentsOf: url)
            }
            done(data)
        }
        FileBridge.current = nil
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        if let done = onSaved { cleanUp(); done("cancelled") }
        if let done = onOpened { done(nil) }
        FileBridge.current = nil
    }

    /// The temporary copy holds the words (encrypted): it does not stay behind.
    private func cleanUp() {
        if let u = tempURL { try? FileManager.default.removeItem(at: u) }
        tempURL = nil
    }
}

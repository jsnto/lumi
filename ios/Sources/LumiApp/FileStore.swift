// The default store: the JSON document persisted in Application Support
// (the iOS counterpart of the Room database file). The app model's
// persistence factory - split out of the god object, whose role is
// orchestration, not storage.

import Foundation
import os
import LumiCore

/// The app's own log (store load/save failures must not fail silently).
let storeLog = Logger(subsystem: "com.lumi.player", category: "store")

extension AppModel {
    /// The default store: the JSON document persisted in Application
    /// Support (the iOS counterpart of the Room database file). A corrupt
    /// document is logged, KEPT (moved aside under a timestamped name -
    /// starting empty and overwriting it would lose the whole library),
    /// and the app starts fresh; a write that fails keeps the old file
    /// (the atomic write) and logs.
    public static func makeFileStore() -> Store {
        let fm = FileManager.default
        let support = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        let dir = support.appendingPathComponent("Lumi", isDirectory: true)
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        let file = dir.appendingPathComponent("store.json")
        var doc = StoreDoc()
        if let data = try? Data(contentsOf: file) {
            do {
                doc = try JSONDecoder().decode(StoreDoc.self, from: data)
            } catch {
                storeLog.error("store file is corrupt - starting empty and keeping it: \(error)")
                let stamp = Int(Date().timeIntervalSince1970)
                let corrupt = dir.appendingPathComponent("store.json.corrupt-\(stamp)")
                do {
                    try fm.moveItem(at: file, to: corrupt)
                } catch {
                    storeLog.error("could not keep the corrupt store file aside: \(error)")
                }
            }
        }
        return Store(doc: doc) { document in
            do {
                let data = try JSONEncoder().encode(document)
                try data.write(to: file, options: .atomic)
            } catch {
                // The atomic write keeps the old file on failure: the
                // change is lost from disk (the in-memory store keeps
                // working and a later mutation retries), never the
                // earlier document.
                storeLog.error("failed to save the store file - the change is not persisted: \(error)")
            }
        }
    }
}

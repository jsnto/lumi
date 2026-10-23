// The tag link manager - the iOS counterpart of the Android app's
// TagLinkManager (the god object's tag-song-management section, split off
// in the same lockstep split): the per-song link / unlink / reorder with
// the Android ordering semantics, and the song profile's assign-to-tag.
//
// The app model keeps the same-named public surfaces as thin delegates,
// so the UI's access pattern is untouched; the behavior lives here.

import Foundation
import LumiCore

/// The tag song management (the tag detail page's link / unlink /
/// reorder) + the song profile's assign-to-tag.
@MainActor
public final class TagLinkManager {
    // MARK: - dependencies (the app model wires the seam)

    private let store: Store

    /// The app's event line (the model's uiEvent - the Android Toast).
    public var event: (String) -> Void = { _ in }

    public init(store: Store) {
        self.store = store
    }

    // MARK: - the per-song link / unlink / reorder

    /// Links [entryIds] to [tagId] (the in-app add-song flow). Each write
    /// commits before the next starts (the Android bulk-save's commit
    /// order: the tree's render order), and a song the tag already plays
    /// is the reported no-op (the label-level duplicate refusal).
    public func linkEntriesToTag(tagId: Int, entryIds: [Int]) -> Int {
        var linked = 0
        for entryId in entryIds {
            if store.linkEntryToTag(tagId: tagId, entryId: entryId) {
                linked += 1
            }
        }
        return linked
    }

    /// Unlinks ONE song from ONE tag (the per-song remove): the content
    /// row, the folder membership, and the song's links to OTHER tags are
    /// untouched; the tag's surviving links renumber densely (the Android
    /// removal semantics).
    public func unlinkSongFromTag(tagId: Int, linkId: Int) -> Bool {
        store.unlinkLinkFromTag(tagId: tagId, linkId: linkId)
    }

    /// Reorders a tag's song list (the drag reorder). A no-op where the
    /// order is unchanged; a refused non-permutation keeps the current
    /// order (the Android reorder guard).
    public func reorderTagSongs(tagId: Int, newOrder: [Int]) {
        let current = store.entriesForTag(tagId).map { $0.linkId }
        guard newOrder != current else { return } // no write where it started
        store.reorderTagLinks(tagId: tagId, linkIds: newOrder)
        event("Order saved.")
    }

    // MARK: - the profile's assign-to-tag

    /// The profile's assign-to-tag (a tag that already plays the song is
    /// the reported no-op - the song is identified by its name).
    public func assignEntryToTag(tag: Tag, entry: Entry) -> Bool {
        if store.linkEntryToTag(tagId: tag.id, entryId: entry.id) {
            event("Added \(entry.label) to \(tag.name).")
            return true
        }
        event("That song is already on \(tag.name).")
        return false
    }
}

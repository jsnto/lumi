// The direction the content list is sorted by title - a port of the Android
// app's com.lumi.player.data.ContentSort.
//
// ASCENDING (A->Z) is the list's default order; the content pane's sort
// control offers both directions and persists the pick (a fresh or unchosen
// install starts A->Z, and an explicitly picked direction stays
// authoritative).

import Foundation

public enum ContentSortDirection: String, Codable, CaseIterable, Sendable {
    case ascending
    case descending
}

/// Reads a stored sort direction. Unset or invalid values fall back to
/// .ascending - the content list's default.
public func contentSortDirectionFromStored(_ value: String?) -> ContentSortDirection {
    switch value?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
    case "desc", "descending": return .descending
    default: return .ascending
    }
}

extension ContentSortDirection {
    /// The settings value this direction stores under.
    public var storedValue: String {
        switch self {
        case .ascending: return "asc"
        case .descending: return "desc"
        }
    }
}

/// Orders [items] by their title (case-insensitive), with [id] breaking ties
/// so the order is deterministic. DESCENDING is the exact reverse of the
/// ascending order (the default). A new list is returned.
public func sortedByTitle<T>(
    _ items: [T],
    title: (T) -> String,
    id: (T) -> Int,
    direction: ContentSortDirection,
) -> [T] {
    let ascending = items.sorted { a, b in
        let ta = title(a).lowercased(), tb = title(b).lowercased()
        if ta == tb { return id(a) < id(b) }
        return ta < tb
    }
    return direction == .ascending ? ascending : ascending.reversed()
}

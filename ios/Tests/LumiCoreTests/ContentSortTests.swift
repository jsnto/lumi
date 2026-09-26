// The content sort's behavior pins - the iOS ports of the Android app's
// ContentSort tests.

import Testing
@testable import LumiCore

@Test func unsetAndInvalidStoredValuesFallBackToAscending() {
    #expect(contentSortDirectionFromStored(nil) == .ascending)
    #expect(contentSortDirectionFromStored("") == .ascending)
    #expect(contentSortDirectionFromStored("  ") == .ascending)
    #expect(contentSortDirectionFromStored("sideways") == .ascending)
}

@Test func anExplicitStoredDirectionStaysAuthoritative() {
    #expect(contentSortDirectionFromStored("desc") == .descending)
    #expect(contentSortDirectionFromStored("DESC") == .descending)
    #expect(contentSortDirectionFromStored("descending") == .descending)
    #expect(contentSortDirectionFromStored("asc") == .ascending)
}

@Test func ascendingIsCaseInsensitiveAndDeterministic() {
    let items = ["banana", "Apple", "cherry", "apple"]
    let sorted = sortedByTitle(items, title: { $0 }, id: { $0.count }, direction: .ascending)
    // Case-insensitive: the two "apple"s tie and keep their input order
    // (stable sort); "banana" and "cherry" follow.
    #expect(sorted == ["Apple", "apple", "banana", "cherry"])
}

@Test func descendingIsTheExactReverseOfAscending() {
    let items = ["banana", "Apple", "cherry"]
    let up = sortedByTitle(items, title: { $0 }, id: { $0.count }, direction: .ascending)
    let down = sortedByTitle(items, title: { $0 }, id: { $0.count }, direction: .descending)
    #expect(down == up.reversed())
}

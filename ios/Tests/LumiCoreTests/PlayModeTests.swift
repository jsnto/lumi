// The play-mode parsing behavior pins - the iOS port of the Android
// app's PlayMode tests: a stored value (either one) wins; only the
// no-setting fallback is .proximity (the app's default).

import Testing
@testable import LumiCore

@Test func unsetOrInvalidStoredValuesFallBackToProximity() {
    #expect(PlayMode.fromStorage("") == .proximity)
    #expect(PlayMode.fromStorage("  ") == .proximity)
    #expect(PlayMode.fromStorage("sideways") == .proximity)
}

@Test func aStoredModeWinsOverTheFallback() {
    #expect(PlayMode.fromStorage("continuous") == .continuous)
    #expect(PlayMode.fromStorage("CONTINUOUS") == .continuous)
    #expect(PlayMode.fromStorage("proximity") == .proximity)
    #expect(PlayMode.fromStorage("Proximity") == .proximity)
}

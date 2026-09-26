// The playback policy's behavior pins - the iOS ports of the Android
// app's policy tests: both modes keep NFC always listening, reading
// overrides pause, and a re-read of the paused tag RESUMES (never
// restarts).

import Testing
@testable import LumiCore

@Test func continuousMode_ignoresTagLoss() {
    let policy = PlaybackPolicy(mode: .continuous)
    #expect(policy.onTagDetected(uid: "AA") == .startPlayback)
    // The tag leaves: continuous mode keeps playing - no pause, and the
    // tag stays active until a different tag is read.
    #expect(policy.onTagLost(uid: "AA") == .ignore)
    #expect(policy.activeTagUid == "AA")
}

@Test func proximityMode_tagLossPausesAndKeepsTheTagActive() {
    let policy = PlaybackPolicy(mode: .proximity)
    #expect(policy.onTagDetected(uid: "AA") == .startPlayback)
    #expect(policy.onTagLost(uid: "AA") == .pausePlayback)
    // The tag STAYS active: a re-read of the paused list resumes it.
    #expect(policy.activeTagUid == "AA")
    #expect(policy.onTagDetected(uid: "AA", paused: true) == .resume)
}

@Test func aRereadOfThePlayingTagIsIgnored() {
    let policy = PlaybackPolicy(mode: .proximity)
    #expect(policy.onTagDetected(uid: "AA") == .startPlayback)
    #expect(policy.onTagDetected(uid: "AA", paused: false) == .ignore)
}

@Test func aDifferentTagReadSwitchesPlayback() {
    let policy = PlaybackPolicy(mode: .proximity)
    #expect(policy.onTagDetected(uid: "AA") == .startPlayback)
    #expect(policy.onTagDetected(uid: "BB", paused: true) == .startPlayback)
    #expect(policy.activeTagUid == "BB")
    // The old tag's loss no longer pauses (it is not the active tag).
    #expect(policy.onTagLost(uid: "AA") == .ignore)
}

@Test func onPlaybackStoppedClearsTheActiveTag() {
    let policy = PlaybackPolicy(mode: .proximity)
    _ = policy.onTagDetected(uid: "AA")
    policy.onPlaybackStopped()
    #expect(policy.activeTagUid == nil)
    // ...so a later read starts (not resumes) that tag's list again.
    #expect(policy.onTagDetected(uid: "AA") == .startPlayback)
}

@Test func aSeedSurvivesAModeSwitch() {
    let policy = PlaybackPolicy(mode: .continuous)
    policy.seedActiveTagUid("AA")
    #expect(policy.activeTagUid == "AA")
}

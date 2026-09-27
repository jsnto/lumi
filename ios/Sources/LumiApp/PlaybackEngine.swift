// The playback engine - the iOS counterpart of the Android app's
// PlayerService. AVPlayer-backed, with the service's own observable
// behavior ported one-for-one:
//
// - a start is a QUEUE + optional remembered position (start index + seek):
//   the remembered position applies only when it was saved against the
//   exact linked list (the fingerprint check happens before the engine is
//   started, in the app model - same shape as the Android ACTION_START
//   extras);
// - the `wantsPlay` intent: set on start/resume, cleared on pause/stop, so
//   an error-driven queue walk cannot re-arm playback over a pause (the
//   Android bad-file loop fix: a paused session stays paused while the
//   bounded error walk completes);
// - the bad-file loop guard: an error on every entry of the queue stops the
//   session (consecutiveErrors >= queue.size); the counter resets only on
//   ACTUAL audio (isPlaying), not on the buffering of a failed prepare -
//   otherwise an all-bad queue loops file-to-file forever (device-proven
//   on the Android app; ported);
// - position remembering: saved while playing (every 5 s) and on
//   pause/stop, only for the active tag's loaded audio;
// - a tag list LOOPS until stopped or switched.
//
// The engine never reads the store itself: the app model passes the queue
// (specs) and the tag uid, and the engine reports saves back through its
// delegate - the Android service's TagPositionStore seam.

import AVFoundation
import Combine
import Foundation
import LumiCore

/// The shared playback state the UI observes (the iOS counterpart of the
/// Android app's PlayerStateHolder state).
public struct PlayerState: Equatable {
    public enum Status: Equatable {
        case idle
        case playing
        case paused
    }

    public var status: Status = .idle
    public var currentLabel: String? = nil
    /// UID of the tag whose list is playing (nil for manual playback).
    public var activeTagUid: String? = nil

    public init() {}
}

/// The engine's callbacks into the app model (the Android service's seams).
public protocol PlaybackEngineDelegate: AnyObject {
    /// The engine stopped on its own (the queue ran out or was refused):
    /// the app model clears the policy's active tag.
    func playbackEngineDidStop()
    /// Save the active tag's position (the engine's position-save seam).
    func playbackEngineSavePosition(tagUid: String, positionMs: Int64, entryIndex: Int, queueFingerprint: String)
}

public final class PlaybackEngine: NSObject, ObservableObject {
    @Published public private(set) var state: PlayerState = PlayerState()

    public var status: PlayerState.Status { state.status }
    public var currentLabel: String? { state.currentLabel }

    public weak var delegate: PlaybackEngineDelegate?

    /// The queue (specs in play order) and the tag it belongs to.
    private var queue: [EntrySpec] = []
    private var position = 0
    private let player = AVPlayer()
    /// The start seek to apply once the first item is ready.
    private var pendingSeekMs: Int64 = 0
    /// The playback intent: set on start/resume, cleared on pause/stop.
    private var wantsPlay = false
    /// Consecutive playback errors on successive entries - the bad-file
    /// loop guard's counter (reset on actual audio only).
    private var consecutiveErrors = 0
    private var currentItem: AVPlayerItem?
    /// Whether the current item's ready-state has been observed yet (the
    /// pending seek applies on the FIRST ready).
    private var readyApplied = false
    private var readyPollTimer: Timer?
    private var positionSaveTimer: Timer?
    private var periodicObserver: Any?
    /// The current item's end-of-track observer (a natural end of a song
    /// advances to the next one - the list LOOPS, the Android app's
    /// queue-advance on a completed track).
    private var trackEndObserver: Any?
    private var lastPositionSaveMs: TimeInterval = 0
    private var audioConfigured = false

    public override init() {
        super.init()
        configureAudioSession()
        player.automaticallyWaitsToMinimizeStalling = false
        observePeriodicTime()
    }

    private func configureAudioSession() {
        guard !audioConfigured else { return }
        audioConfigured = true
        do {
            let session = AVAudioSession.sharedInstance()
            // Background audio: the .playback category plays (and keeps
            // playing under the lock) without the system's mute switch
            // applying - the iOS counterpart of the Android app's
            // background mediaPlayback service (UIBackgroundModes audio).
            try session.setCategory(.playback, mode: .default)
            try session.setActive(true)
        } catch {
            print("Lumi: AVAudioSession configuration failed: \(error)")
        }
    }

    /// One-second heartbeat: the bad-file loop guard's reset point - the
    /// error counter resets ONLY while actual audio plays (timeControl
    /// status .playing). The BUFFERING of each failed prepare never resets
    /// it, so an all-bad queue reaches the stop threshold and stops the
    /// session instead of looping file-to-file (the Android device fix).
    private func observePeriodicTime() {
        periodicObserver = player.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 1, preferredTimescale: 600),
            queue: .main,
        ) { [weak self] _ in
            guard let self else { return }
            if self.player.timeControlStatus == .playing {
                self.consecutiveErrors = 0
            }
            self.maybeSavePosition()
        }
    }

    // MARK: - commands (the Android ACTION_* surface)

    /// ACTION_START: play [entries] for tag [uid] (nil = manual playback,
    /// no tag attached). [startIndex]/[startSeekMs] are the remembered
    /// position, when one applies.
    public func startQueue(_ entries: [EntrySpec], uid: String?, startIndex: Int = 0, startSeekMs: Int64 = 0) {
        guard !entries.isEmpty else {
            stopPlayback()
            return
        }
        queue = entries
        position = min(max(startIndex, 0), entries.count - 1)
        pendingSeekMs = max(0, startSeekMs)
        consecutiveErrors = 0
        wantsPlay = true
        setState { s in
            s.activeTagUid = uid
            s.status = .playing
        }
        loadCurrent()
    }

    /// ACTION_TOGGLE: a paused current song RESUMES the same song (no fresh
    /// start), a playing one pauses in place.
    public func togglePlayPause() {
        guard !queue.isEmpty else {
            stopPlayback()
            return
        }
        if state.status == .idle { return }
        if player.timeControlStatus == .playing {
            pausePlayback()
        } else {
            resumePlayback()
        }
    }

    /// ACTION_RESUME: idempotent - safe on every re-read of the active tag.
    public func resumePlayback() {
        guard !queue.isEmpty else {
            stopPlayback()
            return
        }
        wantsPlay = true
        player.play()
        setState { s in s.status = .playing }
        armPositionSaveTimer()
    }

    /// ACTION_PAUSE: pauses without ending the session (proximity mode: the
    /// tag left range). Idempotent; a paused session STAYS paused (the
    /// wantsPlay intent is cleared, so an error walk cannot re-arm it).
    public func pausePlayback() {
        guard !queue.isEmpty else {
            stopPlayback()
            return
        }
        wantsPlay = false
        // Pause unconditionally: player.pause() while the current item is
        // still loading (waitingToPlayMinimallyStalled) cancels the
        // pending start, so a pause that lands in the load window sticks
        // (the Android service pauses during BUFFERING too - a paused
        // session STAYS paused).
        player.pause()
        setState { s in s.status = .paused }
        saveCurrentPosition()
        positionSaveTimer?.invalidate()
        positionSaveTimer = nil
    }

    /// ACTION_STOP: ends the session.
    public func stopPlayback() {
        wantsPlay = false
        pendingSeekMs = 0
        saveCurrentPosition()
        positionSaveTimer?.invalidate()
        positionSaveTimer = nil
        readyPollTimer?.invalidate()
        readyPollTimer = nil
        if let trackEndObserver { NotificationCenter.default.removeObserver(trackEndObserver) }
        trackEndObserver = nil
        player.removeAllItems()
        currentItem = nil
        queue = []
        position = 0
        setState { s in
            s.status = .idle
            s.currentLabel = nil
            s.activeTagUid = nil
        }
        delegate?.playbackEngineDidStop()
    }

    /// The current item's natural end: the song finished, so the list
    /// advances to the next entry (looping) - without this, a finished
    /// track sits at its end with the state still "playing" and the
    /// per-tag position memory never finalizes (the Android app's queue
    /// walks forward on a completed track).
    @objc private func trackDidPlayToEndTime() {
        guard wantsPlay else { return } // a late notice after a stop: ignore
        consecutiveErrors = 0
        advance()
    }

    /// ACTION_NEXT: move to the next entry, looping the list.
    public func next() {
        guard !queue.isEmpty else {
            stopPlayback()
            return
        }
        advance()
    }

    // MARK: - queue handling

    private func loadCurrent() {
        guard queue.indices.contains(position) else {
            stopPlayback()
            return
        }
        let spec = queue[position]
        setState { s in s.currentLabel = spec.label }
        readyApplied = false

        // The payload is a file path (the app's stored payloads are the
        // picked file paths, like the Android app's stored content uris
        // are the picked content uris) - build the file url accordingly.
        let url: URL
        if spec.payload.hasPrefix("file://") {
            url = URL(string: spec.payload) ?? URL(fileURLWithPath: spec.payload)
        } else {
            url = URL(fileURLWithPath: spec.payload)
        }
        let item = AVPlayerItem(url: url)
        currentItem = item
        player.replaceCurrentItem(with: item)
        if let trackEndObserver { NotificationCenter.default.removeObserver(trackEndObserver) }
        trackEndObserver = NotificationCenter.default.addObserver(
            self,
            selector: #selector(trackDidPlayToEndTime),
            name: .AVPlayerItemDidPlayToEndTime,
            object: item,
        )
        // The playback intent decides the first play: a start or resume
        // plays, a paused session loading (an error-driven advance) does
        // NOT - the bad-file loop fix, ported (the Android service's
        // playWhenReady = wantsPlay).
        if wantsPlay {
            player.play()
            armPositionSaveTimer()
        }
        pollReadyState()
    }

    /// Move to the next entry, looping the list.
    private func advance() {
        guard !queue.isEmpty else {
            stopPlayback()
            return
        }
        // The start seek belongs to the queue's start entry only: once
        // the list advances, a remembered offset must not apply to a
        // later item (it would seek the wrong song).
        pendingSeekMs = 0
        position = (position + 1) % queue.count
        loadCurrent()
    }

    /// Polls the player's ready state: the pending seek applies on the
    /// FIRST ready of the CURRENT item (media3 has no onPlayerReady
    /// listener either - the Android app applies it on STATE_READY), a
    /// failed load counts an error.
    private func pollReadyState() {
        readyPollTimer?.invalidate()
        let timer = Timer(timeInterval: 0.05, repeats: true) { [weak self] t in
            guard let self, let item = self.currentItem else {
                t.invalidate()
                return
            }
            // The CURRENT item's own readiness: player.status right after
            // a replaceCurrentItem can already report the new item's
            // not-yet-loaded default (readyToPlay is the status default),
            // so the pending seek must wait for the item's real ready.
            if item.isReadyToPlay {
                t.invalidate()
                self.readyPollTimer = nil
                if !self.readyApplied {
                    self.readyApplied = true
                    self.applyPendingSeek()
                }
            } else if item.status == .failed {
                t.invalidate()
                self.readyPollTimer = nil
                self.handlePlaybackFailure()
            }
        }
        RunLoop.main.add(timer, forMode: .common)
        readyPollTimer = timer
    }

    private func applyPendingSeek() {
        guard pendingSeekMs > 0 else { return }
        let seconds = TimeInterval(pendingSeekMs) / 1000.0
        player.seek(to: seconds, toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] success in
            // A failed seek keeps the pending offset (the song starts at
            // 0 instead of silently dropping the remembered position);
            // a success consumes it. advance()/stopPlayback clear the
            // offset either way, so it can never leak into a later item.
            if success { self?.pendingSeekMs = 0 }
        }
    }

    // MARK: - errors (the bad-file loop guard)

    /// A playback error on the current entry. The guard counts CONSECUTIVE
    /// failed entries: when the whole queue has failed (consecutiveErrors
    /// >= queue.size) the session stops - an all-bad queue must not loop
    /// file-to-file forever. The counter resets only while actual audio
    /// plays (the periodic observer), so the BUFFERING of each failed
    /// prepare does not defeat the guard.
    private func handlePlaybackFailure() {
        consecutiveErrors += 1
        if !queue.isEmpty, consecutiveErrors >= queue.count {
            stopPlayback() // the whole queue failed: stop the session
            return
        }
        advance() // the walk is bounded by the queue size
    }

    private func armPositionSaveTimer() {
        positionSaveTimer?.invalidate()
        positionSaveTimer = Timer(timeInterval: 1.0, repeats: true) { [weak self] _ in
            self?.maybeSavePosition()
        }
        if let positionSaveTimer { RunLoop.main.add(positionSaveTimer, forMode: .common) }
    }

    /// Saves the active tag's position on the 5 s cadence while actually
    /// playing (the Android service's save cadence), and on pause/stop.
    private func maybeSavePosition() {
        guard player.timeControlStatus == .playing else { return }
        // The cadence clock is the wall clock - the player's currentTime is
        // the media time within the current item, not an epoch clock.
        let now = Date().timeIntervalSinceReferenceDate
        guard now - lastPositionSaveMs >= 5.0 else { return }
        lastPositionSaveMs = now
        saveCurrentPosition()
    }

    private func saveCurrentPosition() {
        guard let uid = state.activeTagUid, !queue.isEmpty, queue.indices.contains(position) else { return }
        let spec = queue[position]
        guard spec.type == "audio" else { return }
        // The media time within the current item, in milliseconds. An
        // unready or failed item reports a NaN time (Int64(NaN) is a
        // fatal trap) - it settles to 0, the position the Android
        // service reports before the player is ready.
        let seconds = player.currentTime().seconds
        let ms = seconds.isFinite ? Int64(max(0, seconds) * 1000) : 0
        lastPositionSaveMs = Date().timeIntervalSinceReferenceDate
        delegate?.playbackEngineSavePosition(
            tagUid: uid,
            positionMs: max(0, ms),
            entryIndex: position,
            queueFingerprint: fingerprintOf(queue),
        )
    }

    private func setState(_ mutate: (inout PlayerState) -> Void) {
        var s = state
        mutate(&s)
        state = s
    }

    deinit {
        if let periodicObserver { player.removeTimeObserver(periodicObserver) }
        if let trackEndObserver { NotificationCenter.default.removeObserver(trackEndObserver) }
        readyPollTimer?.invalidate()
        positionSaveTimer?.invalidate()
        player.removeAllItems()
    }
}

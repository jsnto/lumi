// The playback engine - the iOS counterpart of the Android app's
// PlayerService. AVAudioEngine-backed (an AVPlayerNode schedules the
// queue's items; a tap on its output applies the per-track loudness gain
// and the peak limiter in the sample domain - the iOS counterpart of the
// Android app's LoudnessProcessor in the playback processor chain), with
// the service's own observable behavior ported one-for-one:
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
//   ACTUAL audio (the node playing), not on the buffering of a failed
//   load - otherwise an all-bad queue loops file-to-file forever
//   (device-proven on the Android app; ported);
// - position remembering: saved while playing (every 5 s) and on
//   pause/stop, only for the active tag's loaded audio;
// - the per-track loudness level (the gain + the limiter engagement):
//   resolved per load through the [loudnessProvider] seam (the app model
//   reads the app's audio level setting + the entry's stored loudness -
//   no database read on the playback path), re-resolved on the item's
//   first ready (a settings change that landed in between is picked up,
//   like the Android service's ready re-apply), and OFF mode / unmeasured
//   / silent all leave the processor at the exact pass-through;
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

/// The per-track loudness level the engine applies (the gain in dB and
/// whether the peak limiter engages - the Android service's
/// loudnessProcessor.setLevel arguments). A zero gain with no limiter is
/// the exact pass-through.
public struct LoudnessLevel: Equatable {
    public var gainDb: Double
    public var engageLimiter: Bool

    public init(gainDb: Double, engageLimiter: Bool) {
        self.gainDb = gainDb
        self.engageLimiter = engageLimiter
    }

    public static let passThrough = LoudnessLevel(gainDb: 0, engageLimiter: false)
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

    /// The per-track loudness level seam (the Android service's
    /// applyLoudness resolution): resolves the gain + limiter engagement
    /// for a loaded entry (the app's audio level setting + the entry's
    /// stored loudness). nil = the pass-through (no gain, no limiter).
    public var loudnessProvider: ((EntrySpec) -> LoudnessLevel)?
    /// The sample-domain gain + soft-clip stage (the Android app's
    /// player.LoudnessProcessor): applied in-place to the player node's
    /// output buffers (the audio tap), on the audio thread.
    private let loudnessProcessor = LoudnessSampleProcessor()

    // The audio graph: the player node schedules the queue's items, the
    // main mixer feeds the output. The tap on the player node's bus 0 is
    // where the loudness level applies to the track's own audio.
    private let engine = AVAudioEngine()
    private let playerNode = AVPlayerNode()

    /// The queue (specs in play order) and the tag it belongs to.
    private var queue: [EntrySpec] = []
    private var position = 0
    /// The start seek to apply once the first item is ready.
    private var pendingSeekMs: Int64 = 0
    /// The playback intent: set on start/resume, cleared on pause/stop.
    private var wantsPlay = false
    /// Consecutive playback errors on successive entries - the bad-file
    /// loop guard's counter (reset on actual audio only).
    private var consecutiveErrors = 0
    /// The scheduled item's box: the token that tells a completion
    /// handler (a natural end, a load failure, or a REMOVAL's spurious
    /// callback) which of the scheduled items it belongs to - a stale
    /// box is ignored, so a removed item can never advance the queue.
    private final class ScheduledItemBox {
        let item: AVPlayerItem
        init(item: AVPlayerItem) { self.item = item }
    }
    private var currentBox: ScheduledItemBox?
    /// Whether the current item's ready-state has been observed yet (the
    /// pending seek applies on the FIRST ready).
    private var readyApplied = false
    private var readyPollTimer: Timer?
    private var heartbeatTimer: Timer?
    private var lastPositionSaveMs: TimeInterval = 0
    private var audioConfigured = false
    private var interruptionObserver: Any?
    private var routeChangeObserver: Any?

    public override init() {
        super.init()
        configureAudioSession()
        engine.attach(playerNode)
        engine.connect(playerNode, to: engine.mainMixerNode, format: nil)
        // The loudness tap: the player node's own output, in place - the
        // gain + the soft clip run on the track's samples before the
        // mixer. A nil format lets the engine negotiate the node's own
        // (standard float) format.
        playerNode.installTap(onBus: 0, bufferSize: 1024, format: nil) { [loudnessProcessor] buffer, _ in
            loudnessProcessor.apply(to: buffer)
        }
        observeHeartbeat()
        observeInterruptions()
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

    /// Starts the audio engine if it is not running (the engine is
    /// started lazily on the first play and stopped on a full stop -
    /// idle audio hardware costs power).
    private func ensureEngineRunning() {
        guard !engine.isRunning else { return }
        do {
            try engine.start()
        } catch {
            print("Lumi: AVAudioEngine start failed: \(error)")
        }
    }

    /// One-second heartbeat: the bad-file loop guard's reset point - the
    /// error counter resets ONLY while actual audio plays (the node
    /// playing). The BUFFERING of each failed load never resets it, so an
    /// all-bad queue reaches the stop threshold and stops the session
    /// instead of looping file-to-file (the Android device fix). It also
    /// drives the position save's 5 s cadence.
    private func observeHeartbeat() {
        heartbeatTimer = Timer(timeInterval: 1.0, repeats: true) { [weak self] _ in
            guard let self else { return }
            if self.playerNode.status == .playing {
                self.consecutiveErrors = 0
            }
            self.maybeSavePosition()
        }
        RunLoop.main.add(heartbeatTimer!, forMode: .common)
    }

    /// The platform interruption (a phone call, Siri): the node pauses on
    /// a began (the session stays - wantsPlay is kept), and on an ended
    /// with the shouldResume option a still-wanted session resumes. The
    /// route change that un-plugs the output recovers by restarting the
    /// engine (the scheduled items survive - the node keeps playing).
    private func observeInterruptions() {
        let session = AVAudioSession.sharedInstance()
        interruptionObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: session,
            queue: .main,
        ) { [weak self] note in
            guard let self else { return }
            guard let typeValue = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  let type = AVAudioSession.InterruptionType(rawValue: typeValue) else { return }
            switch type {
            case .began:
                self.playerNode.pause()
            case .ended:
                let optionsValue = note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
                let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue)
                if options.contains(.shouldResume), self.wantsPlay, !self.queue.isEmpty {
                    self.resumePlayback()
                }
            @unknown default:
                break
            }
        }
        routeChangeObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.routeChangeNotification,
            object: session,
            queue: .main,
        ) { [weak self] note in
            guard let self else { return }
            guard let reasonValue = note.userInfo?[AVAudioSessionRouteChangeKey] as? UInt,
                  let reason = AVAudioSession.RouteChangeReason(rawValue: reasonValue) else { return }
            if reason == .oldRouteUnavailable {
                // The output unplugged: restart the engine (the standard
                // recovery) - the scheduled items and the node state
                // survive, playback continues on the new route.
                self.playerNode.pause()
                self.engine.stop()
                if self.wantsPlay {
                    self.ensureEngineRunning()
                    self.playerNode.play()
                }
            }
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
        if playerNode.status == .playing {
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
        ensureEngineRunning()
        playerNode.play()
        setState { s in s.status = .playing }
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
        // Pause unconditionally: pausing the node while the current item is
        // still loading (waiting to play) cancels the pending start, so a
        // pause that lands in the load window sticks (the Android service
        // pauses during BUFFERING too - a paused session STAYS paused).
        playerNode.pause()
        setState { s in s.status = .paused }
        saveCurrentPosition()
    }

    /// ACTION_STOP: ends the session.
    public func stopPlayback() {
        wantsPlay = false
        pendingSeekMs = 0
        saveCurrentPosition()
        readyPollTimer?.invalidate()
        readyPollTimer = nil
        currentBox = nil // the scheduled item's handler is stale from here
        playerNode.removeAllScheduledItems()
        playerNode.stop()
        engine.stop()
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
    private func handleTrackEnd() {
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
        applyLoudness(spec)

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
        let box = ScheduledItemBox(item: item)
        // The previous item (if any) leaves the schedule: its completion
        // handler fires for the REMOVAL, and the box token below keeps it
        // from advancing the queue a second time.
        currentBox = box
        playerNode.removeAllScheduledItems()
        playerNode.schedule(item, at: nil) { [weak self, box] _, error in
            DispatchQueue.main.async {
                guard let self, self.currentBox === box else { return }
                if error == nil {
                    self.handleTrackEnd()
                } else {
                    self.handlePlaybackFailure()
                }
            }
        }
        // The playback intent decides the first play: a start or resume
        // plays, a paused session loading (an error-driven advance) does
        // NOT - the bad-file loop fix, ported (the Android service's
        // playWhenReady = wantsPlay). A pending start seek waits for the
        // item's first ready instead (the seek lands before the node
        // starts, so the remembered position is the start - no flash of
        // the song's beginning).
        if wantsPlay, pendingSeekMs == 0 {
            ensureEngineRunning()
            playerNode.play()
        }
        pollReadyState()
    }

    /// The per-track loudness level (the Android service's applyLoudness):
    /// the gain from the app's audio level setting and the entry's stored
    /// loudness (the app model's seam - no database read on the playback
    /// path). OFF mode, an unmeasured entry, or a silent one all leave
    /// the processor at the exact pass-through.
    private func applyLoudness(_ spec: EntrySpec) {
        let level = loudnessProvider?(spec) ?? .passThrough
        loudnessProcessor.setLevel(gainDb: level.gainDb, engageLimiter: level.engageLimiter)
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

    /// Polls the current item's ready state: on the FIRST ready the
    /// pending start seek applies (AVFoundation has no onPlayerReady
    /// listener either - the Android app applies it on STATE_READY) and
    /// the per-track loudness level re-resolves (a settings change that
    /// landed between the load and the ready is picked up - the Android
    /// service's ready re-apply). A FAILED item settles the failure here
    /// too (the box token keeps it from double-counting against the
    /// completion handler's own error report - whichever lands first
    /// wins, the other sees a stale box and drops out).
    private func pollReadyState() {
        readyPollTimer?.invalidate()
        let timer = Timer(timeInterval: 0.05, repeats: true) { [weak self] t in
            guard let self, let box = self.currentBox else {
                t.invalidate()
                return
            }
            if box.item.isReadyToPlay {
                t.invalidate()
                self.readyPollTimer = nil
                guard !self.readyApplied else { return }
                self.readyApplied = true
                self.applyLoudness(self.queue[self.position])
                if self.pendingSeekMs > 0 {
                    self.applyPendingSeek()
                }
            } else if box.item.status == .failed, self.currentBox === box {
                // The completion handler is the primary end-of-item
                // signal; this is the backstop for a failure that never
                // hands back an error (a stalled load): report it once
                // and let the walk run.
                t.invalidate()
                self.readyPollTimer = nil
                self.handlePlaybackFailure()
            }
        }
        RunLoop.main.add(timer, forMode: .common)
        readyPollTimer = timer
    }

    private func applyPendingSeek() {
        guard pendingSeekMs > 0, let box = currentBox else { return }
        let seconds = TimeInterval(pendingSeekMs) / 1000.0
        box.item.seek(to: seconds, toleranceBefore: .zero, toleranceAfter: .zero) { [weak self, box] success in
            DispatchQueue.main.async {
                // A failed seek keeps the pending offset (the song starts
                // at 0 instead of silently dropping the remembered
                // position); a success consumes it. advance()/stopPlayback
                // clear the offset either way, so it can never leak into a
                // later item.
                guard let self, self.currentBox === box else { return }
                if success { self.pendingSeekMs = 0 }
                // The deferred start: once the seek has settled (or been
                // abandoned), the node starts - a wanted session plays.
                if self.wantsPlay, self.playerNode.status != .playing {
                    self.ensureEngineRunning()
                    self.playerNode.play()
                }
            }
        }
    }

    // MARK: - errors (the bad-file loop guard)

    /// A playback error on the current entry (the scheduled item failed
    /// to load or to play). The guard counts CONSECUTIVE failed entries:
    /// when the whole queue has failed (consecutiveErrors >= queue.size)
    /// the session stops - an all-bad queue must not loop file-to-file
    /// forever. The counter resets only while actual audio plays (the
    /// heartbeat), so the BUFFERING of each failed load does not defeat
    /// the guard.
    private func handlePlaybackFailure() {
        consecutiveErrors += 1
        if !queue.isEmpty, consecutiveErrors >= queue.count {
            stopPlayback() // the whole queue failed: stop the session
            return
        }
        advance() // the walk is bounded by the queue size
    }

    /// Saves the active tag's position on the 5 s cadence while actually
    /// playing (the Android service's save cadence), and on pause/stop.
    private func maybeSavePosition() {
        guard playerNode.status == .playing else { return }
        // The cadence clock is the wall clock - the item's currentTime is
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
        let seconds = currentBox?.item.currentTime.seconds ?? 0
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
        NotificationCenter.default.removeObserver(self)
        if let heartbeatTimer { heartbeatTimer.invalidate() }
        if let readyPollTimer { readyPollTimer.invalidate() }
        playerNode.removeAllScheduledItems()
        engine.stop()
    }
}

// MARK: - the sample-domain loudness stage

/**
 * The per-track gain + soft-clip peak limiter applied in the sample
 * domain (the iOS counterpart of the Android app's player.LoudnessProcessor,
 * which sits in the Android app's playback processor chain the same way
 * this sits in the audio tap). The math is LumiCore's [gainFor] /
 * [softClip]: [setLevel] takes the resolved per-track gain (dB) and the
 * limiter engagement (engaged for boosted tracks only - a gain of 0 with
 * no limiter is the EXACT pass-through, the OFF mode's contract), and
 * [apply] processes the tap's buffers in place.
 *
 * The level is read under a lock (the audio thread reads it while the
 * main thread sets it per track); the processing itself is stateless
 * with respect to the stream.
 */
final class LoudnessSampleProcessor {
    private let lock = NSLock()
    private var linearGain: Float = 1
    private var engageLimiter = false

    /// The resolved level, in dB (the Android processor's gainDb).
    var gainDb: Double {
        lock.lock(); defer { lock.unlock() }
        return log10(Double(linearGain)) * 20.0
    }

    /// Whether the peak limiter is engaged (the Android processor's
    /// limiterEngaged).
    var limiterEngaged: Bool {
        lock.lock(); defer { lock.unlock() }
        return engageLimiter
    }

    /// Applies the per-track level: [gainDb] of gain and, only when
    /// [engageLimiter], the soft-clip peak limiter at the -1 dBTP knee
    /// (LumiCore's LIMITER_KNEE_LINEAR). A zero gain with no limiter
    /// arms nothing - the pass-through path touches no samples.
    func setLevel(gainDb: Double, engageLimiter: Bool) {
        lock.lock(); defer { lock.unlock() }
        if gainDb == 0.0, !engageLimiter {
            linearGain = 1
        } else {
            linearGain = Float(pow(10.0, gainDb / 20.0))
        }
        self.engageLimiter = engageLimiter
    }

    /// Processes the tap's buffer in place (the audio thread).
    func apply(to buffer: AVAudioPCMBuffer) {
        guard let channelData = buffer.floatChannelData else { return }
        var gain = 1.0
        var limiter = false
        lock.lock()
        gain = linearGain
        limiter = engageLimiter
        lock.unlock()
        guard gain != 1.0 || limiter else { return } // the exact pass-through

        let frameLength = Int(buffer.frameLength)
        let channels = Int(buffer.format.channelCount)
        guard frameLength > 0, channels > 0 else { return }
        let knee = LIMITER_KNEE_LINEAR
        if buffer.format.isNonInterleaved {
            for ch in 0..<channels {
                guard let data = channelData[ch] else { continue }
                for frame in 0..<frameLength {
                    var s = data[frame] * gain
                    if limiter, abs(s) > Float(knee) {
                        s = Float(softClip(Double(s), knee: knee))
                    }
                    data[frame] = s
                }
            }
        } else {
            guard let data = channelData[0] else { return }
            for frame in 0..<frameLength {
                for ch in 0..<channels {
                    var s = data[frame * channels + ch] * gain
                    if limiter, abs(s) > Float(knee) {
                        s = Float(softClip(Double(s), knee: knee))
                    }
                    data[frame * channels + ch] = s
                }
            }
        }
    }
}

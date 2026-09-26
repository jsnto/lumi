// The NFC proximity monitor - the iOS counterpart of the Android app's
// always-listening NFC path (MainActivity's foreground dispatch + the
// presence pollers, nfc/ReconnectingPresenceSession +
// nfc/GenericPresencePoller + the absence-timeout fallback).
//
// iOS platform shape (documented platform differences):
// - Android receives a tag through the system's foreground dispatch even
//   when the app was not the foreground app; iOS CoreNFC's reader session
//   only delivers tags while THIS app is in the foreground. A tag held
//   against a locked or backgrounded phone is not read here; when the app
//   returns to the foreground a held tag is re-detected and (reading
//   overrides pause) resumes the paused list - the observable behavior is
//   preserved for the in-foreground hold.
// - The reads and the card write go through the connected tag's OWN low-
//   level NDEF read/write (NFCNDEFTag.read/write) - not the session's
//   high-level readNDEFMessages: the app's on-card shape is the AOSP
//   NdefRecord grammar byte for byte (see NdefCodec), which the high-
//   level API neither writes nor parses. The Android fast path reads a
//   low-level page of the tag every 300 ms on a held session; the iOS
//   equivalent is that raw read, run on the same 300 ms heartbeat. A
//   failed read first tries a FRESH session (re-detect through
//   pollForTags - the port of the fresh-session reconnect that
//   disambiguates a cold-but-present tag from a removed one): the
//   re-detected tag's read is a PRESENCE read only (it refreshes the
//   presence record on success and confirms the removal on failure - it
//   never re-dispatches the payload, so a probe-originated re-read cannot
//   undo a user pause/stop or settle an armed scan), and TWO strikes
//   (100 ms between) confirm the removal - the Android two-strike
//   debounce, ported.
// - The absence-timeout fallback (the "tag re-read wait" window, default
//   15 s) runs too: an active tag with no presence refresh (no successful
//   read, no detection) longer than the window is removed - the port of
//   the Android fallback that owns a tag when no live session can be held.
// - Tags without NDEF are invisible to the iOS reader session (requireNDEF
//   is on; CoreNFC has no low-level tag-tech read equivalent). NDEF tags -
//   the cards this app writes - are the supported path.
//
// The controller NEVER touches playback itself: it reports tag events to
// the app model (onTagDetected / onTagLost), which decides through the
// shared PlaybackPolicy - the same split as the Android app.

import CoreNFC
import Foundation

/// What an armed scan settles to.
public enum ScanOutcome: Equatable {
    /// A registered tag was read: open its detail page.
    case registeredTag(Tag)
    /// An unknown tag was read: enter the registration flow (the prefill
    /// and restore songs travel with it). The payload is the read's
    /// answer (nil when the card carries no lumi record) - a read that
    /// could not complete never settles the scan at all, so "failed" is
    /// not a settle's payload (it must never be read as "no record").
    case unknownTag(uid: String, payload: LumiTagData?, memory: TagMemoryRecord?)
    /// The card-write scan wrote (or refused) the target tag's data.
    case cardWrite(message: String)
}

/// The app model's face for NFC events.
public protocol NfcEventListener: AnyObject {
    /// A tag's payload was read in the always-listening path (the Android
    /// onTagDetected entry: the model decides start / switch / resume).
    func nfcControllerDidDetect(uid: String, payload: LumiTagData?)
    /// The active tag was confirmed removed (the Android onTagLost entry:
    /// proximity mode pauses).
    func nfcControllerDidLose(uid: String)
    /// An armed scan settled.
    func nfcControllerDidSettleScan(_ outcome: ScanOutcome)
    /// The device's NFC usability changed (no hardware / unsupported).
    func nfcControllerUsabilityChanged(supported: Bool)
}

public final class NfcController: NSObject, ObservableObject {
    // MARK: - constants (the Android presence-poll cadences, ported)

    /// Cadence of the presence probes (the Android fast path's 300 ms).
    public static let readIntervalMs: UInt64 = 300
    /// Debounce delay before the single retry probe after a failed read.
    public static let retryDelayMs: UInt64 = 100
    /// The absence-timeout fallback's default window, in seconds (the
    /// Android nfc_reread_seconds default, clamped 1-120 on read).
    public static let rereadSecondsDefault = 15
    /// Bound on the in-flight re-detect re-checks: while a failed probe's
    /// fresh-session re-detect has not landed, the strike re-checks every
    /// retryDelayMs up to this count; running out IS the fresh-session
    /// read failure (the two-strike confirm - a gone tag never re-detects).
    public static let maxRecoveryRechecks = 5

    public weak var eventListener: NfcEventListener?

    /// Whether this device has usable NFC hardware (the NFC prompt's gate).
    @Published public private(set) var isSupported: Bool = NFCNDEFReaderSession.isSupported
    /// Whether a reader session is currently live (the always-listening
    /// state the UI can surface).
    @Published public private(set) var isListening: Bool = false

    // MARK: - session state

    private var session: NFCNDEFReaderSession?
    private var connectedTag: NFCNDEFTag?
    private var connectedUid: String?
    private var didBecomeActive = false
    private var heartbeat: DispatchSourceTimer?
    /// The active tag's uid while watched (the policy's active tag): the
    /// grace window times out on its presence record.
    private var watchedUid: String?
    private var lastPresenceRefresh = Date.distantPast
    /// The two-strike debounce state (a strike retries once after
    /// retryDelayMs; two strikes confirm the removal).
    private var strikeRetryScheduled = false
    /// The uid a failed probe's fresh-session re-detect is recovering:
    /// that tag's re-detect read is a presence read only (no dispatch),
    /// so a probe-originated re-read never re-presents the tag.
    private var probeRecoveryUid: String?
    /// The in-flight re-detect's re-check count (bounded by
    /// maxRecoveryRechecks): re-checks every retryDelayMs while a
    /// failed probe's fresh-session re-detect has not landed.
    private var strikeRetryReschedules = 0
    /// Set when the session is invalidated for app backgrounding (as
    /// opposed to a tag removal): no loss event fires for it, and the
    /// session is NOT re-armed (the scene phase re-arms it on return).
    private var invalidatedForBackground = false
    /// The last re-arm after an unexpected invalidation: a persistent
    /// error must not spin a re-arm loop.
    private var lastRearm = Date.distantPast

    // MARK: - scan mode (the Android scan flag on the single read path)

    public enum ScanMode: Equatable {
        case none
        /// The tags pane's "Scan NFC": a registered tag opens its page, an
        /// unknown one enters registration.
        case tagScan
        /// The tag page's "Save to NFC card": writes [target]'s data to the
        /// presented card when the card's uid matches.
        case cardWrite(target: Tag)
    }

    private var scanMode: ScanMode = .none
    private var nfcRereadSeconds: () -> Int = { NfcController.rereadSecondsDefault }
    /// The app model's resolver of a uid to its registered tag (the scan
    /// settle's Room query).
    public var tagByUid: (String) -> Tag? = { _ in nil }

    public init() {
        super.init()
    }

    // MARK: - lifecycle (always listening while the app is foreground)

    /// Arms the always-listening session (the Android reconfigureNfc's
    /// foreground dispatch, on the app-active edge).
    public func startListening() {
        guard isSupported, session == nil else { return }
        invalidatedForBackground = false
        didBecomeActive = false
        // The no-timeout reader session (the iOS 11 init - it stays live
        // until invalidated, which the scene-phase teardown does on
        // backgrounding; the iOS 13.2 init's sessionTimeout would
        // auto-invalidate the always-listening session after a few
        // seconds).
        let s = NFCNDEFReaderSession(
            delegate: self,
            invalidateAfterFirstRead: false, // continuous, never one-shot
            queue: .main,
        )
        session = s
        // The documented NDEF-only design, made real: the iOS 11 init
        // leaves requireNDEF at its default false, so a non-NDEF tag could
        // be delivered and (on a successful connect) refresh the watched
        // tag's absence window. The app's cards are NDEF tags and a
        // non-NDEF tag has no supported path - the session must not see
        // one.
        s.requireNDEF = true
        s.alertMessage = "Hold an NFC tag against the back of the iPhone."
        isListening = true
    }

    /// Tears the session down (the app-backgrounding edge): no tag-loss
    /// event - a re-entry re-arms and a held tag re-detects (reading
    /// overrides pause).
    public func stopListening(backgrounded: Bool) {
        invalidatedForBackground = backgrounded
        teardownSession()
    }

    private func teardownSession() {
        session?.invalidate()
        session = nil
        connectedTag = nil
        connectedUid = nil
        didBecomeActive = false
        stopHeartbeat()
        // The debounce state dies with the session: a pending strike
        // retry must not confirm a removal across the teardown.
        strikeRetryScheduled = false
        probeRecoveryUid = nil
        strikeRetryReschedules = 0
        isListening = false
    }

    // MARK: - watched tag (the presence record's seed)

    /// Seeds / refreshes the presence record for the tag that (re-)becomes
    /// active (the Android tagActivated seed + reconfigureNfc re-seed).
    public func seedWatchedTag(uid: String?) {
        watchedUid = uid
        if let uid { lastPresenceRefresh = Date() }
    }

    /// The absence window in ms (the "tag re-read wait" setting, clamped
    /// 1-120 like the Android app).
    private var graceWindowMs: UInt64 {
        let seconds = max(1, min(120, nfcRereadSeconds()))
        return UInt64(seconds) * 1000
    }

    // MARK: - scan arming (the Android startTagScan / startCardWrite)

    public func armTagScan() {
        scanMode = .tagScan
    }

    public func armCardWrite(target: Tag) {
        scanMode = .cardWrite(target: target)
    }

    public func disarmScan() {
        scanMode = .none
    }

    // MARK: - heartbeat (the presence poll)

    private func startHeartbeat() {
        stopHeartbeat()
        let timer = DispatchSource.makeTimerSource(queue: .main)
        timer.schedule(deadline: .now() + .milliseconds(Int(NfcController.readIntervalMs)),
                       repeating: .milliseconds(Int(NfcController.readIntervalMs)))
        timer.setEventHandler { [weak self] in
            self?.tick()
        }
        timer.resume()
        heartbeat = timer
    }

    private func stopHeartbeat() {
        heartbeat?.cancel()
        heartbeat = nil
    }

    private func tick() {
        guard let session, didBecomeActive else {
            // The session is not live yet (or was invalidated): keep
            // nudging the detection.
            session?.pollForTags()
            checkGraceWindow()
            return
        }
        if let tag = connectedTag {
            // The fast path: a REAL read on the held session - succeeds
            // only while the tag is physically in the field AND its RF
            // session is alive (the Android page-0 read's contract).
            probeRead(tag)
        } else {
            // No held tag: ask the system to look.
            session.pollForTags()
        }
        checkGraceWindow()
    }

    private func checkGraceWindow() {
        // The absence-timeout fallback: an active tag with no presence
        // refresh longer than the window is removed.
        guard let uid = watchedUid else { return }
        let elapsed = UInt64(Date().timeIntervalSince(lastPresenceRefresh) * 1000)
        if elapsed > graceWindowMs {
            // Fire exactly once: clear the record so a re-fire cannot
            // double-report (the Android fallback's clear-on-fire).
            watchedUid = nil
            eventListener?.nfcControllerDidLose(uid: uid)
        }
    }

    // MARK: - probe + two-strike debounce (the ReconnectingPresenceSession
    // port)

    private func probeRead(_ tag: NFCNDEFTag) {
        tag.read { [weak self] data, error in
            guard let self else { return }
            if error == nil, data != nil {
                // The read answered: the tag is in the field and its
                // session is alive - the presence record refreshes.
                self.strikeRetryScheduled = false
                self.refreshPresence()
            } else {
                // The read failed: the session is stale (the tag went
                // cold) or the tag left. Disambiguate with a FRESH session
                // before counting the strike (the Android wrapper's
                // reconnect): a held-still tag whose RF session dropped
                // re-detects and reads again - no strike; a gone tag
                // never re-detects - the pending retry confirms the
                // removal.
                self.tryFreshSessionOrStrike()
            }
        }
    }

    private func tryFreshSessionOrStrike() {
        // Re-detect: the fresh-session attempt. The stale connected tag's
        // session is dropped; pollForTags looks again.
        probeRecoveryUid = connectedUid
        strikeRetryReschedules = 0
        connectedTag = nil
        connectedUid = nil
        session?.pollForTags()
        if !strikeRetryScheduled {
            strikeRetryScheduled = true
            let deadline = DispatchTime.now() + .milliseconds(Int(NfcController.retryDelayMs))
            DispatchQueue.main.asyncAfter(deadline: deadline) { [weak self] in
                self?.strikeRetry()
            }
        }
    }

    private func strikeRetry() {
        strikeRetryScheduled = false
        // The recovery re-detect's own read settles its own outcome (a
        // success refreshes the presence - the cold-but-present case,
        // no strike; a failure confirms the removal - the fresh-session
        // read failure), so while the re-detect is still IN FLIGHT the
        // probed tag is not confirmed here: a system re-scan + connect
        // + read routinely takes longer than the first 100 ms check.
        // The wait is bounded (a gone tag never re-detects): once the
        // re-checks run out, the fresh session produced no read at all -
        // the two-strike confirm.
        guard let recovery = probeRecoveryUid else { return }
        guard watchedUid == recovery else {
            // A different tag took the field and owns the presence record
            // now (or the watch is gone): the probed tag's fate is
            // settled by the policy's switch, not by this retry.
            probeRecoveryUid = nil
            return
        }
        if strikeRetryReschedules < Self.maxRecoveryRechecks {
            strikeRetryReschedules += 1
            strikeRetryScheduled = true
            let deadline = DispatchTime.now() + .milliseconds(Int(NfcController.retryDelayMs))
            DispatchQueue.main.asyncAfter(deadline: deadline) { [weak self] in
                self?.strikeRetry()
            }
            return
        }
        probeRecoveryUid = nil
        confirmRemoval()
    }

    private func confirmRemoval() {
        guard let uid = watchedUid else { return }
        watchedUid = nil // clear the presence record (no double-report)
        eventListener?.nfcControllerDidLose(uid: uid)
    }

    private func refreshPresence() {
        lastPresenceRefresh = Date()
        strikeRetryScheduled = false
    }

    // MARK: - reading a tag (the single read path)

    /// The read's settle: parse the lumi payload from the raw NDEF bytes
    /// (the AOSP on-tag grammar - NdefCodec) and route it - to the armed
    /// scan's settle when one is armed, to the always-listening playback
    /// read otherwise. A read that could not complete (a [readError])
    /// never dispatches OR settles: the payload is unknown (an armed
    /// scan stays armed for the next read - the Android scan's own
    /// settle-on-the-next-read behavior - and the always-listening read
    /// just reports nothing), and the heartbeat's probes keep owning the
    /// tag's presence.
    private func handleRead(data: Data?, readError: Error?, uid: String, tag: NFCNDEFTag) {
        guard readError == nil else { return }
        let payload: LumiTagData?
        if let data {
            payload = NdefCodec.fromNdefMessageBytes([UInt8](data))
        } else {
            payload = nil
        }
        if case let .cardWrite(target) = scanMode {
            // The card-write scan: the read's outcome does not gate the
            // write (the write is its own raw write below).
            scanMode = .none
            settleCardWrite(target: target, uid: uid, tag: tag)
            return
        }
        if scanMode == .tagScan {
            scanMode = .none
            settleTagScan(uid: uid, payload: payload)
            return
        }

        // The always-listening read (both play modes): the model decides
        // start / switch / resume through the shared policy.
        refreshPresence()
        eventListener?.nfcControllerDidDetect(uid: uid, payload: payload)
    }

    private func settleTagScan(uid: String, payload: LumiTagData?) {
        let existing = tagByUid(uid)
        if let existing {
            eventListener?.nfcControllerDidSettleScan(.registeredTag(existing))
        } else {
            let memory = memoryRecord(uid: uid)
            eventListener?.nfcControllerDidSettleScan(.unknownTag(uid: uid, payload: payload, memory: memory))
        }
    }

    /// The card-write settle (the Android settleCardWrite, ported): the
    /// presented card is written the target tag's data ONLY when the card's
    /// NFC ID is the tag's own uid; a mismatched card is reported and left
    /// untouched. Before the per-card verdict the payload's own size is
    /// checked against the app's write cap - a payload over the cap is
    /// refused up front (a permanent size fact, never a retry-able card
    /// failure) with nothing written. A write that lands remembers the
    /// card's name + song list (the per-card memory).
    private func settleCardWrite(target: Tag, uid: String, tag: NFCNDEFTag) {
        guard uid == target.uid else {
            eventListener?.nfcControllerDidSettleScan(
                .cardWrite(message: "That card's NFC ID doesn't match this tag's - nothing was written."),
            )
            return
        }
        guard let data = cardWriteDataFor(target) else {
            eventListener?.nfcControllerDidSettleScan(
                .cardWrite(message: "Couldn't read this tag's data - nothing was written."),
            )
            return
        }
        let messageBytes = NdefCodec.messageByteLength(data)
        if messageBytes > NdefCodec.maxPayloadBytes {
            eventListener?.nfcControllerDidSettleScan(
                .cardWrite(
                    message:
                        "The tag's data is too large to save to a card - it needs \(messageBytes) bytes, the app writes at most \(NdefCodec.maxPayloadBytes). Nothing was written.",
                ),
            )
            return
        }
        // The physical write: the app's own AOSP-grammar bytes through the
        // tag's low-level write (the high-level writeNDEFMessages would
        // re-encode the record in CoreNFC's own shape - not the shape the
        // Android app reads).
        tag.write(Data(NdefCodec.toNdefMessageBytes(data))) { [weak self] error in
            guard let self else { return }
            if error == nil {
                self.onCardWriteLanded(target: target, data: data)
                self.eventListener?.nfcControllerDidSettleScan(.cardWrite(message: "Saved to the card."))
            } else {
                self.eventListener?.nfcControllerDidSettleScan(
                    .cardWrite(message: "Couldn't write to that card - keep it on the phone and try again."),
                )
            }
        }
    }

    /// The app model supplies the card-write payload (the tag's name + its
    /// ordered song list) and the per-card memory write.
    public var cardWriteDataFor: (Tag) -> LumiTagData? = { _ in nil }
    public var onCardWriteLanded: (Tag, LumiTagData) -> Void = { _, _ in }
    public var memoryRecord: (String) -> TagMemoryRecord? = { _ in nil }

    // MARK: - helpers

    /// The tag's uid as the app stores it: the identifier's bytes as an
    /// uppercase hex string (the Android tag uid's shape).
    public static func uidString(from identifier: Data) -> String {
        identifier.map { String(format: "%02X", $0) }.joined()
    }
}

// MARK: - NFCNDEFReaderSessionDelegate

extension NfcController: NFCNDEFReaderSessionDelegate {
    public func readerSessionWillBecomeActive(_ session: NFCNDEFReaderSession) {
        didBecomeActive = true
        session.pollForTags()
        startHeartbeat()
        if let uid = watchedUid {
            // Re-entry: re-seed the presence record from the carried
            // active uid (the Android reconfigureNfc re-seed), so a tag
            // already playing before the backgrounding is still watched.
            seedWatchedTag(uid: uid)
        }
    }

    public func readerSession(_ session: NFCNDEFReaderSession, didInvalidate reason: NFCNDEFReaderSession.InvalidationReason) {
        // A delayed callback from a session that has ALREADY been
        // replaced (a quick background -> foreground bounce tears the old
        // one down and starts a fresh one) must not tear down or re-arm
        // the CURRENT session: only the session we are holding
        // invalidates us.
        guard session === self.session else { return }
        let backgrounded = invalidatedForBackground
        teardownSession()
        guard !backgrounded else { return }
        // An invalidation that is NOT the app's own backgrounding tear-
        // down re-arms the session (the Android app re-configures its
        // always-listening dispatch on every resume): without the re-arm
        // a dead session stops reading until the next background/foreground
        // cycle, and the absence window then removes a tag that is still
        // held. The re-arm is off the invalidate callback's own stack and
        // rate-limited, so a persistent error cannot spin a re-arm loop.
        let now = Date()
        guard now.timeIntervalSince(lastRearm) >= 2 else { return }
        lastRearm = now
        DispatchQueue.main.async { [weak self] in
            self?.startListening()
        }
    }

    public func readerSession(_ session: NFCNDEFReaderSession, didDetect tags: [NFCNDEFTag]) {
        guard let tag = tags.first else { return }
        let uid = Self.uidString(from: tag.identifier)
        if let connected = connectedTag, Self.uidString(from: connected.identifier) != uid {
            // A DIFFERENT tag is in the field: switch to it (the Android
            // tag-switch: the new tag presentation cancels the old watch
            // and the read of the new uid settles normally).
            connectedTag = nil
            connectedUid = nil
        }
        session.connect(to: tag, animatedImageName: nil) { [weak self] error in
            guard let self, error == nil else { return }
            self.connectedTag = tag
            self.connectedUid = uid
            self.refreshPresence()
            // A probe-triggered re-detect of the tag still being watched
            // is a presence recovery, not a new presentation: its read
            // refreshes the presence on success (the cold-but-present
            // case - no strike) and confirms the removal on failure (the
            // second strike), and it NEVER re-dispatches the payload (a
            // probe-originated re-read must not undo a user pause/stop or
            // settle an armed scan). A re-detect after the removal is
            // already confirmed - or of any tag the probe did not target
            // - settles the read normally.
            let recovery = self.probeRecoveryUid == uid && self.watchedUid == uid
            if self.probeRecoveryUid == uid { self.probeRecoveryUid = nil }
            tag.read { [weak self] data, error in
                guard let self else { return }
                if recovery {
                    if error == nil, data != nil {
                        self.refreshPresence()
                    } else {
                        self.confirmRemoval()
                    }
                } else {
                    self.handleRead(data: data, readError: error, uid: uid, tag: tag)
                }
            }
        }
    }

    public func readerSession(_ session: NFCNDEFReaderSession, didEncounter error: NFCError) {
        // Session-level errors surface on the next heartbeat's probe; no
        // strike logic here (a session error with no held tag is the
        // absence-timeout fallback's territory).
    }
}

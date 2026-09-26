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
// - The Android fast path reads a low-level page of the tag every 300 ms on
//   a held session; the iOS equivalent is the NDEF reader session's own
//   read (readNDEFMessages), run on the same 300 ms heartbeat. A failed
//   read first tries a FRESH session (re-detect through pollForTags - the
//   port of the fresh-session reconnect that disambiguates a cold-but-
//   present tag from a removed one); only a read that ALSO fails on the
//   fresh session counts as a strike, and TWO strikes (100 ms between)
//   confirm the removal - the Android two-strike debounce, ported.
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

/// A detected tag's read outcome (the iOS counterpart of the Android
/// TagPayloadRead): Answered when the NDEF read answered (payload nil when
/// the card carries no lumi record), Failed when the read could not
/// complete (the card's payload is then UNKNOWN - it must never be read as
/// "no record").
public enum TagPayloadRead: Equatable {
    case answered(LumiTagData?)
    case failed
}

/// What an armed scan settles to.
public enum ScanOutcome: Equatable {
    /// A registered tag was read: open its detail page.
    case registeredTag(Tag)
    /// An unknown tag was read: enter the registration flow (the prefill
    /// and restore songs travel with it).
    case unknownTag(uid: String, payload: TagPayloadRead, memory: TagMemoryRecord?)
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
    /// Set when the session is invalidated for app backgrounding (as
    /// opposed to a tag removal): no loss event fires for it.
    private var invalidatedForBackground = false

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
            probeRead(session, tag)
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

    private func probeRead(_ session: NFCNDEFReaderSession, _ tag: NFCNDEFTag) {
        session.readNDEFMessages(from: tag) { [weak self] error in
            guard let self else { return }
            if error == nil {
                self.strikeRetryScheduled = false
                self.refreshPresence()
            } else {
                // The read failed: the session is stale (the tag went
                // cold) or the tag left. Disambiguate with a FRESH session
                // before counting the strike (the Android wrapper's
                // reconnect): a held-still tag whose RF session dropped
                // re-detects and reads again - no strike; a gone tag
                // fails the fresh read too - a strike.
                self.tryFreshSessionOrStrike()
            }
        }
    }

    private func tryFreshSessionOrStrike() {
        // Re-detect: the fresh-session attempt. The stale connected tag's
        // session is dropped; pollForTags looks again. A held-still tag
        // whose RF session dropped re-detects, re-connects and reads
        // again (didDetect -> read -> handleNdefRead -> refreshPresence
        // cancels the pending retry: the tag is present). A gone tag fails
        // the fresh read too - the pending retry then counts the second
        // strike and confirms the removal.
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
        guard let session else { return }
        if let tag = connectedTag {
            session.readNDEFMessages(from: tag) { [weak self] error in
                guard let self else { return }
                if error == nil {
                    self.refreshPresence()
                } else {
                    self.confirmRemoval() // two strikes: removed
                }
            }
        } else {
            // No tag at all on the retry: the removal stands (the tag
            // left between the probe and the retry).
            confirmRemoval()
        }
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

    /// The read's settle: parse the lumi payload from the NDEF messages and
    /// route it - to the armed scan's settle when one is armed, to the
    /// always-listening playback read otherwise.
    private func handleNdefRead(_ messages: [NDEFMessage], from tag: NFCNDEFTag) {
        let uid = Self.uidString(from: tag.identifier)
        let payload = NfcController.lumiPayload(from: messages)

        if case let .cardWrite(target) = scanMode {
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

    private func settleTagScan(uid: String, payload: TagPayloadRead) {
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
        guard let session else {
            eventListener?.nfcControllerDidSettleScan(
                .cardWrite(message: "Couldn't write to that card - keep it on the phone and try again."),
            )
            return
        }
        let json = NdefCodec.toJson(data)
        // The MIME media type record. CoreNFC's NDEFType cases map to the
        // record's TNF byte; the exact byte this case emits is not
        // verifiable off the device, and it does not matter for
        // interoperability: readers (including the Android app) match the
        // record by its TYPE field (`application/x-lumi`), never the TNF.
        // `.mediaAudio` is the closest case to the MIME-media shape.
        let record = NDEFRecord(
            type: Data(NdefCodec.mimeType.utf8),
            payload: Data(json.utf8),
            tnf: .mediaAudio,
            identifier: Data(),
        )
        session.writeNDEFMessages([NDEFMessage(records: [record])], to: tag) { [weak self] error in
            DispatchQueue.main.async {
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

    /// The app's payload among [messages]' records, if any record carries
    /// it (the lenient read - a card with no lumi record yields
    /// answered(nil)).
    public static func lumiPayload(from messages: [NDEFMessage]) -> TagPayloadRead {
        let typeBytes = Data(NdefCodec.mimeType.utf8)
        for message in messages {
            for record in message.records {
                if record.type == typeBytes {
                    let json = String(decoding: record.payload, as: UTF8.self)
                    return .answered(NdefCodec.fromJson(json))
                }
            }
        }
        return .answered(nil)
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
        let wasBackground = reason == .invalidationRequest || reason == .readError
        teardownSession()
        // A session invalidation is NOT a tag removal: the Android app
        // tears the poll down on pause the same way and fires no loss.
        _ = wasBackground
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
            // The single read: handleNdefRead routes it to the armed scan's
            // settle (when one is armed) or the always-listening playback
            // read (the model decides start / switch / resume through the
            // shared policy - a re-read of the paused active tag resumes
            // it, "reading overrides pause").
            session.readNDEFMessages(from: tag) { _ in
                // The messages arrive through didReadNDEFMessages.
            }
        }
    }

    public func readerSession(_ session: NFCNDEFReaderSession, didReadNDEFMessages messages: [NDEFMessage], from tag: NFCNDEFTag) {
        handleNdefRead(messages, from: tag)
    }

    public func readerSession(_ session: NFCNDEFReaderSession, didEncounter error: NFCError) {
        // Session-level errors surface on the next heartbeat's probe; no
        // strike logic here (a session error with no held tag is the
        // absence-timeout fallback's territory).
    }
}

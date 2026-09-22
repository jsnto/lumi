package com.lumi.player.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareUltralight
import android.nfc.tech.NfcA
import android.nfc.tech.TagTechnology
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.lumi.player.LumiApp
import com.lumi.player.data.PlayMode
import com.lumi.player.nfc.GenericPresencePoller
import com.lumi.player.nfc.GenericPresenceSession
import com.lumi.player.nfc.MifarePresencePoller
import com.lumi.player.nfc.PresenceReader
import com.lumi.player.nfc.ReconnectingPresenceSession
import com.lumi.player.player.PlaybackStatus
import com.lumi.player.player.PlayerStateHolder
import com.lumi.player.ui.theme.LumiTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Hosts the Compose UI and drives NFC.
 *
 * NFC strategy per play mode — in both modes NFC is always listening:
 * - Both modes: foreground dispatch delivers any tag in range to the
 *   activity (a self PendingIntent arriving via [onNewIntent]), so a read
 *   starts/switches playback without a tap and overrides play/pause.
 *   Foreground dispatch is the single receive path: it is the mechanism the
 *   tag-registration flow proved on real devices, whereas the reader-mode
 *   [NfcAdapter.ReaderCallback] (onTagDiscovered) never fired on a device
 *   where foreground dispatch worked.
 * - CONTINUOUS: the music keeps playing after the tag is removed; tag loss
 *   is never reported.
 * - PROXIMITY: tag loss has three detectors, all ending in the same
 *   handleTagLost pause. MIFARE FAST PATH: the Tag handle delivered by the
 *   foreground-dispatch read is held ([lastSeenTag]); while that tag is the
 *   active tag a background coroutine continuously reads page 0 of it
 *   ([MifarePresencePoller], Mifare Ultralight tags only) - every
 *   successful read refreshes the presence record, and a read that fails
 *   twice (a 100 ms retry between the strikes - a two-strike debounce
 *   against single-occurrence blips) confirms the removal well under a
 *   second. Every read goes through [ReconnectingPresenceSession]: a
 *   failed read first tries a FRESH session (a tag held still in range
 *   can go cold - its RF session drops while the tag stays in the
 *   field), and only a read that also fails on the fresh session counts
 *   as a removal strike. GENERIC FAST PATH: any other tag the Mifare
 *   path cannot read keeps a tag-tech session (NfcA, then IsoDep) alive
 *   ([GenericPresencePoller]); every successful probe refreshes the
 *   presence record, so a non-Mifare tag HELD STATIONARY no longer false
 *   -pauses after the absence window; a probe that fails twice (a 100 ms
 *   retry between the strikes - the same two-strike debounce as the Mifare
 *   fast path, the same fresh-session attempt first) confirms the removal
 *   through the same route.
 *   FALLBACK: "tag lost" is derived, conservatively, from a
 *   sustained absence of reports on the active tag, checked by a presence
 *   poll every second against the absence window (the `nfc_reread_seconds`
 *   setting - default 15 s, no user-facing settings UI - [presenceTimeoutMs]);
 *   it owns removal
 *   detection whenever no fast path can run (no Mifare Ultralight session
 *   AND no connectable generic tag-tech session). The held-still
 *   cold-session case the fast paths used to false-pause on is
 *   disambiguated by their [ReconnectingPresenceSession] reconnect
 *   (device-verified: a 30 s still hold kept playing and a removal still
 *   paused within ~1-2 s). Loss
 *   PAUSES playback (the session and the active tag are kept), so a
 *   re-read of the same tag resumes it.
 * - Tag registration: the same foreground dispatch, with the view model in
 *   capture mode, so any tag type can be registered.
 */
class MainActivity : ComponentActivity() {

    private val app: LumiApp get() = application as LumiApp
    private lateinit var viewModel: LumiViewModel
    private var nfc: NfcAdapter? = null
    private var nfcConfigured = false
    private var currentMode: PlayMode = PlayMode.CONTINUOUS

    // NFC availability for the UI prompt. [nfcMissing] = no NFC hardware;
    // [nfcDisabled] = hardware present but the radio is off. [showNfcPrompt]
    // is transient: raised on resume / when a tag read is requested, cleared
    // when the user chooses to dismiss it.
    private var nfcMissing by mutableStateOf(false)
    private var nfcDisabled by mutableStateOf(false)
    private var showNfcPrompt by mutableStateOf(false)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this, factory = lumiViewModelFactory(app))[LumiViewModel::class.java]
        nfc = NfcAdapter.getDefaultAdapter(this)

        requestNotificationPermissionIfNeeded()
        refreshNfcState()

        // The keep-screen-on setting can change while the app is open (the
        // settings checkbox), so apply/clear the window flag on every change
        // while the activity is started; onResume/onPause keep the flag in
        // sync with the foreground for the setting's current value.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.keepScreenOn.collectLatest { on ->
                    if (on) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            }
        }

        setContent {
            LumiTheme {
                LumiRoot(
                    viewModel = viewModel,
                    nfcMissing = nfcMissing,
                    nfcDisabled = nfcDisabled,
                    showNfcPrompt = showNfcPrompt,
                    onEnableNfc = ::launchNfcSettings,
                    onDismissNfcPrompt = { showNfcPrompt = false },
                )
            }
        }

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.scanActive.collectLatest { scanning ->
                        reconfigureNfc()
                        // Reading a tag is impossible with the radio off; tell
                        // the user instead of failing silently.
                        if (scanning && (nfcMissing || nfc?.isEnabled == false)) {
                            showNfcPrompt = true
                        }
                    }
                }
                launch {
                    viewModel.playMode.collectLatest { mode ->
                        currentMode = mode
                        reconfigureNfc()
                    }
                }
                launch {
                    // A tag becoming active (start or switch) is the point
                    // where its uid is observable: the policy switch happens
                    // asynchronously inside the view model after a Room
                    // query, so seed the presence record here rather than in
                    // handleTagDetected. Foreground dispatch may deliver a
                    // stationary tag only once, so the activating read can
                    // be its only report - without this seed the watcher
                    // would have nothing to time out and proximity mode
                    // would never stop.
                    viewModel.tagActivated.collectLatest { uid ->
                        lastSeenAt = SystemClock.elapsedRealtime()
                        lastSeenUid = uid
                        // Re-engage the presence poll for the newly active
                        // tag: tear down any stale timer and start a fresh
                        // one, even if the poll was torn down at the last
                        // playback stop.
                        if (currentMode == PlayMode.PROXIMITY) {
                            presenceHandler.removeCallbacks(presenceWatcher)
                            presenceHandler.postDelayed(presenceWatcher, PRESENCE_CHECK_MS)
                            // The activating read just delivered this tag's
                            // handle (kept in lastSeenTag): start the
                            // continuous-reading fast path for it.
                            if (lastSeenTagUid == uid) {
                                Log.i(TAG, "tag activated uid=$uid: seeding presence record and starting presence poll")
                                lastSeenTag?.let { startPresencePoll(it, uid) }
                            } else {
                                Log.i(TAG, "tag activated uid=$uid: presence record seeded, no tag handle on hand (fallback only)")
                            }
                        }
                    }
                }
                launch {
                    PlayerStateHolder.state.collectLatest { state ->
                        // Only IDLE with NO active tag is a real stop: the
                        // service's stopPlayback clears the tag along with
                        // the IDLE status. The transient IDLE that lingers
                        // during an ACTION_START start-up (the service sets
                        // the active tag on the holder BEFORE ExoPlayer
                        // reaches READY) must not be treated as a stop:
                        // treating it as one disowns the just-activated tag
                        // (a removal then pauses nothing, and a re-hold
                        // restarts the queue instead of resuming) and tears
                        // the presence poll down mid-hold.
                        if (state.status == PlaybackStatus.IDLE && state.activeTagUid == null) {
                            viewModel.onPlaybackStopped()
                            // Playback has stopped: tear the presence poll
                            // (fallback and fast path) down so no background
                            // work is left running. Both are re-armed when a
                            // tag next becomes active (see the tagActivated
                            // collector).
                            presenceHandler.removeCallbacks(presenceWatcher)
                            stopPresencePoll()
                            lastSeenUid = null
                        }
                    }
                }
                launch {
                    viewModel.uiEvents.collectLatest { message ->
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // Proximity-mode presence tracking. Under foreground dispatch a
    // stationary tag may be delivered only once per presentation, so the
    // presence record is the only proxy for "still in range"; only an
    // absence of reports that outlives the grace period is treated as
    // removal. Only the ACTIVE tag's reads touch the record, so
    // an unrelated tag read can never mask the active tag's removal.
    // Only proximity mode watches for tag loss; continuous keeps playing.
    private val presenceHandler = Handler(Looper.getMainLooper())
    private var lastSeenUid: String? = null
    @Volatile private var lastSeenAt: Long = 0L

    // Continuous-reading fast path (proximity mode, Mifare Ultralight tags
    // only): the Tag handle from the most recent foreground-dispatch
    // delivery, kept so the active tag can be read continuously instead of
    // waiting for the NFC stack to re-report it.
    @Volatile private var lastSeenTag: Tag? = null
    @Volatile private var lastSeenTagUid: String? = null

    // The active tag's continuous-reading poll (see startPresencePoll).
    private val nfcScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var presencePollJob: Job? = null
    private var presencePollUid: String? = null

    /**
     * Test seam: establishes a connected low-level read session on a tag.
     * Returns null when no session can be established (the tag is not a
     * Mifare Ultralight, or the connect fails) - then the absence-timeout
     * fallback owns removal detection for that tag.
     */
    private var establishPresenceSession: (Tag) -> PresenceReader? = this::defaultPresenceSession

    /**
     * Releases the tag-tech connection [tag] currently holds, if any.
     *
     * A [Tag] can hold at most ONE connected TagTechnology: the framework's
     * connect() succeeds at the binder level and then refuses to take the
     * tag's connection slot when it is already occupied (IOException "Only
     * one TagTechnology can be connected at a time"). The slot can be
     * occupied by the NFC stack's own dispatch/identification connection,
     * by this app's own earlier session on the same [Tag] instance (a
     * reconnect, an activity re-arm), or by another app's NFC connection -
     * and a delivered tag that still holds it makes every connect() fail.
     * On the Samsung A52 this killed both fast paths on every delivery,
     * leaving the 15 s absence-timeout fallback in charge of every tag
     * (device-proven).
     *
     * close() on ANY tech instance of the tag releases the slot: the
     * framework's close() resets the tag-level connection state
     * (setTechnologyDisconnected) and re-establishes the RF session
     * (INfcTag.reconnect), so the caller's own connect() can then take the
     * slot. It is a harmless no-op when the slot is empty.
     */
    private fun releaseExistingTagConnection(tag: Tag, holder: TagTechnology) {
        runCatching { holder.close() }.onFailure {
            // A failing close leaves whatever held the slot in place; the
            // caller's connect() may then still hit the single-slot
            // conflict (logged by the session-establishment failure).
            Log.w(TAG, "releasing the tag-tech connection failed uid=${tag.id.toHex()}", it)
        }
    }

    private fun defaultPresenceSession(tag: Tag): PresenceReader? {
        val mifare = MifareUltralight.get(tag) ?: return null
        releaseExistingTagConnection(tag, mifare)
        mifare.connect()
        return object : PresenceReader {
            override fun readPage0() {
                mifare.readPages(0)
            }
            override fun close() {
                runCatching { mifare.close() }
            }
        }
    }

    /**
     * Test seam: establishes a connected generic tag-tech session on a tag
     * the Mifare fast path cannot read. Returns null when no session can be
     * established (no NfcA/IsoDep tech, or the connect fails) - then the
     * absence-timeout fallback owns removal detection for that tag.
     */
    private var establishGenericPresenceSession: (Tag) -> GenericPresenceSession? =
        this::defaultGenericPresenceSession

    private fun defaultGenericPresenceSession(tag: Tag): GenericPresenceSession? {
        // Chain to whichever common tag-tech the tag exposes; a Mifare
        // Ultralight that the fast path failed to connect still answers as
        // an NfcA tag.
        val tech = NfcA.get(tag) ?: IsoDep.get(tag) ?: MifareUltralight.get(tag) ?: return null
        releaseExistingTagConnection(tag, tech)
        tech.connect()
        return object : GenericPresenceSession {
            override fun probe() {
                // A real low-level read: it only succeeds while the tag is
                // physically in the field, so an IOException out of range
                // is the removal signal.
                when (tech) {
                    is NfcA -> tech.transceive(byteArrayOf(0x93.toByte(), 0x20.toByte()))
                    is IsoDep -> tech.transceive(byteArrayOf(0x00, 0x20, 0x00, 0x00, 0x00))
                    is MifareUltralight -> tech.readPages(0)
                    else -> throw IOException("unsupported tag tech")
                }
            }
            override fun close() {
                runCatching { tech.close() }
            }
        }
    }

    private val presenceWatcher = object : Runnable {
        override fun run() {
            val uid = lastSeenUid
            if (uid != null && SystemClock.elapsedRealtime() - lastSeenAt > presenceTimeoutMs()) {
                Log.i(TAG, "fallback: no reports for ${SystemClock.elapsedRealtime() - lastSeenAt}ms > ${presenceTimeoutMs()}ms: tag lost uid=$uid")
                handleTagLost(uid)
                lastSeenUid = null
            }
            presenceHandler.postDelayed(this, PRESENCE_CHECK_MS)
        }
    }

    /**
     * The absence-tolerance window (the `nfc_reread_seconds` setting, seconds
     * - default 15; the user-facing "Tag re-read wait" setting was removed,
     * so it is only settable via [LumiViewModel.setNfcRereadSeconds]) as the
     * millisecond grace period the presence poll checks against.
     */
    private fun presenceTimeoutMs(): Long =
        (viewModel.nfcRereadSeconds.value.coerceIn(1, 120) * 1_000L)

    // The poll asks every PRESENCE_CHECK_MS whether the active tag has been
    // reported within the configured absence window. A stationary tag may be
    // reported only once per presentation, so the window must be long enough
    // that a still-present tag (which re-reports when it moves) keeps
    // playback going; only a sustained absence counts as removal.

    /**
     * Re-check NFC availability and keep the prompt in sync: raised while the
     * radio is unusable (and so dismissed after, e.g. enabling the radio),
     * cleared once it is usable again.
     */
    private fun refreshNfcState() {
        nfcMissing = nfc == null
        nfcDisabled = !nfcMissing && !nfc!!.isEnabled
        showNfcPrompt = nfcMissing || nfcDisabled
    }

    /** Opens the system NFC settings, falling back to the settings home. */
    private fun launchNfcSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(this, "Could not open NFC settings", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ---- NFC wiring ----

    private val foregroundDispatchPendingIntent by lazy {
        val intent = Intent(this, this.javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        android.app.PendingIntent.getActivity(
            this,
            0,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE,
        )
    }

    private fun reconfigureNfc() {
        if (!nfcConfigured) return
        val nfc = nfc ?: return
        // A presence poll for the tag that is about to be re-seeded below is
        // already running (a resume while that tag plays): keep it. Tearing
        // it down and re-establishing on the same Tag handle races the
        // cancelled loop's teardown - whose finally closes the tag's single
        // tag-technology connection slot - with the fresh establish's
        // connect() (device-proven on the Samsung A52: the fresh connect
        // then hit "Only one TagTechnology can be connected at a time" and
        // the fast path died, leaving the 15 s fallback to pause a tag that
        // was still held in range). The running loop's session is cold after
        // the background stay, but that is exactly what its fresh-session
        // reconnect is for; if the tag is out of range its probes strike
        // and it pauses through the normal route.
        val keepRunningPoll = currentMode == PlayMode.PROXIMITY &&
            presencePollUid == viewModel.activeTagUid &&
            presencePollJob?.isActive == true
        nfc.disableForegroundDispatch(this)
        if (!keepRunningPoll) {
            stopPresencePoll()
            lastSeenUid = null
        }
        if (!nfc.isEnabled) return
        // Always listening: foreground dispatch delivers any tag in range to
        // this activity while it is in the foreground, for both main-page
        // reads and registration captures. This is the receive path proven
        // on a real device (reader mode's onTagDiscovered did not fire there).
        nfc.enableForegroundDispatch(this, foregroundDispatchPendingIntent, null, null)
        // Only proximity derives "tag lost" from a presence timeout.
        if (currentMode == PlayMode.PROXIMITY) {
            // A mode switch into proximity (or a resume) resets the record;
            // if a tag's list was already playing, carry its uid over so the
            // fallback poll has something to time out on.
            viewModel.activeTagUid?.let { uid ->
                lastSeenAt = SystemClock.elapsedRealtime()
                lastSeenUid = uid
                // A tag already playing before the switch may still be in
                // range: if its handle is on hand, resume the
                // continuous-reading fast path for it - unless the running
                // poll above already covers it.
                if (!keepRunningPoll && lastSeenTagUid == uid) {
                    lastSeenTag?.let { startPresencePoll(it, uid) }
                }
            }
            // Re-arm the fallback watcher exactly once: it self-reschedules
            // every tick, so remove the pending callback before posting a
            // fresh one.
            presenceHandler.removeCallbacks(presenceWatcher)
            presenceHandler.postDelayed(presenceWatcher, PRESENCE_CHECK_MS)
        } else {
            presenceHandler.removeCallbacks(presenceWatcher)
        }
    }

    private fun handleTagDetected(uid: String, tag: Tag) {
        if (uid.isEmpty()) return
        Log.i(TAG, "tag detected uid=$uid active=${viewModel.activeTagUid} mode=$currentMode")
        // Keep the delivered Tag handle: the continuous-reading fast path
        // needs the live handle, not just the uid.
        lastSeenTag = tag
        lastSeenTagUid = uid
        // The presence record is seeded when a tag BECOMES active (see the
        // viewModel.tagActivated collector) and refreshed by a re-read of
        // the tag already playing. A read of some OTHER tag while one is
        // playing must not touch the record, or it would mask the active
        // tag's removal.
        if (uid == viewModel.activeTagUid) {
            lastSeenAt = SystemClock.elapsedRealtime()
            lastSeenUid = uid
            if (currentMode == PlayMode.PROXIMITY) {
                // A (re-)presentation of the active tag starts (or restarts,
                // cancelling any stale loop) the continuous-reading poll.
                startPresencePoll(tag, uid)
            }
        } else if (currentMode == PlayMode.PROXIMITY) {
            // A new tag presentation cancels the previous tag's poll; the
            // new tag's loop starts when it becomes active (tagActivated).
            stopPresencePoll()
        }
        viewModel.onTagDetected(uid, tag)
    }

    private fun handleTagLost(uid: String) {
        if (uid.isEmpty()) return
        Log.i(TAG, "handleTagLost uid=$uid (proximity tag loss -> pause)")
        viewModel.onTagLost(uid)
    }

    /**
     * Starts (or restarts) the presence poll for the active tag. Whichever
     * fast path can run (Mifare page-0 reads, or a connected generic
     * tag-tech session) refreshes the presence record so the
     * absence-timeout fallback stays quiet while the tag stays present; a
     * confirmed removal pauses through the same [handleTagLost] route as
     * the fallback and clears the record so the fallback does not
     * double-report the loss.
     *
     * A fast-path read/probe is only a "tag removed" strike after a fresh
     * session has tried and failed too ([ReconnectingPresenceSession]):
     * under foreground dispatch a tag held still in range can go cold -
     * its RF session drops while the tag is physically still in the
     * reader's field, and reads on the stale session fail. Without the
     * fresh-session attempt a stationary tag would confirm a removal
     * (and pause) within ~500 ms of the activating read, device-proven.
     */
    private fun startPresencePoll(tag: Tag, uid: String) {
        // A poll for this exact tag is already running: its fast path is
        // refreshing the presence record (or its session was refused and
        // the absence-timeout fallback owns it). A re-delivery of the same
        // tag (a re-read, a resume re-arm) must not tear the running poll
        // down and restart it: the restart races the running loop's
        // teardown, and the cancelled loop's finally closes the shared
        // [Tag]'s connection slot out from under the fresh session.
        if (uid == presencePollUid && presencePollJob?.isActive == true) return
        // A new tag presentation always cancels the previous tag's poll.
        stopPresencePoll()
        presencePollUid = uid
        presencePollJob = nfcScope.launch {
            // Mifare fast path first: a connectable Mifare Ultralight
            // session reads page 0 continuously.
            val reader = withContext(Dispatchers.IO) {
                try {
                    establishPresenceSession(tag)
                } catch (e: Exception) {
                    // No session (not a Mifare Ultralight, connect failed,
                    // ...): fall through to the generic fast path.
                    Log.w(TAG, "Mifare presence session failed for uid=$uid (falling through to generic fast path)", e)
                    null
                }
            }
            if (reader != null) {
                Log.i(TAG, "presence poll: Mifare fast path active for uid=$uid")
                runMifarePresenceLoop(reader, uid, tag)
                return@launch
            }
            // Generic fast path: any other tag keeps a tag-tech session
            // connected and is probed with a real low-level read every
            // tick; a probe that fails twice confirms the removal.
            val session = withContext(Dispatchers.IO) {
                try {
                    establishGenericPresenceSession(tag)
                } catch (e: Exception) {
                    // No connectable session: the absence-timeout fallback
                    // owns this tag.
                    Log.w(TAG, "generic presence session failed for uid=$uid (absence-timeout fallback owns this tag)", e)
                    null
                }
            } ?: return@launch
            Log.i(TAG, "presence poll: generic fast path active for uid=$uid")
            withContext(Dispatchers.IO) {
                // The session can go cold under foreground dispatch while
                // the tag is still in range (see runMifarePresenceLoop):
                // the fresh-session reconnect disambiguates "cold but
                // present" (a fresh session reads it -> present) from
                // "gone" (no fresh session can read it -> strike).
                val poller = ReconnectingPresenceSession(
                    initial = session,
                    readOf = { it.probe() },
                    reconnect = {
                        val fresh = establishGenericPresenceSession(tag)
                        if (fresh == null) {
                            Log.w(TAG, "generic reconnect: no fresh session uid=$uid (strike stands)")
                        } else {
                            // Liveness: a fresh session was adopted or
                            // established on a failed probe.
                            Log.i(TAG, "generic reconnect: fresh session established uid=$uid")
                        }
                        fresh
                    },
                    closeOf = { it.close() },
                )
                // Probe liveness counter (per-loop, single-threaded).
                var diagProbeOk = 0
                try {
                    GenericPresencePoller.loop(
                        probe = {
                            try {
                                poller.read()
                                // Liveness heartbeat so a silent success (no
                                // logs) is distinguishable from a dead loop.
                                diagProbeOk++
                                if (diagProbeOk == 1 || diagProbeOk % 20 == 0) {
                                    Log.i(TAG, "generic probe OK uid=$uid count=$diagProbeOk")
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: IOException) {
                                // [poller] only throws IOException (a read
                                // that ALSO failed on a fresh session) or
                                // CancellationException.
                                Log.w(TAG, "generic probe FAILED (strike) uid=$uid: ${e.javaClass.simpleName}: ${e.message}")
                                throw e
                            }
                        },
                        onStillPresent = {
                            presenceHandler.post {
                                if (lastSeenUid == uid) {
                                    lastSeenAt = SystemClock.elapsedRealtime()
                                }
                            }
                        },
                        onConfirmedRemoved = {
                            Log.i(TAG, "generic fast path: removal CONFIRMED (two failed probes) uid=$uid")
                            presenceHandler.post {
                                if (lastSeenUid == uid) {
                                    handleTagLost(uid)
                                    // The fast path already reported the
                                    // loss: clear the record so the
                                    // fallback poll does not issue a second
                                    // pause for the same removal (a re-read
                                    // re-seeds the record via the normal
                                    // activation path).
                                    lastSeenUid = null
                                }
                            }
                        },
                    )
                } finally {
                    // Cancellation (tag switch, mode change, playback stop)
                    // exits through this finally without reporting the
                    // removal; only a confirmed probe removal does.
                    // [ReconnectingPresenceSession.close] closes whichever
                    // session the wrapper currently holds (the initial
                    // one, or one adopted through a reconnect); sessions
                    // superseded or rejected along the way were already
                    // closed by the wrapper.
                    poller.close()
                }
            }
        }
    }

    /**
     * The Mifare fast path: every successful page-0 read refreshes the
     * presence record, so the absence-timeout fallback never fires while
     * the tag answers; a confirmed removal (two failed reads) pauses
     * through the same [handleTagLost] route as the fallback and clears
     * the record so the fallback does not double-report the loss.
     *
     * A read failure must not count as a removal strike until a FRESH
     * session has tried and failed too: under foreground dispatch a tag
     * held still in range goes cold - the phone's RF session for it drops
     * while the tag is physically still in the reader's field, and reads
     * on the stale session fail. [ReconnectingPresenceSession] is the
     * disambiguator: a cold tag answers a fresh connect + read (the loop
     * treats that as "still present" and keeps playing), a genuinely
     * removed tag answers neither (the strike stands and the removal is
     * confirmed by the normal two-strike debounce).
     */
    private suspend fun runMifarePresenceLoop(reader: PresenceReader, uid: String, tag: Tag) {
        withContext(Dispatchers.IO) {
            val session = ReconnectingPresenceSession(
                initial = reader,
                readOf = { it.readPage0() },
                reconnect = {
                    val fresh = establishPresenceSession(tag)
                    if (fresh == null) {
                        Log.w(TAG, "Mifare reconnect: no fresh session uid=$uid (strike stands)")
                    } else {
                        // Liveness: a fresh session was adopted or
                        // established on a failed read.
                        Log.i(TAG, "Mifare reconnect: fresh session established uid=$uid")
                    }
                    fresh
                },
                closeOf = { it.close() },
            )
            // Read liveness counter (per-loop, single-threaded).
            var diagProbeOk = 0
            try {
                MifarePresencePoller.loop(
                    readPage0 = {
                        try {
                            session.read()
                            // Liveness heartbeat so a silent success (no
                            // logs) is distinguishable from a dead loop.
                            diagProbeOk++
                            if (diagProbeOk == 1 || diagProbeOk % 20 == 0) {
                                Log.i(TAG, "Mifare probe OK uid=$uid count=$diagProbeOk")
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: IOException) {
                            // [session] only throws IOException (a read that
                            // ALSO failed on a fresh session) or
                            // CancellationException.
                            Log.w(TAG, "Mifare page-0 read FAILED (strike) uid=$uid: ${e.javaClass.simpleName}: ${e.message}")
                            throw e
                        }
                    },
                    onStillPresent = {
                        presenceHandler.post {
                            if (lastSeenUid == uid) {
                                lastSeenAt = SystemClock.elapsedRealtime()
                            }
                        }
                    },
                    onConfirmedRemoved = {
                        Log.i(TAG, "Mifare fast path: removal CONFIRMED (two failed reads) uid=$uid")
                        presenceHandler.post {
                            if (lastSeenUid == uid) {
                                handleTagLost(uid)
                                // The fast path already reported the loss:
                                // clear the record so the fallback poll does
                                // not issue a second pause for the same removal
                                // (a re-read re-seeds the record via the normal
                                // activation path).
                                lastSeenUid = null
                            }
                        }
                    },
                )
            } finally {
                // Cancellation (tag switch, mode change, playback stop) or
                // a confirmed removal exits through this finally: close
                // whichever session the wrapper currently holds; sessions
                // superseded or rejected along the way were already closed
                // by the wrapper.
                session.close()
            }
        }
    }

    /** Cancels the active tag's continuous-reading poll, if any. */
    private fun stopPresencePoll() {
        presencePollJob?.cancel()
        presencePollJob = null
        presencePollUid = null
    }

    override fun onResume() {
        super.onResume()
        // Keep the screen on for the activity's lifetime when the user
        // enabled the keep-screen-on setting (cleared in onPause); otherwise
        // the screen follows the system timeout while the app is open.
        if (viewModel.keepScreenOn.value) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        nfcConfigured = true
        reconfigureNfc()
        refreshNfcState()
        // A tag tapped while the app was starting arrives with the resume.
        handleNfcIntent(intent)
    }

    override fun onPause() {
        if (nfcConfigured) {
            nfc?.disableForegroundDispatch(this)
        }
        presenceHandler.removeCallbacks(presenceWatcher)
        stopPresencePoll()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNfcIntent(intent)
    }

    private fun handleNfcIntent(intent: Intent?) {
        if (intent?.hasExtra(NfcAdapter.EXTRA_TAG) == true) {
            @Suppress("DEPRECATION") // getParcelableExtra(Class) is API 33+; min is 26.
            val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG) ?: return
            // Consume the tag so a stale tag intent is not reprocessed on a
            // later resume (e.g. after the app is backgrounded and reopened);
            // a fresh tag presentation always delivers a fresh intent.
            intent.removeExtra(NfcAdapter.EXTRA_TAG)
            handleTagDetected(tag.id.toHex(), tag)
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

    companion object {
        private const val PRESENCE_CHECK_MS = 1_000L
        private const val TAG = "LumiNfc"
    }
}

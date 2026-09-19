package com.lumi.player.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
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
import com.lumi.player.player.PlaybackStatus
import com.lumi.player.player.PlayerStateHolder
import com.lumi.player.ui.theme.LumiTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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
 * - PROXIMITY: "tag lost" is derived, conservatively, from a short absence
 *   of reports on the active tag, checked by a presence poll every second.
 *   Foreground dispatch may deliver a stationary tag only once per
 *   presentation, so "no further reports" is the only removal signal
 *   available — a held-still tag may trigger the timeout early; verifying
 *   pause-on-removal timing on a real device is the first on-device
 *   validation item. Loss PAUSES playback (the session and the active tag
 *   are kept), so a re-read of the same tag resumes it.
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
                    viewModel.registering.collectLatest { registering ->
                        reconfigureNfc()
                        // Reading a tag is impossible with the radio off; tell
                        // the user instead of failing silently.
                        if (registering && (nfcMissing || nfc?.isEnabled == false)) {
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
                        }
                    }
                }
                launch {
                    PlayerStateHolder.state.collectLatest { state ->
                        if (state.status == PlaybackStatus.IDLE) {
                            viewModel.onPlaybackStopped()
                            // Playback has stopped: tear the presence poll
                            // down so no background work is left running.
                            // It is re-armed when a tag next becomes active
                            // (see the tagActivated collector).
                            presenceHandler.removeCallbacks(presenceWatcher)
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
    // stationary tag may be delivered only once, so the presence record is
    // the only proxy for "still in range"; a short absence of reports is
    // treated as removal. Only the ACTIVE tag's reads touch the record, so
    // an unrelated tag read can never mask the active tag's removal.
    // Only proximity mode watches for tag loss; continuous keeps playing.
    private val presenceHandler = Handler(Looper.getMainLooper())
    private var lastSeenUid: String? = null
    @Volatile private var lastSeenAt: Long = 0L
    private val presenceWatcher = object : Runnable {
        override fun run() {
            val uid = lastSeenUid
            if (uid != null && SystemClock.elapsedRealtime() - lastSeenAt > PRESENCE_TIMEOUT_MS) {
                handleTagLost(uid)
                lastSeenUid = null
            }
            presenceHandler.postDelayed(this, PRESENCE_CHECK_MS)
        }
    }
    // The poll asks every PRESENCE_CHECK_MS whether the active tag is still
    // being reported; PRESENCE_TIMEOUT_MS of absence counts as removal. The
    // timeout stays longer than one poll period because a stationary tag
    // may be reported only once per presentation.

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
        nfc.disableForegroundDispatch(this)
        presenceHandler.removeCallbacks(presenceWatcher)
        lastSeenUid = null
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
            // poll has something to time out on. Accepted tradeoff: a
            // still-present stationary tag, already reported once, pauses
            // after the timeout - "no further reports" is the only removal
            // signal under foreground dispatch.
            viewModel.activeTagUid?.let { uid ->
                lastSeenAt = SystemClock.elapsedRealtime()
                lastSeenUid = uid
            }
            presenceHandler.postDelayed(presenceWatcher, PRESENCE_CHECK_MS)
        }
    }

    private fun handleTagDetected(uid: String, tag: Tag) {
        if (uid.isEmpty()) return
        // The presence record is seeded when a tag BECOMES active (see the
        // viewModel.tagActivated collector) and refreshed by a re-read of
        // the tag already playing. A read of some OTHER tag while one is
        // playing must not touch the record, or it would mask the active
        // tag's removal.
        if (uid == viewModel.activeTagUid) {
            lastSeenAt = SystemClock.elapsedRealtime()
            lastSeenUid = uid
        }
        viewModel.onTagDetected(uid, tag)
    }

    private fun handleTagLost(uid: String) {
        if (uid.isEmpty()) return
        viewModel.onTagLost(uid)
    }

    override fun onResume() {
        super.onResume()
        // Keep the screen on for the activity's lifetime so NFC reads and
        // playback are never interrupted by the screen locking, sleeping, or
        // going dark while the app is open.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        private const val PRESENCE_TIMEOUT_MS = 2_000L
    }
}

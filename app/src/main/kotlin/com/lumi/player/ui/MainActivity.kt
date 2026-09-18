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
import com.lumi.player.player.PlayerStateHolder
import com.lumi.player.ui.theme.LumiTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Hosts the Compose UI and drives NFC.
 *
 * NFC strategy per play mode — in both modes NFC is always listening:
 * - Both modes: reader mode passively reads any tag in range, so a read
 *   starts/switches playback without a tap and overrides play/pause.
 * - CONTINUOUS: the music keeps playing after the tag is removed; tag loss
 *   is never reported.
 * - PROXIMITY: the reader re-reports the tag while it stays in range, so
 *   "tag lost" is derived from a short absence of reports (presence
 *   timeout), which stops playback when the tag is removed.
 * - Tag registration: temporarily foreground dispatch while the capture
 *   dialog is showing, so any tag type can be registered.
 */
class MainActivity : ComponentActivity() {

    private val app: LumiApp get() = application as LumiApp
    private lateinit var viewModel: LumiViewModel
    private var nfc: NfcAdapter? = null
    private var nfcConfigured = false

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
                    PlayerStateHolder.state.collectLatest { state ->
                        if (state.status == com.lumi.player.player.PlaybackStatus.IDLE) {
                            viewModel.onPlaybackStopped()
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

    private var currentMode: PlayMode = PlayMode.CONTINUOUS

    // Proximity-mode presence tracking. Reader mode re-reports the tag while
    // it stays in range; if reports stop, the tag left the reader. Only
    // proximity mode watches for tag loss; continuous keeps playing.
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
        nfc.disableReaderMode(this)
        presenceHandler.removeCallbacks(presenceWatcher)
        lastSeenUid = null
        if (!nfc.isEnabled) return
        when {
            viewModel.registering.value ->
                nfc.enableForegroundDispatch(this, foregroundDispatchPendingIntent, null, null)
            else -> {
                // Always listening: reader mode passively reads any tag in
                // range, so a read starts/switches playback in both modes.
                nfc.enableReaderMode(
                    this,
                    nfcReaderCallback,
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                    null,
                )
                // Only proximity derives "tag lost" from a presence timeout.
                if (currentMode == PlayMode.PROXIMITY) {
                    presenceHandler.postDelayed(presenceWatcher, PRESENCE_CHECK_MS)
                }
            }
        }
    }

    private val nfcReaderCallback = object : NfcAdapter.ReaderCallback {
        override fun onTagDiscovered(tag: Tag) {
            val uid = tag.id.toHex()
            if (uid == viewModel.activeTagUid) {
                lastSeenAt = SystemClock.elapsedRealtime()
                lastSeenUid = uid
            }
            handleTagDetected(uid)
        }
    }

    private fun handleTagDetected(uid: String) {
        if (uid.isEmpty()) return
        viewModel.onTagDetected(uid)
    }

    private fun handleTagLost(uid: String) {
        if (uid.isEmpty()) return
        viewModel.onTagLost(uid)
    }

    override fun onResume() {
        super.onResume()
        nfcConfigured = true
        reconfigureNfc()
        refreshNfcState()
        // A tag tapped while the app was starting arrives with the resume.
        handleNfcIntent(intent)
    }

    override fun onPause() {
        if (nfcConfigured) {
            nfc?.let {
                it.disableReaderMode(this)
                it.disableForegroundDispatch(this)
            }
        }
        presenceHandler.removeCallbacks(presenceWatcher)
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
            handleTagDetected(tag.id.toHex())
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

    companion object {
        private const val PRESENCE_CHECK_MS = 500L
        private const val PRESENCE_TIMEOUT_MS = 2_000L
    }
}

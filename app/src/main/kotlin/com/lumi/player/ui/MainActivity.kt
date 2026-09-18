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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
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
 * NFC strategy per play mode:
 * - CONTINUOUS: foreground dispatch. Every tap delivers the tag UID; the
 *   view model decides whether to start or ignore it.
 * - PROXIMITY: reader mode. The reader re-reports the tag while it stays in
 *   range, so "tag lost" is derived from a short absence of reports
 *   (presence timeout), which stops playback when the tag is removed.
 * - Tag registration: temporarily foreground dispatch while the capture
 *   dialog is showing, so any tag type can be registered.
 */
class MainActivity : ComponentActivity() {

    private val app: LumiApp get() = application as LumiApp
    private lateinit var viewModel: LumiViewModel
    private lateinit var nfc: NfcAdapter
    private var nfcConfigured = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this, factory = lumiViewModelFactory(app))[LumiViewModel::class.java]
        nfc = NfcAdapter.getDefaultAdapter(this)

        requestNotificationPermissionIfNeeded()

        setContent {
            LumiTheme {
                LumiRoot(viewModel)
            }
        }

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.registering.collectLatest { reconfigureNfc() }
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
    // it stays in range; if reports stop, the tag left the reader.
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
        nfc.disableForegroundDispatch(this)
        nfc.disableReaderMode(this)
        presenceHandler.removeCallbacks(presenceWatcher)
        lastSeenUid = null
        if (!nfc.isEnabled) return
        when {
            viewModel.registering.value -> nfc.enableForegroundDispatch(this, foregroundDispatchPendingIntent, null, null)
            currentMode == PlayMode.PROXIMITY -> {
                nfc.enableReaderMode(
                    this,
                    nfcReaderCallback,
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                    null,
                )
                presenceHandler.postDelayed(presenceWatcher, PRESENCE_CHECK_MS)
            }

            else -> nfc.enableForegroundDispatch(this, foregroundDispatchPendingIntent, null, null)
        }
    }

    private val nfcReaderCallback = object : NfcAdapter.ReaderCallback {
        override fun onTagDiscovered(tag: Tag) {
            lastSeenAt = SystemClock.elapsedRealtime()
            lastSeenUid = tag.id.toHex()
            handleTagDetected(lastSeenUid!!)
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
        // A tag tapped while the app was starting arrives with the resume.
        handleNfcIntent(intent)
    }

    override fun onPause() {
        if (nfcConfigured) {
            nfc.disableReaderMode(this)
            nfc.disableForegroundDispatch(this)
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
        if (intent?.action == NfcAdapter.ACTION_TAG_DISCOVERED) {
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

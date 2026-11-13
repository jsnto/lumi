package com.lumi.player.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.lumi.player.LumiApp
import com.lumi.player.data.LumiThemeMode
import com.lumi.player.ui.theme.LumiDarkBackground
import com.lumi.player.ui.theme.LumiTheme
import com.lumi.player.ui.theme.resolvedDarkTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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
 * - Tag registration: the same foreground dispatch, with the view model in
 *   capture mode, so any tag type can be registered.
 *
 * The activity owns the receive dispatch and the lifecycle wiring only.
 * The presence machine - the proximity tag-loss detection (the presence
 * record, the absence-timeout fallback, and the continuous-reading fast
 * paths) - lives in [PresenceFallbackEngine]: it is constructed here with
 * the NFC adapter the platform resolved (the engine's reconfigureNfc seam)
 * and the activity's lifecycle + scope (its collectors run while the
 * activity is STARTED), the tag dispatch
 * ([handleNfcIntent] -> [PresenceFallbackEngine.onTagDetected]) and the
 * resume / pause / re-configuration are forwarded to it, and the scan
 * re-configuration below delegates to it - the same delegation the activity
 * does to the view model's [AutoLockEngine] surfaces.
 */
class MainActivity : ComponentActivity() {

    private val app: LumiApp get() = application as LumiApp
    private lateinit var viewModel: LumiViewModel

    /**
     * The presence machine (the proximity tag-loss detection: the presence
     * record, the absence-timeout fallback, the continuous-reading fast
     * paths - see [PresenceFallbackEngine]). Constructed in onCreate; the
     * activity forwards its dispatch and lifecycle to it.
     */
    internal lateinit var presenceEngine: PresenceFallbackEngine

    // NFC availability for the UI prompt. [nfcMissing] = no NFC hardware;
    // [nfcDisabled] = hardware present but the radio is off. [showNfcPrompt]
    // is transient: raised on resume / when a tag read is requested, cleared
    // when the user chooses to dismiss it.
    private var nfcMissing by mutableStateOf(false)
    private var nfcDisabled by mutableStateOf(false)
    private var showNfcPrompt by mutableStateOf(false)

    // The NFC radio's state can change while the app is open (a
    // quick-settings toggle, without the activity ever leaving the
    // foreground): the state-changed broadcast re-syncs the usability
    // flags (the prompt AND the tag page's save-to-card control, which
    // observes them through the view model) so they go live without a
    // restart or re-entry.
    private val nfcStateChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshNfcState()
        }
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this, factory = lumiViewModelFactory(app))[LumiViewModel::class.java]
        val initialThemeMode = runCatching {
            runBlocking { viewModel.repository.observeThemeMode().first() }
        }.getOrDefault(LumiThemeMode.FOLLOW_SYSTEM)

        // The presence machine (the proximity tag-loss detection - the
        // presence record, the absence-timeout fallback, and the
        // continuous-reading fast paths) is driven from this activity: it
        // takes the NFC adapter the platform resolved (its reconfigureNfc
        // seam) and the activity's lifecycle + scope (its collectors run
        // while the activity is STARTED - no presence work survives a stop
        // or a backgrounded activity).
        presenceEngine = PresenceFallbackEngine(
            activity = this,
            nfc = NfcAdapter.getDefaultAdapter(this),
            viewModel = viewModel,
            lifecycle = lifecycle,
            scope = lifecycleScope,
        )

        // The radio's state-changed broadcast (registered for the whole
        // activity lifetime, released in onDestroy): the platform posts it
        // when the user toggles the NFC radio, including while this app
        // stays in the foreground (a quick-settings toggle). The project's
        // stub SDK omits the legacy ACTION_STATE_CHANGED alias, so the
        // filter uses the modern constant the platform broadcasts.
        val nfcStateFilter = IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(nfcStateChangedReceiver, nfcStateFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(nfcStateChangedReceiver, nfcStateFilter)
        }

        requestNotificationPermissionIfNeeded()
        refreshNfcState()

        // The keep-screen-on setting can change while the app is open (the
        // Advanced options page's checkbox), so apply/clear the window flag
        // on every
        // change of its EFFECTIVE value while the activity is started: the
        // user's choice AND the auto-lock not having claimed the screen (the
        // lock wins over keep-screen-on until music plays again). onResume
        // /onPause keep the flag in sync with the foreground for the
        // setting's current effective value.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.keepScreenOnEffective.collectLatest { on ->
                    if (on) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            }
        }

        setContent {
            // The appearance choice (the settings screen's "Appearance"
            // option): applied app-wide through the theme, effective
            // immediately on change - no app restart required.
            val themeMode by viewModel.themeMode.collectAsState(
                initial = initialThemeMode,
            )
            // The setup gate follows the store live (a fresh install walks
            // the wizard into the app; a returning user's stored
            // "complete" lands the player on the first frame - the view
            // model reads the store synchronously at construction to seed
            // this StateFlow, so its current value IS the seed). Tests
            // create the activity AFTER their pre-seed write (manual
            // setup, see the test classes), so the store write never
            // lands mid-composition.
            val setupComplete by viewModel.setupComplete.collectAsState(
                initial = viewModel.setupComplete.value,
            )
            val darkTheme = resolvedDarkTheme(themeMode)
            // The window chrome (status bar, the light-status-bar icons,
            // and the window background the manifest's fixed-light theme
            // painted behind the first frame) follows the SAME resolved
            // mode as the Compose tree, so the frame never diverges from
            // the UI: dark mode takes the dark background on the status
            // bar and clears the white flash at launch; light mode
            // restores the captured manifest chrome byte-identical.
            @Suppress("DEPRECATION") // navigationBarColor is deprecated in the API 35 stub
            val originalChrome = remember {
                OriginalWindowChrome(
                    statusBarColor = window.statusBarColor,
                    navigationBarColor = window.navigationBarColor,
                    systemUiVisibility = window.decorView.systemUiVisibility,
                    background = window.decorView.background,
                )
            }
            LaunchedEffect(darkTheme) {
                window.applyWindowChrome(darkTheme, originalChrome)
            }
            LumiTheme(themeMode) {
                LumiRoot(
                    viewModel = viewModel,
                    nfcMissing = nfcMissing,
                    nfcDisabled = nfcDisabled,
                    showNfcPrompt = showNfcPrompt,
                    onEnableNfc = ::launchNfcSettings,
                    onDismissNfcPrompt = { showNfcPrompt = false },
                    setupComplete = setupComplete,
                )
            }
        }

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.scanActive.collectLatest { scanning ->
                        presenceEngine.reconfigureNfc()
                        // Reading a tag is impossible with the radio off; tell
                        // the user instead of failing silently.
                        if (scanning && (nfcMissing || presenceEngine.nfc?.isEnabled == false)) {
                            showNfcPrompt = true
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

    /**
     * Re-check NFC availability and keep the prompt in sync: raised while the
     * radio is unusable (and so dismissed after, e.g. enabling the radio),
     * cleared once it is usable again.
     */
    private fun refreshNfcState() {
        val nfc = presenceEngine.nfc
        nfcMissing = nfc == null
        nfcDisabled = !nfcMissing && !nfc!!.isEnabled
        showNfcPrompt = nfcMissing || nfcDisabled
        // Publish the usability for the UI below the activity (the tag
        // details page's save-to-card control observes these through the
        // view model): a card write needs the phone's own radio, so the
        // control greys out - live - while the radio is unusable.
        viewModel.setNfcUsability(nfcMissing, nfcDisabled)
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

    override fun onResume() {
        super.onResume()
        // Keep the screen on for the activity's lifetime when the effective
        // keep-screen-on value is on - the user's setting AND the auto-lock
        // not having claimed the idle screen (the lock wins; cleared in
        // onPause); otherwise the screen follows the system timeout while
        // the app is open.
        if (viewModel.keepScreenOnEffective.value) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        // The auto-lock's foreground gate (see [LumiViewModel.runAutoLockTick]):
        // the screen is locked only while the app is in the foreground.
        viewModel.appInForeground = true
        // The presence machine re-arms its receive path and re-seeds its
        // record for the carried active tag (a resume while a tag plays).
        presenceEngine.onForeground()
        refreshNfcState()
        // A tag tapped while the app was starting arrives with the resume.
        handleNfcIntent(intent)
        // The settings "Update" area's install-settings return (the "install
        // unknown apps" permission may have been granted, or still be off):
        // the engine re-checks and launches the kept install or offers the
        // retry path. A no-op on every other resume (the engine's own flag
        // only arms after it sent the user to the settings screen).
        viewModel.onUpdateActivityResumed(this)
    }

    override fun onPause() {
        // The presence machine disables the receive path and tears its
        // poll down so no background work survives the background stay.
        presenceEngine.onBackground()
        // The app leaves the foreground: the auto-lock must not lock the
        // screen while the user is in another app (an elapsed stretch is
        // held until the app is back).
        viewModel.appInForeground = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(nfcStateChangedReceiver)
        } catch (e: Exception) {
            // Not registered (a crash before onCreate finished); nothing to clean up.
        }
        super.onDestroy()
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
            // The presence machine decides what the read means for its
            // record / fast path, and forwards the read to the view model
            // (the playback decision).
            presenceEngine.onTagDetected(tag.id.toHex(), tag)
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
}

/**
 * The window chrome as the manifest's fixed-light theme created it (the
 * status bar color, the navigation bar color, the light-status-bar flag,
 * and the window background behind the first frame), captured on the
 * first composition so the light mode can restore it byte-identical.
 */
private data class OriginalWindowChrome(
    val statusBarColor: Int,
    val navigationBarColor: Int,
    val systemUiVisibility: Int,
    val background: Drawable?,
)

/**
 * Applies the window chrome for the resolved dark flag: dark mode takes the
 * dark background on the status bar and the navigation bar, turns the
 * light-status-bar icons off, and sets the window background to the dark
 * color so the manifest's near-white background never flashes before the
 * first dark frame; light mode restores the captured manifest chrome. The
 * light-status-bar icons go through the decor view's
 * [View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR] bit - the same underlying state
 * the window insets controller reads on the real device (the pruned test
 * mirror reduces `Window.getInsetsController()` to null, so the flag bit is
 * the only mirror-safe surface, and it is the pre-`WindowInsetsController`
 * API on API 26). The `statusBarColor` and `navigationBarColor` writes are
 * no-ops in the pruned test mirror (the instrumented `PhoneWindow` bodies
 * never execute - probed: set + idle leaves the getter at 0) but run the
 * real AOSP bodies on the device, so the status bar and navigation bar
 * bands follow the mode there too.
 */
@Suppress("DEPRECATION")
private fun Window.applyWindowChrome(darkTheme: Boolean, original: OriginalWindowChrome) {
    if (darkTheme) {
        val color = LumiDarkBackground.toArgb()
        statusBarColor = color
        navigationBarColor = color
        decorView.systemUiVisibility =
            decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        decorView.background = ColorDrawable(color)
    } else {
        statusBarColor = original.statusBarColor
        navigationBarColor = original.navigationBarColor
        decorView.systemUiVisibility = original.systemUiVisibility
        original.background?.let { decorView.background = it }
    }
}

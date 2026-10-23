package com.lumi.player.ui

import com.lumi.player.data.AutoLockDelay
import com.lumi.player.data.AudioLevelMode
import com.lumi.player.data.AudioLevelSettings
import com.lumi.player.data.ContentSortDirection
import com.lumi.player.data.LumiRepository
import com.lumi.player.data.LumiThemeMode
import com.lumi.player.data.MAX_REMEMBERED_TAGS
import com.lumi.player.data.PlayMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The app's settings surface: every persisted setting (the generic
 * key/value settings table) as a Room-backed [StateFlow] seeded with the
 * domain's default placeholder, plus its fire-and-forget setter. The
 * [LumiViewModel] keeps a same-named delegate for every surface here, so
 * the UI and the test suite never touch this class directly.
 */
class SettingsViewModel(
    private val repository: LumiRepository,
    private val scope: CoroutineScope,
    /**
     * Fired right after a new auto-lock choice has been written: the
     * view model applies it to the running auto-lock timer immediately
     * (the engine's own stored-delay collector re-applies it again on the
     * Room round trip).
     */
    private val onAutoLockDelaySet: (AutoLockDelay) -> Unit,
) {

    // ---- play mode ----

    /** The current play mode, persisted in the database. */
    val playMode: StateFlow<PlayMode> =
        repository.observePlayMode().stateIn(scope, SharingStarted.Eagerly, PlayMode.PROXIMITY)

    fun setPlayMode(mode: PlayMode) {
        scope.launch { repository.setPlayMode(mode) }
    }

    // ---- soft lock ----

    /** Whether the user asked not to show the soft-lock prompt again. */
    val lockPopupSuppressed: StateFlow<Boolean> =
        repository.observeLockPopupSuppressed().stateIn(scope, SharingStarted.Eagerly, false)

    fun setLockPopupSuppressed(suppressed: Boolean) {
        scope.launch { repository.setLockPopupSuppressed(suppressed) }
    }

    // ---- keep screen on ----

    /**
     * Whether the user wants the screen to stay on while the app is open.
     * Off by default: the screen follows the system timeout while the app
     * is open.
     */
    val keepScreenOn: StateFlow<Boolean> =
        repository.observeKeepScreenOn().stateIn(scope, SharingStarted.Eagerly, false)

    fun setKeepScreenOn(on: Boolean) {
        scope.launch { repository.setKeepScreenOn(on) }
    }

    // ---- remembered playback positions ----

    /**
     * How many songs' last played position the per-tag LRU remembers (the
     * Advanced options page's "Remembered playback positions" stepper).
     * Defaults to the hard-coded [MAX_REMEMBERED_TAGS] behavior; the count
     * is clamped to the setting's 1..20 range and persists in the database
     * like the other settings (a restart reads the pick back). The player
     * service evicts against this count at the next stored position: a
     * lower count evicts the oldest remembered tags first, a higher one
     * keeps the rows already remembered.
     */
    val rememberedPositions: StateFlow<Int> =
        repository.observeRememberedPositions().stateIn(scope, SharingStarted.Eagerly, MAX_REMEMBERED_TAGS)

    /**
     * Whether the write of this setting is still in flight. The stepper
     * disables its buttons for that duration, so a step never computes
     * from a stale value.
     */
    private val _rememberedPositionsWriteInFlight = MutableStateFlow(false)
    val rememberedPositionsWriteInFlight: StateFlow<Boolean> = _rememberedPositionsWriteInFlight

    // Test seam for the write (the real repository write by default), so
    // a test can hold the in-flight window open.
    internal var rememberedPositionsWriter: suspend (Int) -> Unit =
        { repository.setRememberedPositions(it) }

    fun setRememberedPositions(count: Int) {
        if (_rememberedPositionsWriteInFlight.value) return
        _rememberedPositionsWriteInFlight.value = true
        scope.launch {
            try {
                rememberedPositionsWriter(count)
            } finally {
                _rememberedPositionsWriteInFlight.value = false
            }
        }
    }

    // ---- first-time setup ----

    /**
     * The stored gate, read synchronously at construction (the view model
     * is created on the main thread, and the settings table read is a
     * fast local query - the same pattern MainActivity's cold-launch
     * seeds use). Seeding the StateFlow with it (instead of the "not
     * complete" placeholder) keeps the app from composing the wizard for
     * a frame on every returning user's launch, before Room's first
     * emission lands - which would swap the whole tree over and back.
     */
    private val storedSetupComplete: Boolean = runCatching {
        runBlocking { repository.observeSetupComplete().first() }
    }.getOrDefault(false)

    /**
     * Whether the first-time setup wizard has been finished. Unset (a
     * fresh install with cleared app data) is the wizard's gate: the app
     * lands on the wizard instead of the player until it is finished.
     */
    val setupComplete: StateFlow<Boolean> =
        repository.observeSetupComplete().stateIn(scope, SharingStarted.Eagerly, storedSetupComplete)

    /**
     * Records the wizard's completion (it never un-does itself) and
     * resets the wizard's flow to its start, so a later entry into it
     * begins at the welcome again.
     */
    fun completeSetup() {
        _wizardStep.value = SetupStep.Welcome
        _wizardFolderPickInFlight.value = false
        if (setupComplete.value) return
        scope.launch { repository.setSetupComplete(true) }
    }

    /**
     * The wizard's current step (the flow a fresh install walks). It
     * lives here, not in the wizard's composition, because the view
     * model outlives an activity recreation (a rotation, a foldable
     * unfolding, a dark-mode, font-scale or locale change): a
     * recreation mid-wizard keeps the step instead of bouncing the
     * flow back to the welcome. A process death (a fresh view model)
     * and [completeSetup] both start the flow at the welcome again.
     */
    private val _wizardStep = MutableStateFlow(SetupStep.Welcome)

    val wizardStep: StateFlow<SetupStep> = _wizardStep.asStateFlow()

    fun setWizardStep(step: SetupStep) {
        _wizardStep.value = step
    }

    /** The wizard's folder pick in flight (its music step's button copy). */
    private val _wizardFolderPickInFlight = MutableStateFlow(false)

    val wizardFolderPickInFlight: StateFlow<Boolean> = _wizardFolderPickInFlight.asStateFlow()

    fun setWizardFolderPickInFlight(inFlight: Boolean) {
        _wizardFolderPickInFlight.value = inFlight
    }

    // ---- nfc re-read wait ----

    /**
     * How many seconds of absence of reports on the active tag (proximity
     * mode) count as the tag having been removed; persisted in the database.
     */
    val nfcRereadSeconds: StateFlow<Long> =
        repository.observeNfcRereadSeconds().stateIn(
            scope,
            SharingStarted.Eagerly,
            LumiRepository.NFC_REREAD_SECONDS_DEFAULT,
        )

    fun setNfcRereadSeconds(seconds: Long) {
        scope.launch { repository.setNfcRereadSeconds(seconds) }
    }

    // ---- content sort direction ----

    /**
     * The content list's title sort direction (both the Folders section and
     * the individual content follow it). Ascending is the default - the
     * list shows A->Z until the user picks otherwise; the pick persists in
     * the database (an explicit pick stays authoritative over the default).
     */
    val contentSortDirection: StateFlow<ContentSortDirection> =
        repository.observeContentSortDirection().stateIn(
            scope,
            SharingStarted.Eagerly,
            ContentSortDirection.ASCENDING,
        )

    fun setContentSortDirection(direction: ContentSortDirection) {
        scope.launch { repository.setContentSortDirection(direction) }
    }

    // ---- appearance (theme mode) ----

    /**
     * The app's appearance mode (the settings screen's "Appearance"
     * choice): follow the system (the default - the phone's own light/dark
     * setting), or force light / dark. Persisted in the database; the
     * activity applies it app-wide through the theme, effective immediately
     * on change (no app restart required).
     */
    val themeMode: StateFlow<LumiThemeMode> =
        repository.observeThemeMode().stateIn(scope, SharingStarted.Eagerly, LumiThemeMode.FOLLOW_SYSTEM)

    fun setThemeMode(mode: LumiThemeMode) {
        scope.launch { repository.setThemeMode(mode) }
    }

    // ---- audio level ----

    /**
     * The audio level mode (OFF: every song plays at its recorded level;
     * NORMALIZE: each measured song gets the per-track gain that brings its
     * loudness to the target level). Off by default: no gain is applied
     * until the user turns normalization on. The pick persists in the
     * database (the service reads it live through the app's in-memory
     * [com.lumi.player.LumiApp.audioLevel] snapshot, no database read on the
     * playback path).
     */
    val audioLevelMode: StateFlow<AudioLevelMode> =
        repository.observeAudioLevelMode().stateIn(scope, SharingStarted.Eagerly, AudioLevelMode.OFF)

    fun setAudioLevelMode(mode: AudioLevelMode) {
        scope.launch { repository.setAudioLevelMode(mode) }
    }

    /**
     * The target integrated loudness (LUFS) normalization aims for: one of
     * the picker's targets (AudioLevelSettings.TARGETS - -18 / -16 / -14,
     * the default -16). The pick persists in the database.
     */
    val audioLevelTargetLufs: StateFlow<Double> =
        repository.observeAudioLevelTargetLufs().stateIn(
            scope,
            SharingStarted.Eagerly,
            AudioLevelSettings.DEFAULT_TARGET_LUFS,
        )

    fun setAudioLevelTargetLufs(targetLufs: Double) {
        scope.launch { repository.setAudioLevelTargetLufs(targetLufs) }
    }

    // ---- auto-lock after idle ----

    /**
     * How many minutes of idle - no song actively playing (stopped or
     * paused both count as idle) - count before the phone's screen is
     * locked, so a phone left with the app open does not drain its battery
     * doing nothing. Off by default: the screen is never locked
     * automatically. The choice persists in the database like the other
     * settings.
     */
    val autoLockDelay: StateFlow<AutoLockDelay> =
        repository.observeAutoLockDelay().stateIn(scope, SharingStarted.Eagerly, AutoLockDelay.OFF)

    fun setAutoLockDelay(delay: AutoLockDelay) {
        scope.launch { repository.setAutoLockDelay(delay) }
        // The running stretch follows the new choice immediately: the
        // timer re-arms (or disarms) against the current playback state.
        onAutoLockDelaySet(delay)
    }
}

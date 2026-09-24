package com.lumi.player.ui

import android.app.Application
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.lumi.player.LumiApp
import com.lumi.player.data.LumiThemeMode
import com.lumi.player.data.SettingsEntity
import com.lumi.player.ui.theme.LumiTheme
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * The settings screen's "Appearance" option: the menu row renders with the
 * current mode (follow system by default - an existing user is never
 * switched unexpectedly), the row opens the appearance sub-screen with the
 * Light / Dark / Follow-system choices, the choice persists through the app's
 * existing settings storage (a restarted app keeps it), and both themes
 * apply app-wide through the theme - the dark theme is the designed cocoa
 * night palette, not a grey fallback. The System choice follows the device's
 * own dark/light setting reactively: a flip of the device setting while the
 * app is open is reflected without a restart or re-entry.
 *
 * Theme application is pinned by composing the app's [LumiTheme] with an
 * explicit mode on a bare activity and reading the live [MaterialTheme]
 * colors back from inside the composition (the same probe technique the
 * settled palette test uses).
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeModeUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
        setSystemNight(false)
    }

    /** Opens the settings menu (the real [MainActivity], player screen first). */
    private fun openSettings(): MainActivity {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        return activity
    }

    /** The activity's real [LumiViewModel], via reflection. */
    private fun viewModelOf(activity: MainActivity): LumiViewModel {
        val field = MainActivity::class.java
            .getDeclaredField("viewModel")
            .also { it.isAccessible = true }
        return field.get(activity) as LumiViewModel
    }

    /** Waits for the asynchronous Room round trip to make the choice observable. */
    private fun awaitThemeMode(activity: MainActivity, expected: LumiThemeMode) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            if (viewModelOf(activity).themeMode.value == expected) return
        }
        fail("the appearance setting must become $expected")
    }

    /** The app's color scheme captured from a [LumiTheme] probe for [mode]. */
    private fun schemeOf(mode: LumiThemeMode): ColorScheme {
        var captured: ColorScheme? = null
        val act = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        act.setContent {
            LumiTheme(mode) {
                captured = MaterialTheme.colorScheme
            }
        }
        val deadline = System.currentTimeMillis() + 5_000
        while (captured == null && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        return checkNotNull(captured) { "the theme probe must compose" }
    }

    /** The default (follow-system) scheme, under the current system setting. */
    private fun schemeOfFollowSystem(): ColorScheme {
        var captured: ColorScheme? = null
        val act = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        act.setContent {
            LumiTheme {
                captured = MaterialTheme.colorScheme
            }
        }
        val deadline = System.currentTimeMillis() + 5_000
        while (captured == null && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        return checkNotNull(captured) { "the theme probe must compose" }
    }

    private fun app(): LumiApp = RuntimeEnvironment.getApplication() as LumiApp

    /**
     * Seeds a raw, un-sanitized value directly into the settings table,
     * bypassing [LumiViewModel.setThemeMode] (which only ever writes the
     * valid stored spellings), so adversarial values actually land in the
     * database the way a hand-written or corrupt row would.
     */
    private suspend fun seedStoredThemeMode(raw: String) {
        app().database.dao().upsertSetting(
            SettingsEntity(SettingsEntity.THEME_MODE, raw),
        )
    }

    /** The real repository's mapping of the stored value (the live read path). */
    private suspend fun storedThemeMode(): LumiThemeMode =
        app().repository.observeThemeMode().first()

    /** Sets the (system) phone's own light/dark setting for follow-system. */
    private fun setSystemNight(night: Boolean) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val config = Configuration(app.resources.configuration)
        config.uiMode =
            (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        app.resources.updateConfiguration(config, app.resources.displayMetrics)
    }

    // This mirror's Color.value packs the ARGB word at bits 32-63.
    private fun argb(color: Color): Long = color.value.toLong() ushr 32

    @Test
    fun settingsMenuShowsTheAppearanceRowWithFollowSystemByDefault() {
        val activity = openSettings()

        // The menu offers the Appearance row, and the default is follow
        // system (the phone's own setting) - existing users are never
        // switched to a forced mode.
        compose.onNodeWithText("Appearance").assertIsDisplayed()
        compose.onNodeWithText("Follow system").assertExists()
        // The setting starts unset in the database, so the view model
        // reports follow-system.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS)
        assertEquals(
            "the appearance setting must default to follow system",
            LumiThemeMode.FOLLOW_SYSTEM,
            viewModelOf(activity).themeMode.value,
        )
    }

    @Test
    fun appearanceRowOpensTheSubScreenWithAllThreeChoices() {
        openSettings()

        compose.onNodeWithText("Appearance").performClick()
        compose.waitForIdle()

        // The sub-screen (top bar AND section titled "Appearance") offers
        // the three choices with their details.
        compose.onNodeWithText("Follow system").assertExists()
        compose.onNodeWithText("Uses the phone's own light or dark setting.").assertExists()
        compose.onNodeWithText("Light").assertExists()
        compose.onNodeWithText("Bright and sunny, any time of day.").assertExists()
        compose.onNodeWithText("Dark").assertExists()
        compose.onNodeWithText("Soft and easy on the eyes in the dark.").assertExists()

        // Back to the menu: the choices' details are gone, the row is back.
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Appearance").assertIsDisplayed()
        compose.onNodeWithText("Uses the phone's own light or dark setting.")
            .assertDoesNotExist()
        compose.onNodeWithText("Bright and sunny, any time of day.")
            .assertDoesNotExist()
    }

    @Test
    fun choosingDarkPersistsAndTheRestartedAppKeepsIt() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()

        // Pick Dark in the sub-screen; the Room round trip makes it
        // observable, and the menu row's subtitle now reads Dark.
        compose.onNodeWithText("Appearance").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Dark").performClick()
        awaitThemeMode(activity, LumiThemeMode.DARK)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Dark").assertExists()

        // The choice is in the app's settings storage: a restarted app
        // (a fresh activity over the same store) reads it back.
        controller.destroy()
        val restarted = Robolectric.buildActivity(MainActivity::class.java).setup()
        awaitThemeMode(restarted.get(), LumiThemeMode.DARK)
    }

    @Test
    fun choosingLightOverridesADarkChoice() {
        val activity = openSettings()

        compose.onNodeWithText("Appearance").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Dark").performClick()
        awaitThemeMode(activity, LumiThemeMode.DARK)
        compose.waitForIdle()

        // Still on the sub-screen: pick Light; the choice follows it.
        compose.onNodeWithText("Light").performClick()
        awaitThemeMode(activity, LumiThemeMode.LIGHT)
    }

    @Test
    fun lightAndDarkModesApplyTheDesignedPalettes() {
        // Light: the settled sunlit-amber family (bright, warm, high
        // contrast over cream).
        val light = schemeOf(LumiThemeMode.LIGHT)
        assertEquals(0xFFF5761E.toLong(), argb(light.primary))
        assertEquals(0xFFFFFBF2.toLong(), argb(light.background))
        assertEquals(0xFFFFE3C8.toLong(), argb(light.primaryContainer))

        // Dark: the designed cocoa night palette (warm deep brown, NOT a
        // grey fallback) with its bright candy accents.
        val dark = schemeOf(LumiThemeMode.DARK)
        assertEquals(0xFFFFA24D.toLong(), argb(dark.primary))
        assertEquals(0xFF140F0A.toLong(), argb(dark.background))
        assertEquals(0xFFF6ECDF.toLong(), argb(dark.onSurface))
        assertEquals(0xFF4DD0C4.toLong(), argb(dark.secondary))
    }

    @Test
    fun followSystemTracksTheSystemsNightSettingReactively() {
        // The app is open on the player screen with the system on light;
        // the choice is System (follow the device) - the default, and no
        // stored value is written by the flip below.
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        compose.waitForIdle()
        compose.onNodeWithText("lumi").assertIsDisplayed()

        // The device's dark setting flips while the app is open (the test
        // mutates the resources the same way the platform delivers the
        // configuration change): the app is NOT restarted and the user
        // re-enters no screen, so the app's own theme - FOLLOW_SYSTEM -
        // now resolves to the dark palette on the next composition.
        setSystemNight(true)
        assertEquals(0xFFFFA24D.toLong(), argb(schemeOfFollowSystem().primary))

        // And back: the system flips to light and the app follows.
        setSystemNight(false)
        assertEquals(0xFFF5761E.toLong(), argb(schemeOfFollowSystem().primary))

        // The whole time the stored choice stayed System: the flip never
        // switched the user to a forced mode.
        ShadowLooper.idleMainLooper()
        assertEquals(
            "the System choice must stay stored as follow system",
            LumiThemeMode.FOLLOW_SYSTEM,
            viewModelOf(activity).themeMode.value,
        )
    }

    /**
     * The window chrome follows the SAME resolved mode as the UI. Pinned
     * through the mirror-observable surfaces: the window background
     * (no near-white flash before the first dark frame) and the
     * light-status-bar flag. The `window.statusBarColor` write is kept in
     * production for the real device (the pruned test mirror's instrumented
     * `PhoneWindow.setStatusBarColor` body is a no-op - probed: set + idle
     * leaves the getter at 0 - so the band color itself is a device-side
     * verification item).
     */
    @Test
    fun darkChoiceAppliesTheDarkWindowChromeAndLightRestoresTheManifestChrome() {
        val activity = openSettings()
        // The manifest's fixed-light chrome, as captured on the first
        // composition (before any chrome sync can run).
        val originalVisibility = activity.window.decorView.systemUiVisibility
        val originalBackground = activity.window.decorView.background

        // Pick Dark: the window chrome follows the SAME resolved mode as
        // the UI - the light status-bar icons off, and the dark window
        // background (no near-white flash before the first dark frame).
        compose.onNodeWithText("Appearance").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Dark").performClick()
        awaitThemeMode(activity, LumiThemeMode.DARK)
        compose.waitForIdle()
        val darkBackground = activity.window.decorView.background
        assertNotNull("the window background must be set in dark mode", darkBackground)
        assertEquals(0xFF140F0A.toInt(), (darkBackground as ColorDrawable).color)
        assertEquals(
            "the light-status-bar icons must be off in dark mode",
            0,
            activity.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR,
        )

        // Pick Light again: the manifest's original chrome comes back
        // byte-identical (flag, and the original background drawable
        // itself).
        compose.onNodeWithText("Light").performClick()
        awaitThemeMode(activity, LumiThemeMode.LIGHT)
        compose.waitForIdle()
        assertEquals(originalVisibility, activity.window.decorView.systemUiVisibility)
        if (originalBackground != null) {
            assertSame(originalBackground, activity.window.decorView.background)
        } else {
            assertNull(activity.window.decorView.background)
        }
    }

    /**
     * A cold launch with DARK stored is dark from the first composition:
     * onCreate seeds the first frame with the stored mode synchronously, so
     * the view model's async themeMode flow (which starts from the
     * follow-system placeholder) cannot win the race and flash the
     * manifest's near-white window background and the light palette before
     * the first dark frame. Pinned on the mirror-observable surface: right
     * after the fresh activity's first composition - with no further
     * pumping of the async flow - the dark window background is already in
     * place.
     */
    @Test
    fun coldLaunchWithStoredDarkIsDarkFromTheFirstComposition() {
        // Store DARK through a first activity (pump the looper for the
        // Room round trip), then destroy it.
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Appearance").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Dark").performClick()
        awaitThemeMode(activity, LumiThemeMode.DARK)
        controller.destroy()

        // Cold-launch a fresh activity over the same store: the dark
        // window background is already in place from the first
        // composition - no further pumping of the async flow.
        val cold = Robolectric.buildActivity(MainActivity::class.java).setup()
        val background = cold.get().window.decorView.background
        assertNotNull(
            "the dark window background must be in place from the first composition",
            background,
        )
        assertEquals(0xFF140F0A.toInt(), (background as ColorDrawable).color)
    }

    // The adversarial persistence contract, driven LIVE through the real
    // database (the raw-write seam bypasses the view model's sanitizing
    // setter, so the adversarial values are what the repository actually
    // sees): every value that is not a valid mode spelling falls back to
    // follow system, and every valid spelling round-trips to its mode.

    @Test
    fun freshStoreResolvesToFollowSystem() = runBlocking {
        // No row at all (the first-launch state of a fresh store): the
        // real repository mapping resolves to follow system.
        assertEquals(LumiThemeMode.FOLLOW_SYSTEM, storedThemeMode())
    }

    @Test
    fun emptyStoredValueResolvesToFollowSystem() = runBlocking {
        seedStoredThemeMode("")
        assertEquals(LumiThemeMode.FOLLOW_SYSTEM, storedThemeMode())
    }

    @Test
    fun whitespaceOnlyStoredValueResolvesToFollowSystem() = runBlocking {
        seedStoredThemeMode("   ")
        assertEquals(LumiThemeMode.FOLLOW_SYSTEM, storedThemeMode())
    }

    @Test
    fun garbageStoredValueResolvesToFollowSystem() = runBlocking {
        seedStoredThemeMode("bogus-mode")
        assertEquals(LumiThemeMode.FOLLOW_SYSTEM, storedThemeMode())
    }

    @Test
    fun caseAndWhitespaceVariedStoredValuesResolveToTheirModes() = runBlocking {
        seedStoredThemeMode(" DARK ")
        assertEquals(LumiThemeMode.DARK, storedThemeMode())
        seedStoredThemeMode("light")
        assertEquals(LumiThemeMode.LIGHT, storedThemeMode())
        seedStoredThemeMode("system")
        assertEquals(LumiThemeMode.FOLLOW_SYSTEM, storedThemeMode())
    }

    /**
     * The UI, not just the parse function, falls back: with a garbage
     * value in the settings table, a cold launch of the real app shows
     * the Appearance menu row's subtitle as "Follow system" - the
     * resolved fallback - and never the raw stored value.
     */
    @Test
    fun garbageStoredValueColdLaunchMenuShowsFollowSystem() {
        runBlocking { seedStoredThemeMode("bogus-mode") }
        val activity = openSettings()

        compose.onNodeWithText("Appearance").assertIsDisplayed()
        // The row's subtitle is the RESOLVED mode: the garbage value
        // falls back to follow system in the UI, and the raw value never
        // surfaces.
        compose.onNodeWithText("Follow system").assertExists()
        compose.onNodeWithText("bogus-mode").assertDoesNotExist()
        // The view model (the row's data source) resolves it the same way.
        assertEquals(
            "the garbage stored value must resolve to follow system",
            LumiThemeMode.FOLLOW_SYSTEM,
            viewModelOf(activity).themeMode.value,
        )
    }
}

package com.lumi.player.ui

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.lumi.player.LumiApp
import com.lumi.player.data.EntryType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadow.api.Shadow
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * Behavioral tests for the audio-only settings change: the "Add content"
 * button must go straight to the audio file picker - the old Audio/Video/Link
 * type dialog and the link-entry form are gone - while legacy video/url rows
 * saved before the change still parse and render in the content list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsAudioOnlyUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
    }

    private fun openSettings(): MainActivity {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        return activity
    }

    private class SettingsHostActivity : androidx.activity.ComponentActivity() {
        companion object {
            var pendingViewModel: LumiViewModel? = null
        }

        override fun onCreate(savedInstanceState: android.os.Bundle?) {
            super.onCreate(savedInstanceState)
            val vm = requireNotNull(pendingViewModel)
            setContent { SettingsScreen(vm, onBack = {}) }
        }
    }


@Test
    fun addContentGoesStraightToTheAudioPickerWithNoTypeDialog() {
        // Host the real [SettingsScreen] in a plain activity and drive its
        // actual "Add content" wiring end-to-end.
        val app = ApplicationProvider.getApplicationContext<LumiApp>()
        val viewModel = LumiViewModel(app, app.repository)
        SettingsHostActivity.pendingViewModel = viewModel
        val activity = Robolectric.buildActivity(SettingsHostActivity::class.java).setup().get()
        compose.waitForIdle()

        // The settings column is taller than the tiny Robolectric display and
        // performClick injects real touch input at the node's screen position,
        // so scroll the button into view first.
        compose.onNodeWithText("Add content").performScrollTo()
        compose.waitForIdle()

        // The old "Audio / Video / Link" chooser dialog and the link-entry
        // form are gone: none of their copy can be on screen.
        compose.onNodeWithText("Saved now, plays in a future version.").assertDoesNotExist()
        compose.onNodeWithText("Plays right away.", substring = false).assertDoesNotExist()
        compose.onNodeWithText("Link / URL").assertDoesNotExist()
        compose.onNodeWithText("Add a link").assertDoesNotExist()

        compose.onNodeWithText("Add content").performClick()
        compose.waitForIdle()

        // Tapping the button launches the document picker directly, filtered
        // to audio only. The SAF contract starts the picker via
        // startActivityForResult, which Robolectric records.
        val shadow = Shadow.extract<org.robolectric.shadows.ShadowApplication>(app)
        val appQ = mutableListOf<Intent>()
        while (true) {
            val e = shadow.peekNextStartedActivityForResult() ?: break
            appQ.add(e.intent)
            shadow.nextStartedActivityForResult
        }
        val actShadow = Shadow.extract<org.robolectric.shadows.ShadowActivity>(activity)
        val actQ = mutableListOf<Intent>()
        while (true) {
            val e = actShadow.peekNextStartedActivityForResult() ?: break
            actQ.add(e.intent)
            actShadow.nextStartedActivityForResult
        }
        val all = (appQ + actQ).map { it.action }
        val pick = (appQ + actQ).lastOrNull {
            it.action == Intent.ACTION_CHOOSER || it.action == Intent.ACTION_OPEN_DOCUMENT
        }
        assertNotNull(
            "tapping 'Add content' must launch the document picker; actions: $all",
            pick,
        )
        val docIntent = if (pick!!.action == Intent.ACTION_CHOOSER) {
            pick.extras
                ?.getParcelableArray(Intent.EXTRA_INITIAL_INTENTS)
                ?.firstOrNull()?.let { it as Intent } ?: pick
        } else {
            pick
        }
        assertEquals(
            "the picker must ask for a document, got: $pick",
            Intent.ACTION_OPEN_DOCUMENT,
            docIntent.action,
        )
        // The picker must be filtered to audio only, whatever mime form the
        // activity-result contract used (intent type or EXTRA_MIME_TYPES).
        val mimes = (docIntent.type?.let { listOf(it) } ?: emptyList()) +
            (docIntent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList() ?: emptyList())
        assertTrue(
            "the picker must be filtered to audio files only, got: $docIntent",
            mimes.contains("audio/*"),
        )
        assertTrue(
            "the picker must not offer video files, got: $docIntent",
            "video/*" !in mimes && mimes.none { it.startsWith("video/") },
        )
    }

@Test
    fun legacyVideoAndUrlEntriesStillParseAndRenderInTheContentList() {
        // Rows saved before the audio-only change must still show up (and be
        // manageable) in the content list - just without the "coming soon"
        // placeholder.
        val app = ApplicationProvider.getApplicationContext<LumiApp>()
        val thread = Thread {
            runBlocking {
                app.repository.addEntry(
                    EntryType.VIDEO, "Old Clip", "content://media/video/clip",
                )
                app.repository.addEntry(
                    EntryType.URL, "Toy Site", "https://example.com/toy",
                )
            }
        }
        thread.start()
        thread.join()
        ShadowLooper.idleMainLooper()
        openSettings()

        compose.onNodeWithText("Old Clip").assertExists()
        compose.onNodeWithText("video file").assertExists()
        compose.onNodeWithText("Toy Site").assertExists()
        compose.onNodeWithText("link", substring = false).assertExists()
        // No "coming soon" placeholder remains anywhere in the UI.
        check(compose.onAllNodesWithText("coming soon", substring = true).fetchSemanticsNodes().isEmpty()) {
            "no 'coming soon' placeholder may remain in the UI"
        }
    }
}

package com.lumi.player.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.lumi.player.LumiApp
import com.lumi.player.data.PlayMode
import com.lumi.player.nfc.LumiTagData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * Behavioral coverage for the tag-registration dialog's tag-payload
 * integration: a captured tag that carries the app's lumi payload
 * pre-fills the name field and pre-selects its play mode, and a writable
 * tag offers the optional "Save and write to tag" step while a non-
 * writable (or unformatted) tag only offers plain save. Saving with a
 * preselected mode applies that mode to the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RegistrationPrefillUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
    }

    private fun app(): LumiApp = ApplicationProvider.getApplicationContext()

    private fun viewModelOf(activity: MainActivity): LumiViewModel {
        val field = MainActivity::class.java.getDeclaredField("viewModel")
        field.isAccessible = true
        return field.get(activity) as LumiViewModel
    }

    private fun openSettingsAndCapture(data: LumiTagData?, writable: Boolean): LumiViewModel {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        val viewModel = viewModelOf(activity)
        viewModel.captureForTest(UID, data, writable)
        compose.waitForIdle()
        return viewModel
    }

    private fun idleUntil(cond: () -> Boolean, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
    }

    private fun savedTagName(uid: String): String? {
        var result: String? = null
        Thread {
            result = runBlocking { app().repository.tagByUid(uid)?.name }
        }.also { it.start() }.apply { join() }
        return result
    }

    private fun playModeOf(viewModel: LumiViewModel): PlayMode = viewModel.playMode.value

    // ---- prefill from the tag's own payload ----

    @Test
    fun registration_tagWithLumiPayload_prefillsNameAndOffersWrite() {
        val viewModel = openSettingsAndCapture(
            data = LumiTagData("Hallway", PlayMode.PROXIMITY),
            writable = true,
        )
        compose.onNodeWithText("Tag captured").assertIsDisplayed()
        // The name field pre-fills from the tag's payload.
        compose.onNodeWithText("Hallway").assertIsDisplayed()
        // A writable tag offers the optional write step.
        compose.onNodeWithText("Save and write to tag").assertIsDisplayed()

        // Saving with the preselected mode applies it to the app.
        compose.onNodeWithText("Save and write to tag").performClick()
        compose.waitForIdle()
        idleUntil({ playModeOf(viewModel) == PlayMode.PROXIMITY })
        assertEquals("The dialog's preselected mode must become the app mode", PlayMode.PROXIMITY, playModeOf(viewModel))
        assertEquals("The saved tag must carry the pre-filled name", "Hallway", savedTagName(UID))
    }

    @Test
    fun registration_tagWithLumiPayload_prefersTagModeOverAppMode() {
        // App is in CONTINUOUS; the tag says PROXITY. Saving without
        // changing the dialog must keep the tag's mode.
        val viewModel = openSettingsAndCapture(
            data = LumiTagData("Porch", PlayMode.PROXIMITY),
            writable = false,
        )
        compose.onNodeWithText("Porch").assertIsDisplayed()
        compose.onNodeWithText("Save and write to tag").assertDoesNotExist()
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        idleUntil({ playModeOf(viewModel) == PlayMode.PROXIMITY })
        assertEquals(PlayMode.PROXIMITY, playModeOf(viewModel))
    }

    @Test
    fun registration_plainTag_emptyFormAndNoWriteOption() {
        val viewModel = openSettingsAndCapture(data = null, writable = false)
        compose.onNodeWithText("Tag captured").assertIsDisplayed()
        compose.onNodeWithText("Save and write to tag").assertDoesNotExist()
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        idleUntil({ playModeOf(viewModel) == PlayMode.CONTINUOUS })
        assertEquals(
            "skipping the write and the mode must leave the app mode untouched",
            PlayMode.CONTINUOUS,
            playModeOf(viewModel),
        )
        assertEquals("The saved tag must have the empty name from the empty form", "", savedTagName(UID))
    }

    companion object {
        const val UID = "04CAFE00"
    }
}

package com.lumi.player.ui

import android.nfc.Tag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.lumi.player.LumiApp
import com.lumi.player.data.PlayMode
import com.lumi.player.nfc.LumiTagData
import com.lumi.player.nfc.LumiTagNdef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
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
 * writable (or unformatted) tag only offers plain save. The mode only
 * records what mode the tag was registered with (form pre-fill on
 * re-read); saving a tag never changes the app's play mode.
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

    private fun openSettings(): LumiViewModel {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        return viewModelOf(activity)
    }

    private fun openSettingsAndCapture(data: LumiTagData?, writable: Boolean): LumiViewModel {
        val viewModel = openSettings()
        viewModel.captureForTest(UID, data, writable)
        compose.waitForIdle()
        return viewModel
    }

    private fun db(block: suspend () -> Unit) {
        Thread { runBlocking { block() } }.also { it.start() }.apply { join() }
    }

    private fun idleUntil(cond: () -> Boolean, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
    }

    /** Idles the main looper for [durationMs] unconditionally. */
    private fun pumpMain(durationMs: Long) {
        val deadline = System.currentTimeMillis() + durationMs
        while (System.currentTimeMillis() < deadline) {
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
        val viewModel = openSettings()
        // Live capture shape: the dialog opens before the async NDEF read
        // lands, so the payload arrives after the form has already composed.
        viewModel.captureForTest(UID, null, false)
        compose.waitForIdle()
        compose.onNodeWithText("Tag captured").assertIsDisplayed()
        compose.onNodeWithText("Name (optional)").assertIsDisplayed()
        compose.onNodeWithText("Save and write to tag").assertDoesNotExist()
        viewModel.captureForTest(UID, LumiTagData("Hallway", PlayMode.PROXIMITY), true)
        compose.waitForIdle()
        // The name field picks up the tag's payload; a writable tag offers
        // the optional write step.
        compose.onNodeWithText("Hallway").assertIsDisplayed()
        compose.onNodeWithText("Save and write to tag").assertIsDisplayed()

        // Saving must NOT change the app's play mode, even though the tag's
        // preselected mode (PROXIMITY) differs from the app's (CONTINUOUS).
        compose.onNodeWithText("Save and write to tag").performClick()
        compose.waitForIdle()
        idleUntil({ savedTagName(UID) != null })
        assertEquals(
            "saving must not change the app play mode",
            PlayMode.CONTINUOUS,
            playModeOf(viewModel),
        )
        assertEquals("The saved tag must carry the pre-filled name", "Hallway", savedTagName(UID))
    }

    @Test
    fun registration_savingTagMode_doesNotChangeAppPlayMode() {
        // App is in CONTINUOUS; the tag says PROXIMITY. Saving with the
        // preselected mode records it on the tag payload only - the app's
        // play mode must stay CONTINUOUS.
        val viewModel = openSettingsAndCapture(
            data = LumiTagData("Porch", PlayMode.PROXIMITY),
            writable = false,
        )
        compose.onNodeWithText("Porch").assertIsDisplayed()
        compose.onNodeWithText("Save and write to tag").assertDoesNotExist()
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        idleUntil({ savedTagName(UID) != null })
        assertEquals(
            "saving a tag must not switch the app play mode",
            PlayMode.CONTINUOUS,
            playModeOf(viewModel),
        )
        assertEquals("The tag must be saved under the pre-filled name", "Porch", savedTagName(UID))
    }

    @Test
    fun registration_rewritingRegisteredTag_updatesStoredName() {
        // The tag is already registered under its stored name.
        db { app().repository.addTag(UID, "Old Name") }
        val viewModel = openSettingsAndCapture(data = null, writable = true)
        viewModel.tagWriter = { _, _ -> true }
        compose.onNodeWithText("Name (optional)").assertIsDisplayed()
        // Name the tag and write that name to it: the stored record must
        // follow the written tag payload.
        compose.onNodeWithText("Name (optional)").performTextInput("Porch")
        compose.waitForIdle()
        compose.onNodeWithText("Save and write to tag").performClick()
        compose.waitForIdle()
        idleUntil({ savedTagName(UID) == "Porch" })
        assertEquals(
            "the stored name must follow the written tag payload",
            "Porch",
            savedTagName(UID),
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun registration_nameInput_isClampedToTagPayloadLimit() {
        // The dialog's name field clamps to MAX_NAME_LENGTH, so the name
        // saved to the DB and the name written to the tag can never
        // diverge by truncation.
        // Pre-fill the field so the editable node can be selected by its
        // (merged-tree) text, then overwrite it with an oversized name.
        val viewModel = openSettingsAndCapture(
            data = LumiTagData("Prefill", PlayMode.CONTINUOUS),
            writable = false,
        )
        compose.onNodeWithText("Tag captured").assertIsDisplayed()
        compose.onNodeWithText("Prefill")
            .performTextInput("x".repeat(LumiTagNdef.MAX_NAME_LENGTH + 10))
        compose.waitForIdle()
        // The field holds at most MAX_NAME_LENGTH chars, never the raw input.
        compose.onNodeWithText("x".repeat(LumiTagNdef.MAX_NAME_LENGTH)).assertExists()
        compose.onNodeWithText("x".repeat(LumiTagNdef.MAX_NAME_LENGTH + 10))
            .assertDoesNotExist()
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        idleUntil({ savedTagName(UID) != null })
        assertEquals(
            "the saved name must be clamped, not the raw 60-char input",
            "x".repeat(LumiTagNdef.MAX_NAME_LENGTH),
            savedTagName(UID),
        )
    }

    @Test
    fun registration_plainTag_emptyFormAndNoWriteOption() {
        val viewModel = openSettingsAndCapture(data = null, writable = false)
        compose.onNodeWithText("Tag captured").assertIsDisplayed()
        compose.onNodeWithText("Save and write to tag").assertDoesNotExist()
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        idleUntil({ savedTagName(UID) != null })
        assertEquals(
            "saving a plain tag must leave the app mode untouched",
            PlayMode.CONTINUOUS,
            playModeOf(viewModel),
        )
        assertEquals("The saved tag must have the empty name from the empty form", "", savedTagName(UID))
    }

    @Test
    fun registration_typingBeforePayloadLands_keepsTypedName() {
        // The dialog composes (empty form) before the async NDEF read lands,
        // so the user may start typing in that window; when the payload then
        // arrives it must not clobber what the user already typed.
        val viewModel = openSettings()
        viewModel.captureForTest(UID, null, false)
        compose.waitForIdle()
        compose.onNodeWithText("Name (optional)").performTextInput("Mine")
        compose.waitForIdle()
        viewModel.captureForTest(UID, LumiTagData("Hallway", PlayMode.PROXIMITY), true)
        compose.waitForIdle()
        compose.onNodeWithText("Mine").assertIsDisplayed()
        compose.onNodeWithText("Hallway").assertDoesNotExist()
        // Writability from the late read still takes effect.
        compose.onNodeWithText("Save and write to tag").assertIsDisplayed()
    }

    // ---- stale capture read must not overwrite a newer session ----

    /**
     * A synthetic tag with the given UID. The stub SDK omits Tag's test
     * hooks; the real framework (loaded by Robolectric at runtime) exposes
     * the public constructor, so build it reflectively (same approach as
     * NfcAlwaysListeningUiTest). It carries no NDEF handle, so the real
     * reader would see nothing - tests replace [LumiViewModel.tagReader].
     */
    private fun tagWithUid(uid: String): Tag {
        val nfcTagCls = Class.forName("android.nfc.INfcTag")
        val ctor = Tag::class.java.getDeclaredConstructor(
            ByteArray::class.java,
            IntArray::class.java,
            Array<android.os.Bundle>::class.java,
            Int::class.javaPrimitiveType!!,
            Long::class.javaPrimitiveType!!,
            nfcTagCls,
        )
        ctor.isAccessible = true
        val tagService = java.lang.reflect.Proxy.newProxyInstance(
            nfcTagCls.classLoader,
            arrayOf(nfcTagCls),
        ) { _, _, _ -> null } as Any
        return ctor.newInstance(
            ByteArray(uid.length / 2) {
                uid.substring(it * 2, it * 2 + 2).toInt(16).toByte()
            },
            intArrayOf(),
            arrayOfNulls<android.os.Bundle>(0),
            0,
            0L,
            tagService,
        ) as Tag
    }

    @Test
    fun registration_staleCaptureRead_doesNotOverwriteNewerSession() {
        // Start a capture of tag A whose read is in flight; cancel into a
        // new capture of tag B; A's read then lands. Its stale payload must
        // not publish into B's session - only B's own read may.
        val viewModel = openSettings()
        val gateA = CountDownLatch(1)
        val gateB = CountDownLatch(1)
        val entered = AtomicInteger(0)
        viewModel.tagReader = {
            if (entered.incrementAndGet() == 1) {
                gateA.await()
                LumiTagData("Stale", PlayMode.PROXIMITY)
            } else {
                gateB.await()
                LumiTagData("Fresh", PlayMode.CONTINUOUS)
            }
        }
        viewModel.capture(UID_A, tagWithUid(UID_A))
        idleUntil({ entered.get() == 1 }) // A's read is in flight (blocked)
        viewModel.capture(UID_B, tagWithUid(UID_B))
        idleUntil({ entered.get() == 2 }) // B's read is in flight (blocked)
        // A's (stale) read lands while B's session is active: suppressed.
        gateA.countDown()
        // Pump the main looper well past A's resumption so its (suppressed)
        // publish window has passed; the new session must still be empty.
        pumpMain(1_000)
        assertEquals(
            "a stale in-flight read must not publish into the newer session",
            null,
            viewModel.capturedTagData.value,
        )
        // B's own read lands: it publishes B's payload.
        gateB.countDown()
        idleUntil({ viewModel.capturedTagData.value != null })
        assertEquals(
            "the active session's own read must publish",
            LumiTagData("Fresh", PlayMode.CONTINUOUS),
            viewModel.capturedTagData.value,
        )
    }

    companion object {
        const val UID = "04CAFE00"
        const val UID_A = "AABBCCDD"
        const val UID_B = "11223344"
    }
}

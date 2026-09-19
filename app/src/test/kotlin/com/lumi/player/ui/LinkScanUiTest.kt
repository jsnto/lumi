package com.lumi.player.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.lumi.player.LumiApp
import com.lumi.player.data.EntryType
import com.lumi.player.player.PlayerStateHolder
import com.lumi.player.player.PlaybackStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
 * Behavioral coverage for the link editor's "scan a tag" action: scanning
 * from the tag-to-content section reads the next tag and re-points it at a
 * song list - a known tag is selected in the link editor so its list can be
 * edited there, an unknown tag goes through the registration capture first
 * and is then selected. Cancelling the scan ends it, so the next read is a
 * normal playback read again. The song list is associated by the tag's UID
 * in the database, so no tag write is involved and the tag payload format
 * is untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LinkScanUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
    }

    private fun app(): LumiApp = ApplicationProvider.getApplicationContext()

    private fun openSettings(): LumiViewModel {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        val field = MainActivity::class.java.getDeclaredField("viewModel")
        field.isAccessible = true
        return field.get(activity) as LumiViewModel
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

    private fun startScan(viewModel: LumiViewModel) {
        // The scan button lives on the tag-to-content sub-screen (its menu
        // button opens it), and on the small test screen it may be outside
        // the viewport: existence is the layout assertion, and the scan is
        // started through the view model (same pattern as captureForTest).
        compose.onNodeWithText("Tag to content").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Scan a tag to change its song list").assertExists()
        viewModel.startTagScan()
        compose.waitForIdle()
        compose.onNodeWithText("Hold the back of the phone against the tag…").assertIsDisplayed()
    }

    @Test
    fun scan_knownTag_selectsItInLinkEditor() {
        // Two tags, so the effective selection can only be the scanned one
        // (the effective id falls back to the first tag when nothing is
        // selected).
        var knownTagId: Long = 0
        db {
            app().repository.addTag(OTHER_UID, "Other")
            knownTagId = app().repository.addTag(UID, "Porch")!!.id
        }
        val viewModel = openSettings()
        startScan(viewModel)
        // The foreground-dispatch read lands while the scan is in progress.
        viewModel.onTagDetected(UID)
        compose.waitForIdle()
        idleUntil({ viewModel.effectiveSelectedTagId.value == knownTagId })
        assertEquals(
            "the scanned tag must be selected in the link editor",
            knownTagId,
            viewModel.effectiveSelectedTagId.value,
        )
        assertEquals("the scan must be over once it is settled", false, viewModel.scanning.value)
        compose.onNodeWithText("Hold the back of the phone against the tag…").assertDoesNotExist()
    }

    @Test
    fun scan_unknownTag_registersItAndSelectsIt() {
        val viewModel = openSettings()
        startScan(viewModel)
        viewModel.onTagDetected(UID)
        // The unknown tag goes through the registration capture.
        idleUntil({ viewModel.capturedUid.value != null })
        compose.onNodeWithText("Tag captured").assertIsDisplayed()
        compose.onNodeWithText("Name (optional)").performTextInput("Porch")
        compose.waitForIdle()
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        val saved = dbAndGet { app().repository.tagByUid(UID) }
        idleUntil({ viewModel.effectiveSelectedTagId.value != null })
        assertEquals("the scanned tag must be registered", "Porch", saved?.name)
        assertEquals(
            "the freshly registered tag must be selected in the link editor",
            saved!!.id,
            viewModel.effectiveSelectedTagId.value,
        )
    }

    @Test
    fun scan_readMustNotDrivePlayback() {
        // A read while the scan is in progress settles the scan only: it
        // must not switch the playback policy or start the player, even
        // for a known tag that has content linked to it.
        db {
            val tag = app().repository.addTag(UID, "Porch")!!
            val entry = app().repository.addEntry(EntryType.AUDIO, "song", "")
            app().repository.updateTagEntries(tag.id) { it + entry.id }
        }
        val viewModel = openSettings()
        startScan(viewModel)
        viewModel.onTagDetected(UID)
        idleUntil({ !viewModel.scanning.value })
        pumpMain(300)
        assertEquals("a read during the scan must not start playback", PlaybackStatus.IDLE, PlayerStateHolder.state.value.status)
        val policyField = LumiViewModel::class.java.getDeclaredField("policy")
        policyField.isAccessible = true
        val policy = policyField.get(viewModel)
        val activeUid = policy.javaClass.getMethod("getActiveTagUid").invoke(policy)
        assertNull("a read during the scan must not switch the active tag", activeUid)
        compose.onNodeWithText("Hold the back of the phone against the tag\u2026").assertDoesNotExist()
    }

    @Test
    fun scan_cancelled_nextReadIsANormalReadAgain() {
        val viewModel = openSettings()
        startScan(viewModel)
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertEquals(false, viewModel.scanning.value)
        compose.onNodeWithText("Hold the back of the phone against the tag…").assertDoesNotExist()
        // A read after the cancel is a normal read: it must not open a
        // capture or select anything, even for an unknown tag. Pump past
        // the read's async handling before asserting.
        viewModel.onTagDetected(OTHER_UID)
        pumpMain(300)
        assertNull("a read after cancel must not open a capture", viewModel.capturedUid.value)
        assertNull("a read after cancel must not select a tag", viewModel.selectedTagId.value)
    }

    private fun <T> dbAndGet(block: suspend () -> T): T {
        var result: T? = null
        Thread { result = runBlocking { block() } }.also { it.start() }.apply { join() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    companion object {
        const val UID = "04CAFE00"
        const val OTHER_UID = "AABBCCDD"
    }
}

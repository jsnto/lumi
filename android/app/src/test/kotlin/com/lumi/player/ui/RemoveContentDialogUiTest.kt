package com.lumi.player.ui

import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isNotDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.lumi.player.LumiApp
import com.lumi.player.data.EntryType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
 * The content pane's remove-content confirmation dialog (the failed live
 * scenario: "the remove-content confirmation dialog reflects the current
 * state, with no stale dialog lingering after the removal settles").
 *
 * This branch's v4 -> v5 migration dropped the tag_links foreign key to
 * entries, so a tag's link to a removed content entry SURVIVES the removal
 * (the song holds its place on the tag's list as a "song not found" row -
 * pinned by [com.lumi.player.data.EntriesForTagTest] and
 * [com.lumi.player.ui.FolderRemoveUiTest]). The dialog's copy must state
 * THAT effect: the song leaves the app's content list and the tags that
 * play it keep a "song not found" row - not the pre-branch cascade
 * ("removed ... from every tag"), which no longer happens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
    qualifiers = "w412dp-h900dp",
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RemoveContentDialogUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        markSetupCompleteForTests()
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
    }

    private fun app(): LumiApp = ApplicationProvider.getApplicationContext()

    private fun openContentPane() {
        Robolectric.buildActivity(MainActivity::class.java).setup()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Content").performClick()
        compose.waitForIdle()
    }

    /** Runs a suspend block on the app's database and returns its result. */
    private fun <T> dbAndGet(block: suspend () -> T): T {
        var result: T? = null
        Thread { result = runBlocking { block() } }.also { it.start() }.apply { join() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun idleUntil(cond: () -> Boolean, timeoutMs: Long = 5_000, message: String = "the condition") {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        check(cond()) { "timed out waiting for $message" }
    }

    @Test
    fun theRemoveContentDialogReflectsTheCurrentState_andNoDialogLingersAfterTheRemoval() {
        openContentPane()

        // One individual song, already played by a tag (so the dialog's
        // survival clause is about a real tag link, not a hypothetical one).
        dbAndGet {
            val entry = checkNotNull(app().repository.addEntryUnique(EntryType.AUDIO, "bravo", "content://bravo/0"))
            val tag = checkNotNull(app().repository.addTag("AABB00", "Lumi test tag"))
            check(app().repository.linkEntryToTag(tag.id, entry.id))
        }
        idleUntil(cond = { compose.onNodeWithText("bravo").isDisplayed() }, message = "the content row")

        // The row's delete action opens the remove-content confirmation.
        compose.onNodeWithContentDescription("Delete").performClick()
        idleUntil(cond = { compose.onNodeWithText("Remove content?").isDisplayed() }, message = "the remove-content dialog")

        // The copy reflects the CURRENT state (the post-migration effect):
        // the song leaves the app, and the tags that play it keep a
        // "song not found" row in its place. The pre-branch cascade claim
        // ("removed ... from every tag") is gone - the link survives the
        // removal, it is not removed from the tag.
        val currentCopy =
            "“bravo” will be removed from the app. " +
                "Tags that play it keep a “song not found” row in its place."
        check(compose.onNodeWithText(currentCopy).isDisplayed()) {
            "the remove-content dialog must state the survival effect, not the old cascade"
        }

        // Confirming settles the removal: the dialog is gone (no stale
        // dialog lingers) and the song's row leaves the content list.
        compose.onNodeWithText("Remove").performClick()
        idleUntil(
            cond = {
                compose.onNodeWithText("Remove content?").isNotDisplayed() &&
                    compose.onNodeWithText(currentCopy).isNotDisplayed()
            },
            message = "the dialog to settle",
        )
        idleUntil(
            cond = {
                compose.onNodeWithText("bravo").isNotDisplayed() &&
                    compose.onNodeWithText("Nothing here yet. Add a song.").isDisplayed()
            },
            message = "the removed row to leave the content list",
        )
        // And the removal actually happened (the dialog's effect, not
        // just its copy): the content row is gone from the database.
        check(dbAndGet { app().repository.observeIndividualEntries().first() }.isEmpty())
    }
}

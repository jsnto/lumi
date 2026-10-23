package com.lumi.player.ui

import android.nfc.Tag
import android.os.Bundle
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.lumi.player.LumiApp
import com.lumi.player.data.EntryType
import com.lumi.player.nfc.LumiTagData
import com.lumi.player.nfc.TagPayloadRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import com.lumi.player.ui.testutil.idleUntil
import com.lumi.player.ui.testutil.sendTag
import com.lumi.player.ui.testutil.syntheticTag
import com.lumi.player.ui.testutil.app
import com.lumi.player.ui.testutil.dbAndGet
import com.lumi.player.ui.testutil.viewModelOf
import com.lumi.player.ui.testutil.TECH_NDEF
import com.lumi.player.ui.testutil.TECH_NFCA
import com.lumi.player.ui.testutil.setupMainActivity
import com.lumi.player.ui.testutil.clickSettings
import com.lumi.player.ui.testutil.clickMenuRow
import com.lumi.player.ui.testutil.clickTagRow

/**
 * The tag details page's "Save to NFC tag" section: the derived in-sync
 * indicator (the tag's current system data against its last known per-uid
 * saved memory - Unknown with no memory, InSync when it matches, Differs
 * when it no longer does), its "Scan to compare" action (a READ of the
 * tag's own tag that records the card's actual contents as the memory -
 * a blank card clears the record; a mismatched or unreadable tag records
 * nothing), and the write action's IN SYNC landing (a successful write
 * records the memory, so the indicator reads InSync afterwards). The NFC
 * tags list's name sort (case-insensitive, stable tie-break) is pinned
 * here too.
 *
 * Tag reads are driven the way the NFC tests drive them: reflectively-
 * built [Tag]s delivered as EXTRA_TAG intents via
 * [ActivityController.newIntent], the same shape the system's foreground
 * dispatch delivers. The card's payload comes through the
 * [LumiViewModel.songPayloadReader] seam (a read of the tag's own NDEF
 * payload); the physical write through [LumiViewModel.tagWriter].
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TagSaveSyncUiTest {

    private companion object {
        const val TAG_UID = "AABBCCDDEEFF0011"
        const val OTHER_UID = "1122334455667788"
        const val IN_SYNC = "In sync - no changes since the last save."
        const val DIFFERS = "This tag's data differs from the saved tag."
        const val UNKNOWN = "Nothing saved or compared yet."
    }

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        markSetupCompleteForTests()
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
    }

    /** True when the [text] node exists (the guarded probe for [idleUntil]). */
    private fun hasText(text: String): Boolean =
        try {
            compose.onNodeWithText(text).assertExists()
            true
        } catch (e: AssertionError) {
            false
        }

    /** Forges a presented-tag handle the way the framework fills one. */
    private fun forgeTag(uid: String): Tag {
        val ndefBundle = Bundle().apply {
            putInt("ndefmaxlength", 151)
            putInt("ndefcardstate", 2)
            putInt("ndeftype", 2)
        }
        return syntheticTag(
            uid = uid,
            techs = intArrayOf(TECH_NFCA, TECH_NDEF),
            techExtras = arrayOf(Bundle(), ndefBundle),
        )
    }

    /** Registers the tag under a known name and opens its details page. */
    private fun controllerWithOpenTagPage(name: String = "Porch"): ActivityController<MainActivity> {
        dbAndGet { app().repository.addTag(TAG_UID, name) }
        val controller = compose.setupMainActivity()
        openTagPage(name)
        return controller
    }

    /** Navigates to the registered tag's details page (from the player screen). */
    private fun openTagPage(name: String = "Porch") {
        compose.clickSettings()
        compose.clickMenuRow("NFC tags")
        compose.clickTagRow(name)
        compose.onNodeWithText("Tag details").assertIsDisplayed()
    }

    /** Scrolls the save section into view and waits for it to be up. */
    private fun openSaveSection() {
        compose.onNodeWithText("Save to NFC tag").performScrollTo()
        compose.waitForIdle()
    }

    /** Clicks the section's [text] button once the merged tree has it. */
    private fun clickSectionButton(text: String) {
        compose.onNodeWithText(text).performScrollTo()
        compose.idleUntil(
            { compose.onNodeWithText(text).isDisplayed() },
            message = "the \"$text\" button to be displayed",
        )
        compose.onNodeWithText(text).performClick()
        compose.waitForIdle()
    }

    // ---- the indicator: the three derived states ----

    @Test
    fun theSaveSectionShowsTheUnknownIndicatorAndBothButtons() {
        val controller = controllerWithOpenTagPage()
        viewModelOf(controller.get())

        openSaveSection()
        // No saved memory for the uid yet: the indicator reads UNKNOWN,
        // and both actions are offered (the write button's gates hold -
        // the radio is on, the payload is under the cap).
        compose.onNodeWithText(UNKNOWN).assertExists()
        compose.onNodeWithText("Scan to compare").assertExists()
        compose.onNodeWithText("Write to NFC tag").assertExists()
        compose.onNodeWithText(IN_SYNC).assertDoesNotExist()
        compose.onNodeWithText(DIFFERS).assertDoesNotExist()
    }

    @Test
    fun anEditedTagReadsDiffersFromItsSavedMemory() {
        val controller = controllerWithOpenTagPage()
        val viewModel = viewModelOf(controller.get())

        // The memory matches the tag's current system data: IN SYNC.
        dbAndGet { app().repository.rememberTag(TAG_UID, "Porch", emptyList()) }
        openSaveSection()
        compose.idleUntil({ hasText(IN_SYNC) }, message = "the in-sync indicator")

        // Any tag edit flips the indicator to DIFFERS at once - a rename
        // (the derived state reads the tag row live, no event needed).
        val tag = dbAndGet { app().repository.tagByUid(TAG_UID) }!!
        viewModel.renameTag(tag, "Hallway")
        compose.idleUntil({ hasText(DIFFERS) }, message = "the differs indicator after the rename")
        assertFalse("the in-sync copy is gone after the rename", hasText(IN_SYNC))
    }

    @Test
    fun aSongChangeAlsoReadsDiffersFromItsSavedMemory() {
        val controller = controllerWithOpenTagPage()
        // The memory matches the tag's (empty) song list: IN SYNC.
        dbAndGet { app().repository.rememberTag(TAG_UID, "Porch", emptyList()) }
        openSaveSection()
        compose.idleUntil({ hasText(IN_SYNC) }, message = "the in-sync indicator")

        // A song added to the tag flips the indicator to DIFFERS.
        dbAndGet {
            val tag = app().repository.tagByUid(TAG_UID)!!
            app().repository.linkEntryToTag(
                tag.id,
                app().repository.addEntry(EntryType.AUDIO, "First song", "content://first").id,
            )
        }
        compose.idleUntil({ hasText(DIFFERS) }, message = "the differs indicator after the song was added")
        assertFalse("the in-sync copy is gone after the song was added", hasText(IN_SYNC))
    }

    // ---- the compare scan ----

    /** Arms the compare scan through the button and asserts the dialog's copy. */
    private fun armCompareScan() {
        clickSectionButton("Scan to compare")
        compose
            .onNodeWithText(
                "Hold the tag's own tag - the tag whose NFC ID matches the tag's - against the back of the phone to read what it carries. A tag with a different NFC ID is not recorded.",
            )
            .assertExists()
    }

    @Test
    fun aCompareScanOfTheTagsOwnTagRecordsTheCardsActualContents() {
        val controller = controllerWithOpenTagPage()
        val viewModel = viewModelOf(controller.get())

        // The card's ACTUAL contents differ from the tag's current system
        // data (the tag has no songs): the compare records them as the
        // memory and reports the difference.
        viewModel.songPayloadReader = { _ ->
            TagPayloadRead.Answered(LumiTagData("Porch", listOf("First song", "Gone song")))
        }
        armCompareScan()
        compose.sendTag(controller, forgeTag(TAG_UID))

        compose.idleUntil(
            { ShadowToast.getTextOfLatestToast() == DIFFERS },
            message = "the compare's differs report",
        )
        assertEquals(
            "the compare records the card's actual contents as the memory",
            listOf("First song", "Gone song"),
            dbAndGet { app().repository.memoryForUid(TAG_UID) }?.songs,
        )
        // The indicator recomputes from the comparison: DIFFERS.
        openSaveSection()
        compose.idleUntil({ hasText(DIFFERS) }, message = "the differs indicator")
        // The scan settled: the card was not captured for registration
        // and no write path was armed.
        assertNull("the presented card was not captured for registration", viewModel.capturedUid.value)
        assertFalse("the scan is disarmed after settling", viewModel.scanActive.value)
        assertNull("no write was armed by the compare", viewModel.cardWriteTarget.value)
    }

    @Test
    fun aCompareScanThatFindsTheCardMatchingSystemDataReadsInSync() {
        val controller = controllerWithOpenTagPage()
        val viewModel = viewModelOf(controller.get())

        // The tag plays one song; the card carries exactly that: the
        // compare records a matching memory and reports InSync.
        dbAndGet {
            val tag = app().repository.tagByUid(TAG_UID)!!
            app().repository.linkEntryToTag(
                tag.id,
                app().repository.addEntry(EntryType.AUDIO, "First song", "content://first").id,
            )
        }
        viewModel.songPayloadReader = { _ ->
            TagPayloadRead.Answered(LumiTagData("Porch", listOf("First song")))
        }
        armCompareScan()
        compose.sendTag(controller, forgeTag(TAG_UID))

        compose.idleUntil(
            { ShadowToast.getTextOfLatestToast() == IN_SYNC },
            message = "the compare's in-sync report",
        )
        openSaveSection()
        compose.idleUntil({ hasText(IN_SYNC) }, message = "the in-sync indicator")
    }

    @Test
    fun aCompareScanOfAMatchingBlankTagClearsTheRecord_andTheIndicatorGoesUnknown() {
        val controller = controllerWithOpenTagPage()
        val viewModel = viewModelOf(controller.get())

        // A saved memory exists (IN SYNC), then the tag is wiped: the
        // compare of the matching BLANK card clears the record and the
        // indicator goes UNKNOWN.
        dbAndGet { app().repository.rememberTag(TAG_UID, "Porch", emptyList()) }
        openSaveSection()
        compose.idleUntil({ hasText(IN_SYNC) }, message = "the in-sync indicator before the wipe")
        viewModel.songPayloadReader = { _ -> TagPayloadRead.Answered(null) }
        armCompareScan()
        compose.sendTag(controller, forgeTag(TAG_UID))

        compose.idleUntil(
            { ShadowToast.getTextOfLatestToast() == "That tag carries no saved data." },
            message = "the blank-card report",
        )
        assertNull("the blank card cleared the record", dbAndGet { app().repository.memoryForUid(TAG_UID) })
        openSaveSection()
        compose.idleUntil({ hasText(UNKNOWN) }, message = "the unknown indicator after the clear")
    }

    @Test
    fun aCompareScanOfAMismatchedTagIsReported_andRecordsNothing() {
        val controller = controllerWithOpenTagPage()
        val viewModel = viewModelOf(controller.get())

        // The memory matches the tag's data (IN SYNC). A DIFFERENT tag's
        // card is presented: reported with the write path's own verdict
        // language, and nothing is recorded (the memory is untouched).
        dbAndGet { app().repository.rememberTag(TAG_UID, "Porch", emptyList()) }
        openSaveSection()
        compose.idleUntil({ hasText(IN_SYNC) }, message = "the in-sync indicator before the mismatch")
        var reads = 0
        viewModel.songPayloadReader = { _ ->
            reads++
            TagPayloadRead.Answered(LumiTagData("Other", listOf("Other song")))
        }
        armCompareScan()
        compose.sendTag(controller, forgeTag(OTHER_UID))

        compose.idleUntil(
            {
                ShadowToast.getTextOfLatestToast() ==
                    "That card's NFC ID doesn't match this tag's - nothing was written."
            },
            message = "the mismatch report (the write path's verdict language)",
        )
        assertEquals("the mismatched card's read is not consumed", 0, reads)
        assertEquals(
            "the mismatched card recorded nothing",
            emptyList<String>(),
            dbAndGet { app().repository.memoryForUid(TAG_UID) }?.songs,
        )
        compose.idleUntil({ hasText(IN_SYNC) }, message = "the in-sync indicator to hold")
    }

    @Test
    fun aCompareScanThatCannotReadIsReported_andRecordsNothing() {
        val controller = controllerWithOpenTagPage()
        val viewModel = viewModelOf(controller.get())

        // A read that cannot connect says nothing about the card's
        // payload: reported, and the last known memory stays (the
        // indicator keeps reading IN SYNC).
        dbAndGet { app().repository.rememberTag(TAG_UID, "Porch", emptyList()) }
        openSaveSection()
        compose.idleUntil({ hasText(IN_SYNC) }, message = "the in-sync indicator before the failed read")
        viewModel.songPayloadReader = { _ -> TagPayloadRead.Failed }
        armCompareScan()
        compose.sendTag(controller, forgeTag(TAG_UID))

        compose.idleUntil(
            { ShadowToast.getTextOfLatestToast() == "Couldn't read that tag - try again." },
            message = "the failed-read report",
        )
        assertEquals(
            "the failed read kept the last known memory",
            emptyList<String>(),
            dbAndGet { app().repository.memoryForUid(TAG_UID) }?.songs,
        )
        compose.idleUntil({ hasText(IN_SYNC) }, message = "the in-sync indicator to hold")
    }

    // ---- the write's IN SYNC landing ----

    @Test
    fun aSuccessfulWriteLandsTheIndicatorInSync() {
        val controller = controllerWithOpenTagPage()
        val viewModel = viewModelOf(controller.get())

        // No memory yet (UNKNOWN); the write lands and records the
        // memory, so the indicator reads IN SYNC afterwards.
        openSaveSection()
        compose.onNodeWithText(UNKNOWN).assertExists()
        viewModel.tagWriter = { _, _ -> true }
        clickSectionButton("Write to NFC tag")
        compose
            .onNodeWithText(
                "Hold the tag's own tag - the tag whose NFC ID matches the tag's - against the back of the phone to save this tag's data to it. A tag with a different NFC ID is not written.",
            )
            .assertExists()
        compose.sendTag(controller, forgeTag(TAG_UID))

        compose.idleUntil(
            { ShadowToast.getTextOfLatestToast() == "Saved to the tag." },
            message = "the save confirmation",
        )
        openSaveSection()
        compose.idleUntil({ hasText(IN_SYNC) }, message = "the in-sync indicator after the write")
        assertFalse("the unknown copy is gone after the write", hasText(UNKNOWN))
    }

    // ---- the compare button's gate (the phone's radio alone - the
    // compare is a read, so an over-cap or unwritable tag never refuses
    // it; the write keeps its own gates) ----

    @Test
    fun anOverCapTagStillOffersTheCompareButton_onlyTheWriteIsRefused() {
        val controller = controllerWithOpenTagPage()
        val viewModel = viewModelOf(controller.get())
        val tagId = dbAndGet { app().repository.tagByUid(TAG_UID)!!.id }
        // Grow the tag's list over the app's write cap (the write's own
        // size refusal source - the same list the write's over-cap pin
        // uses).
        val names = (0 until 12).map { "Song %02d ".format(it + 1) + "x".repeat(42) }
        dbAndGet {
            names.forEach {
                app().repository.linkEntryToTag(
                    tagId,
                    app().repository.addEntry(EntryType.AUDIO, it, "content://$it").id,
                )
            }
        }

        openSaveSection()
        compose.idleUntil(
            {
                try {
                    compose
                        .onNodeWithText("This tag's data is too large for the tag payload - nothing can be written.")
                        .assertExists()
                    true
                } catch (e: AssertionError) {
                    false
                }
            },
            message = "the over-cap note",
        )
        // The WRITE is refused by its size gate ...
        assertTrue(
            "the write button is disabled while the payload is over the cap",
            compose.onNodeWithText("Write to NFC tag").fetchSemanticsNode()
                .config.contains(SemanticsProperties.Disabled),
        )
        // ... but the COMPARE is a read (reading an over-cap tag is a
        // legitimate compare result), so its gate - the radio alone -
        // does not hold.
        assertFalse(
            "the compare button is not refused by the payload's size",
            compose.onNodeWithText("Scan to compare").fetchSemanticsNode()
                .config.contains(SemanticsProperties.Disabled),
        )
        // ... and tapping it arms the COMPARE scan (never the write).
        clickSectionButton("Scan to compare")
        compose
            .onNodeWithText(
                "Hold the tag's own tag - the tag whose NFC ID matches the tag's - against the back of the phone to read what it carries. A tag with a different NFC ID is not recorded.",
            )
            .assertExists()
        assertTrue("the compare scan is armed", viewModel.scanActive.value)
        assertEquals(TAG_UID, viewModel.cardCompareTarget.value?.uid)
        assertNull("the compare arms no write", viewModel.cardWriteTarget.value)
    }

    @Test
    fun theRadioOffDisablesTheCompareButton_asWell() {
        // The compare goes through the phone's radio too: with the radio
        // off neither action can run, so the compare button is disabled
        // just like the write's (and a tap arms nothing).
        NfcTestState.radioEnabled = false
        dbAndGet { app().repository.addTag(TAG_UID, "Porch") }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        // The launch prompt (the radio is off) overlays the screen first.
        compose.onNodeWithText("NFC is off").assertIsDisplayed()
        compose.onNodeWithText("Later").performClick()
        compose.waitForIdle()
        openTagPage()
        openSaveSection()
        compose.idleUntil(
            {
                try {
                    compose.onNodeWithText("Turn on NFC to save to tag.").assertExists()
                    true
                } catch (e: AssertionError) {
                    false
                }
            },
            message = "the radio-off note",
        )
        assertTrue(
            "the compare button is disabled while the radio is off",
            compose.onNodeWithText("Scan to compare").fetchSemanticsNode()
                .config.contains(SemanticsProperties.Disabled),
        )
        val viewModel = viewModelOf(controller.get())
        compose.onNodeWithText("Scan to compare").performClick()
        compose.waitForIdle()
        assertFalse("the disabled compare button arms no scan", viewModel.scanActive.value)
        assertNull("the disabled compare button sets no compare target", viewModel.cardCompareTarget.value)
    }

    @Test
    fun theScanDialogTitleIsAlwaysScanATag_forTheWriteAndCompareScans() {
        // The dialog's title no longer branches on the scan's purpose:
        // the write scan AND the compare scan both read "Scan a tag" (the
        // old "Scan a card" title is gone).
        val controller = controllerWithOpenTagPage()

        clickSectionButton("Write to NFC tag")
        compose.onNodeWithText("Scan a tag").assertIsDisplayed()
        compose.onNodeWithText("Scan a card").assertDoesNotExist()
        compose
            .onNodeWithText(
                "Hold the tag's own tag - the tag whose NFC ID matches the tag's - against the back of the phone to save this tag's data to it. A tag with a different NFC ID is not written.",
            )
            .assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()

        openSaveSection()
        clickSectionButton("Scan to compare")
        compose.onNodeWithText("Scan a tag").assertIsDisplayed()
        compose.onNodeWithText("Scan a card").assertDoesNotExist()
        compose
            .onNodeWithText(
                "Hold the tag's own tag - the tag whose NFC ID matches the tag's - against the back of the phone to read what it carries. A tag with a different NFC ID is not recorded.",
            )
            .assertExists()
    }

    // ---- the tags pane's name sort ----

    @Test
    fun theNfcTagsListIsSortedByTagNameCaseInsensitively() {
        // Added out of order, mixed case, with an equal name (the
        // tie-break: the earlier-created row reads first).
        dbAndGet {
            app().repository.addTag("BB11", "bravo")
            app().repository.addTag("AA01", "Alpha")
            app().repository.addTag("CC22", "charlie")
            app().repository.addTag("DD33", "alpha")
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("NFC tags").performClick()
        compose.waitForIdle()

        // The rows are found by their uid detail (unique per row); the
        // layout order (the rows' tops in the root's coordinate space,
        // without scrolling) is the case-insensitive name order with the
        // stable tie-break (Alpha before the later "alpha").
        val topOf = { uid: String ->
            compose.onNodeWithText(uid).fetchSemanticsNode().boundsInRoot.top
        }
        assertTrue("Alpha sorts before alpha (case-insensitive, stable tie-break)", topOf("AA01") < topOf("DD33"))
        assertTrue("the alpha rows sort before bravo", topOf("DD33") < topOf("BB11"))
        assertTrue("bravo sorts before charlie", topOf("BB11") < topOf("CC22"))
        // All four rows are present (nothing dropped by the sort).
        compose.onNodeWithText("bravo").assertExists()
        compose.onNodeWithText("Alpha").assertExists()
        compose.onNodeWithText("charlie").assertExists()
        compose.onNodeWithText("alpha").assertExists()
    }
}

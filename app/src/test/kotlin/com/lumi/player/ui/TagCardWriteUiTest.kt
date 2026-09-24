package com.lumi.player.ui

import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.lumi.player.LumiApp
import com.lumi.player.data.PlayMode
import com.lumi.player.nfc.LumiTagData
import com.lumi.player.nfc.LumiTagNdef
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast

/**
 * The tag details page's card information and its "Save to NFC card"
 * action.
 *
 * DETAILS: a presented tag's additional card information - its tech list
 * and, when the handle carries the NDEF tech, its NDEF type, NDEF
 * capacity, and writability - is read from the tag's DISCOVERY data only
 * (no connection, no RF) and shown in the "Tag details" section only when
 * it is actually available: a tag without the NDEF tech shows no NDEF
 * fields, and the details are keyed by the tag's uid, so a read of
 * another tag never surfaces on (or persists into) this tag's page.
 *
 * SAVE TO CARD: the "Save to NFC card" section arms the app's existing
 * foreground-dispatch scan (no reader mode) targeted at a card write
 * for the tag's OWN card: a presented card is written the tag's data
 * (its name and its song list) through the existing write path only
 * when the card's NFC ID matches the tag's uid - a mismatched card is
 * reported and never written. For the matched card, the verdict is
 * discovery-time data: a card that isn't NDEF, is read-only, or is too
 * small to hold the payload is reported, and nothing is written to it;
 * a card that passes is written and the save is confirmed. The scan
 * settles on any read (success, failure, mismatch, or cancel): further
 * reads are normal reads - never re-routed to the write, and never into
 * registration.
 *
 * Tag reads are driven the way the NFC tests drive them: reflectively-
 * built [Tag]s (with the NDEF tech's extras forged the way the framework
 * fills them on presentation) delivered as EXTRA_TAG intents via
 * [ActivityController.newIntent], the same shape the system's foreground
 * dispatch delivers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TagCardWriteUiTest {

    /** A tag uid distinct from the registered ones used by the tests. */
    private companion object {
        const val TAG_UID = "AABBCCDDEEFF0011"
        const val OTHER_UID = "1122334455667788"
        const val CARD_UID = "9988776655443322"
    }

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
    }

    private fun app(): LumiApp = ApplicationProvider.getApplicationContext()

    /** Runs a suspend block on the app's database and returns its result. */
    private fun <T> dbAndGet(block: suspend () -> T): T {
        var result: T? = null
        Thread { result = runBlocking { block() } }.also { it.start() }.apply { join() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun idleUntil(cond: () -> Boolean, timeoutMs: Long = 15_000, message: String = "the condition") {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            compose.waitForIdle()
            Thread.sleep(10)
        }
        check(cond()) { "timed out waiting for $message" }
    }

    /** The activity's view model (the same seam the other settings tests use). */
    private fun viewModelOf(activity: MainActivity): LumiViewModel {
        val field = MainActivity::class.java.getDeclaredField("viewModel")
        field.isAccessible = true
        return field.get(activity) as LumiViewModel
    }

    /**
     * Forges a presented-tag handle the way the framework's foreground
     * dispatch delivers one: the uid, the tech list (NfcA by default, plus
     * the NDEF tech when [ndef]), and - for the NDEF tech - the extras the
     * framework fills on presentation (the NDEF capacity, the card
     * state, and the NDEF type).
     */
    private fun forgeTag(
        uid: String,
        ndef: Boolean = true,
        writable: Boolean = true,
        maxNdefLength: Int = 151,
        ndefType: Int = 2,
    ): Tag {
        val techs = if (ndef) intArrayOf(1, 6) else intArrayOf(1)
        val ndefBundle = Bundle().apply {
            putInt("ndefmaxlength", maxNdefLength)
            putInt("ndefcardstate", if (writable) 2 else 0)
            putInt("ndeftype", ndefType)
        }
        val nfcTagCls = Class.forName("android.nfc.INfcTag")
        val ctor = Tag::class.java.getDeclaredConstructor(
            ByteArray::class.java,
            IntArray::class.java,
            Array<Bundle>::class.java,
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
            techs,
            if (ndef) arrayOf(Bundle(), ndefBundle) else arrayOf(Bundle()),
            0,
            0L,
            tagService,
        ) as Tag
    }

    /** Delivers [tag] to the resumed activity the way foreground dispatch would. */
    private fun sendTag(controller: ActivityController<MainActivity>, tag: Tag) {
        controller.newIntent(Intent(Intent.ACTION_MAIN).putExtra(NfcAdapter.EXTRA_TAG, tag))
        compose.waitForIdle()
    }

    /** Registers the tag under a known name and opens its details page. */
    private fun controllerWithOpenTagPage(): ActivityController<MainActivity> {
        dbAndGet { app().repository.addTag(TAG_UID, "Porch") }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        openTagPage()
        return controller
    }

    /** Navigates to the registered tag's details page (from the player screen). */
    private fun openTagPage() {
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("NFC tags").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Porch").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Tag details").assertIsDisplayed()
    }

    @Test
    fun aPresentedTagShowsItsAvailableCardDetails_onTheTagPage() {
        val controller = controllerWithOpenTagPage()

        sendTag(controller, forgeTag(TAG_UID))

        // The discovery-data details appear in the details section: the
        // tech list (NfcA + the NDEF tech), the NDEF type (type 2 - the
        // NFC Forum name), the NDEF capacity, and the writability.
        idleUntil({
            try {
                compose.onNodeWithText("NfcA, Ndef").assertExists()
                compose.onNodeWithText("NFC Forum Type 2").assertExists()
                compose.onNodeWithText("151 bytes").assertExists()
                compose.onNodeWithText("Yes").assertExists()
                true
            } catch (e: AssertionError) {
                false
            }
        }, message = "the card details to appear")
    }

    @Test
    fun aTagWithoutTheNdefTechShowsNoNdefFields() {
        val controller = controllerWithOpenTagPage()

        sendTag(controller, forgeTag(TAG_UID, ndef = false))

        // The tech list is available (the NfcA tech only) and shows; the
        // NDEF fields are not available on this handle and stay absent -
        // nothing is guessed.
        idleUntil({
            try {
                compose.onNodeWithText("NfcA").assertExists()
                compose.onNodeWithText("NDEF type").assertDoesNotExist()
                compose.onNodeWithText("NDEF capacity").assertDoesNotExist()
                compose.onNodeWithText("Writable").assertDoesNotExist()
                true
            } catch (e: AssertionError) {
                false
            }
        }, message = "the NDEF-less details to settle")
    }

    @Test
    fun cardDetailsAreKeyedByUid_aReadOfAnotherTagShowsNothingOnThisPage() {
        dbAndGet { app().repository.addTag(OTHER_UID, "Back porch") }
        val controller = controllerWithOpenTagPage()

        // A read of ANOTHER tag (registered, so its page exists): the
        // details are keyed by that tag's uid, so this page shows none of
        // it - no stale details.
        sendTag(controller, forgeTag(OTHER_UID))

        idleUntil(
            {
                try {
                    // The page itself (the tag's own name in the details
                    // section) is up and settled: the uid-keyed details
                    // carry a different uid, so no NDEF field shows here.
                    compose.onNodeWithText("NFC Forum Type 2").assertDoesNotExist()
                    compose.onNodeWithText("NDEF capacity").assertDoesNotExist()
                    true
                } catch (e: AssertionError) {
                    false
                }
            },
            message = "the uid-keyed details to stay away",
        )

        // The details live on the OTHER tag's page: back to the tags
        // list, open it - the details (already inspected) are there.
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Back porch").performClick()
        compose.waitForIdle()
        idleUntil({
            try {
                compose.onNodeWithText("NFC Forum Type 2").assertExists()
                true
            } catch (e: AssertionError) {
                false
            }
        }, message = "the other tag's details on its own page")
    }

    @Test
    fun cardDetailsPersist_aRescannedTagKeepsItsOwnDetails_afterScanningAnotherTag() {
        dbAndGet { app().repository.addTag(OTHER_UID, "Back porch") }
        val controller = controllerWithOpenTagPage()

        // The PORCH tag's card is presented: its details are observed.
        sendTag(controller, forgeTag(TAG_UID))
        idleUntil({
            try {
                compose.onNodeWithText("NFC Forum Type 2").assertExists()
                true
            } catch (e: AssertionError) {
                false
            }
        }, message = "the tag's own details to appear")

        // Another tag's card is presented (a normal read - the tag plays
        // nothing, so it reports and settles): its details are observed
        // too, keyed to THAT tag's uid.
        val viewModel = viewModelOf(controller.get())
        sendTag(controller, forgeTag(OTHER_UID, maxNdefLength = 64))
        idleUntil({
            val details = viewModel.tagCardDetails.value
            details.containsKey(TAG_UID) && details.containsKey(OTHER_UID)
        }, message = "both tags' details to be observed")
        assertEquals("the other tag's own capacity is its own detail", 64, viewModel.tagCardDetails.value[OTHER_UID]?.maxNdefSizeBytes)

        // Back to the PORCH page: the tag's OWN details persist - they
        // were not clobbered by the other tag's read (a last-read-only
        // store would show nothing here, and a shared single field would
        // show the other tag's 64-byte capacity).
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Porch").performClick()
        compose.waitForIdle()
        idleUntil({
            try {
                compose.onNodeWithText("NFC Forum Type 2").assertExists()
                compose.onNodeWithText("151 bytes").assertExists()
                true
            } catch (e: AssertionError) {
                false
            }
        }, message = "the persisted own-tag details to come back")
        // The other tag's details are NOT shown on this page.
        compose.onNodeWithText("64 bytes").assertDoesNotExist()
    }

    @Test
    fun savingToTheCard_writesTheTagsData_toAWritableCard() {
        val controller = controllerWithOpenTagPage()
        val viewModel = viewModelOf(controller.get())

        compose.onNodeWithText("Scan a card").performScrollTo()
        compose.onNodeWithText("Scan a card").performClick()
        compose.waitForIdle()
        // The scan dialog asks for the tag's own card (the card-write
        // copy - the NFC ID must match).
        compose.onNodeWithText("Hold the tag's own card - the card whose NFC ID matches the tag's - against the back of the phone to save this tag's data to it. A card with a different NFC ID is not written.").assertExists()

        // The physical write seam: record what is written; it must run
        // exactly once, for the card, with the tag's data.
        var writes = 0
        var written: Pair<Tag?, LumiTagData>? = null
        viewModel.tagWriter = { tag, data ->
            writes++
            written = tag to data
            true
        }

        // Present the tag's OWN card (its NFC ID matches the tag's uid)
        // - the card write only runs for the tag's own card.
        sendTag(controller, forgeTag(TAG_UID))

        idleUntil(
            { ShadowToast.getTextOfLatestToast() == "Saved to the card." },
            message = "the save confirmation",
        )
        assertEquals("the card write ran exactly once", 1, writes)
        // The tag has no songs yet, so the payload carries its name and
        // the (empty) ordered song list - and NO play mode (the written
        // payload no longer carries one)...
        assertEquals("the tag's name and song list are the payload (no play mode)",
            LumiTagData("Porch"),
            written?.second)
        // ...and the codec's encoding of the written payload confirms it:
        // the on-card JSON carries name + songs, no mode field.
        val onCard = org.json.JSONObject(LumiTagNdef.toJson(written!!.second))
        assertFalse("the on-card payload carries no play mode field", onCard.has("mode"))
        // ...and the successful write REMEMBERS the card: the per-card
        // memory holds the tag's name + songs for the card's uid (it
        // survives the tag's deletion and feeds a later re-scan).
        assertEquals(
            "the write remembers the card's name and song list",
            emptyList<String>(),
            dbAndGet { app().repository.memoryForUid(TAG_UID) }?.songs,
        )
        assertEquals("Porch", dbAndGet { app().repository.memoryForUid(TAG_UID) }?.name)
        // The scan settled (disarmed): the dialog is gone, the target
        // cleared, and the presented card never went to registration.
        assertTrue("the scan is disarmed after settling", !viewModel.scanActive.value)
        assertNull("the card-write target is cleared after settling", viewModel.cardWriteTarget.value)
        assertNull("the presented card was not captured for registration", viewModel.capturedUid.value)
        assertNull("no tag row was registered for the card", dbAndGet { app().repository.tagByUid(CARD_UID) })
        // The tag's own details section now occupies page space (the
        // presented card is the tag's own), so the bottom section scrolls
        // back into view before the display assertion (the short test
        // screen clips what it pushes below the fold).
        compose.onNodeWithText("Save to NFC card").performScrollTo()
        compose.onNodeWithText("Save to NFC card").assertIsDisplayed()
    }

    @Test
    fun savingToTheCardWritesTheOrderedSongList_andRemembersItForTheCard() {
        // The payload carries the tag's FULL ordered song list (a write
        // saves the songs too): a tag with songs writes them in link
        // order, missing songs included, and the successful write
        // remembers the list for the card's uid.
        val controller = controllerWithOpenTagPage()
        val viewModel = viewModelOf(controller.get())
        val tagId = dbAndGet { app().repository.tagByUid(TAG_UID)!!.id }
        val firstId = dbAndGet { app().repository.addEntry(com.lumi.player.data.EntryType.AUDIO, "First song", "content://first").id }
        val secondId = dbAndGet { app().repository.addEntry(com.lumi.player.data.EntryType.AUDIO, "Second song", "content://second").id }
        val goneId = dbAndGet { app().repository.addEntry(com.lumi.player.data.EntryType.AUDIO, "Gone song", "content://gone").id }
        dbAndGet {
            listOf(firstId, secondId, goneId).forEach { app().repository.linkEntryToTag(tagId, it) }
            app().repository.deleteEntry(
                app().repository.observeEntries().first().single { it.id == goneId },
            )
        }

        compose.onNodeWithText("Scan a card").performScrollTo()
        compose.onNodeWithText("Scan a card").performClick()
        compose.waitForIdle()

        var writes = 0
        var written: Pair<Tag?, LumiTagData>? = null
        viewModel.tagWriter = { tag, data ->
            writes++
            written = tag to data
            true
        }

        sendTag(controller, forgeTag(TAG_UID))

        idleUntil(
            { ShadowToast.getTextOfLatestToast() == "Saved to the card." },
            message = "the save confirmation",
        )
        assertEquals("the card write ran exactly once", 1, writes)
        assertEquals(
            "the payload carries the full ordered song list (the missing song's name included) and no play mode",
            LumiTagData(
                "Porch",
                listOf("First song", "Second song", "Gone song"),
            ),
            written?.second,
        )
        // The memory holds the same list for the card's uid.
        assertEquals(
            listOf("First song", "Second song", "Gone song"),
            dbAndGet { app().repository.memoryForUid(TAG_UID) }?.songs,
        )
    }

    @Test
    fun aMismatchedCardIsReported_andNothingIsWritten() {
        val controller = controllerWithOpenTagPage()
        val viewModel = viewModelOf(controller.get())

        compose.onNodeWithText("Scan a card").performScrollTo()
        compose.onNodeWithText("Scan a card").performClick()
        compose.waitForIdle()

        // The physical write seam: it must NEVER run for a card whose
        // NFC ID is not the tag's.
        var writes = 0
        viewModel.tagWriter = { _, _ ->
            writes++
            true
        }

        // A card the app has never seen - perfectly writable, but its
        // NFC ID does not match the tag's: the write is refused before
        // the discovery-data gate even looks at the card.
        sendTag(controller, forgeTag(CARD_UID))

        idleUntil(
            { ShadowToast.getTextOfLatestToast() == "That card's NFC ID doesn't match this tag's - nothing was written." },
            message = "the mismatch report",
        )
        assertEquals("the write seam never ran for the mismatched card", 0, writes)
        // The scan settled (disarmed) the same way a verdict does, and
        // the mismatched card went nowhere: no registration, no row.
        assertTrue("the scan is disarmed after the mismatch report", !viewModel.scanActive.value)
        assertNull("the card-write target is cleared", viewModel.cardWriteTarget.value)
        assertNull("the mismatched card was not captured for registration", viewModel.capturedUid.value)
        assertNull("no tag row was registered for the card", dbAndGet { app().repository.tagByUid(CARD_UID) })
        // The tag's own details section now occupies page space (the
        // presented card is the tag's own), so the bottom section scrolls
        // back into view before the display assertion (the short test
        // screen clips what it pushes below the fold).
        compose.onNodeWithText("Save to NFC card").performScrollTo()
        compose.onNodeWithText("Save to NFC card").assertIsDisplayed()
    }

    @Test
    fun aReadOnlyCardIsReported_andNothingIsWritten() {
        val controller = controllerWithOpenTagPage()
        val viewModel = viewModelOf(controller.get())

        compose.onNodeWithText("Scan a card").performScrollTo()
        compose.onNodeWithText("Scan a card").performClick()
        compose.waitForIdle()

        var writes = 0
        viewModel.tagWriter = { _, _ ->
            writes++
            false
        }

        // A read-only card (the NDEF card state says it cannot be
        // written): the verdict is discovery data - the write seam must
        // never run. (The card carries the tag's own NFC ID, so it gets
        // past the id-match gate to the discovery-data verdict.)
        sendTag(controller, forgeTag(TAG_UID, writable = false))

        idleUntil(
            { ShadowToast.getTextOfLatestToast() == "That card is read-only - nothing was written." },
            message = "the read-only report",
        )
        assertEquals("nothing was written to the read-only card", 0, writes)
        assertTrue(!viewModel.scanActive.value)
        assertNull(viewModel.cardWriteTarget.value)
    }

    @Test
    fun aCardTooSmallForThePayloadIsReported_withItsCapacityAndNeeded() {
        val controller = controllerWithOpenTagPage()
        val viewModel = viewModelOf(controller.get())

        compose.onNodeWithText("Scan a card").performScrollTo()
        compose.onNodeWithText("Scan a card").performClick()
        compose.waitForIdle()

        var writes = 0
        viewModel.tagWriter = { _, _ ->
            writes++
            false
        }

        // A writable card whose NDEF area cannot hold the payload: the
        // report names what it holds and what the data needs.
        sendTag(controller, forgeTag(TAG_UID, maxNdefLength = 20))

        idleUntil(
            {
                val toast = ShadowToast.getTextOfLatestToast()
                toast != null && toast.startsWith("That card is too small for the tag's data") &&
                    toast.contains("it holds 20 bytes") && toast.contains("Nothing was written.")
            },
            message = "the too-small report",
        )
        assertEquals("nothing was written to the too-small card", 0, writes)
    }

    @Test
    fun aTagListOverTheAppCapIsReported_asTooLarge_neverAsACardFailure() {
        // The backstop: the button's live size check greys out an
        // already-over-cap list, but the settle-side pre-check still
        // guards a list that grows over the cap AFTER the scan is armed
        // (the payload is sized against the app's own write cap
        // (LumiTagNdef.MAX_PAYLOAD_BYTES) at settle time, from the tag's
        // live song list). That refusal is the cap's, not any card's - it
        // must be reported as a permanent size fact, never as a card
        // failure the user could retry, and no physical write is
        // attempted.
        val controller = controllerWithOpenTagPage()
        val viewModel = viewModelOf(controller.get())

        // Arm the card-write scan while the tag's list is small (the
        // button is enabled - no songs yet)...
        compose.onNodeWithText("Scan a card").performScrollTo()
        compose.onNodeWithText("Scan a card").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Hold the tag's own card - the card whose NFC ID matches the tag's - against the back of the phone to save this tag's data to it. A card with a different NFC ID is not written.").assertExists()

        // ...then grow the list over the cap while the scan is armed.
        val tagId = dbAndGet { app().repository.tagByUid(TAG_UID)!!.id }
        val names = (0 until 12).map { "Song %02d ".format(it + 1) + "x".repeat(42) }
        dbAndGet {
            names.forEach {
                app().repository.linkEntryToTag(
                    tagId,
                    app().repository.addEntry(com.lumi.player.data.EntryType.AUDIO, it, "content://$it").id,
                )
            }
        }
        val needed = LumiTagNdef.messageByteLength(
            LumiTagNdef.toNdefMessage(LumiTagData("Porch", names)),
        )
        check(needed > LumiTagNdef.MAX_PAYLOAD_BYTES) { "the test list must exceed the app's write cap" }

        var writes = 0
        viewModel.tagWriter = { _, _ ->
            writes++
            false
        }

        // The tag's own card with plenty of capacity (a card the gate
        // would pass): the settle-time cap refusal still wins, and no
        // physical write is attempted at all.
        sendTag(controller, forgeTag(TAG_UID, maxNdefLength = 4096))

        idleUntil(
            {
                val toast = ShadowToast.getTextOfLatestToast()
                toast != null &&
                    toast.startsWith("The tag's data is too large to save to a card") &&
                    toast.contains("it needs $needed bytes") &&
                    toast.contains("Nothing was written.")
            },
            message = "the too-large report",
        )
        assertEquals("no physical write is attempted for an over-cap payload", 0, writes)
        assertTrue("the scan is disarmed after settling", !viewModel.scanActive.value)
        assertNull("the card-write target is cleared after settling", viewModel.cardWriteTarget.value)
        assertNull("the presented card was not captured for registration", viewModel.capturedUid.value)
    }

    @Test
    fun anOverCapTagShowsTheSizeNote_andDisablesTheSaveButton() {
        // The same long list the over-cap refusal pins: the payload
        // exceeds the app's write cap, so the save-to-card button is
        // greyed out and the note near it says the data is too large -
        // a write that cannot succeed cannot even be attempted.
        val controller = controllerWithOpenTagPage()
        val viewModel = viewModelOf(controller.get())
        val tagId = dbAndGet { app().repository.tagByUid(TAG_UID)!!.id }
        val names = (0 until 12).map { "Song %02d ".format(it + 1) + "x".repeat(42) }
        dbAndGet {
            names.forEach {
                app().repository.linkEntryToTag(
                    tagId,
                    app().repository.addEntry(com.lumi.player.data.EntryType.AUDIO, it, "content://$it").id,
                )
            }
        }

        compose.onNodeWithText("Scan a card").performScrollTo()
        compose.waitForIdle()

        // The note shows near the button (the mirror's text matcher is
        // exact, so the probe is the note's full text)...
        idleUntil(
            {
                try {
                    compose.onNodeWithText("This tag's data is too large for the card payload - nothing can be written.").assertExists()
                    true
                } catch (e: AssertionError) {
                    false
                }
            },
            message = "the over-cap note",
        )
        // ...and the button carries the disabled semantic...
        assertTrue(
            "the save button is disabled while the payload is over the cap",
            compose.onNodeWithText("Scan a card").fetchSemanticsNode()
                .config.contains(SemanticsProperties.Disabled),
        )
        // ...and a tap arms nothing: no scan, no card-write target.
        compose.onNodeWithText("Scan a card").performClick()
        compose.waitForIdle()
        assertFalse("the disabled button arms no scan", viewModel.scanActive.value)
        assertNull("the disabled button sets no card-write target", viewModel.cardWriteTarget.value)
    }

    @Test
    fun anUnderCapTagKeepsTheSaveButtonEnabled() {
        // A short song list keeps the payload under the app's write cap:
        // no size note, the button stays enabled exactly as before, and
        // tapping it arms the card-write scan.
        val controller = controllerWithOpenTagPage()
        val viewModel = viewModelOf(controller.get())
        val tagId = dbAndGet { app().repository.tagByUid(TAG_UID)!!.id }
        dbAndGet {
            app().repository.linkEntryToTag(
                tagId,
                app().repository.addEntry(com.lumi.player.data.EntryType.AUDIO, "Short song", "content://short").id,
            )
        }

        compose.onNodeWithText("Scan a card").performScrollTo()
        compose.waitForIdle()

        // No size note, and the button carries no disabled semantic.
        compose.onNodeWithText("This tag's data is too large for the card payload - nothing can be written.").assertDoesNotExist()
        assertFalse(
            "the save button stays enabled while the payload is under the cap",
            compose.onNodeWithText("Scan a card").fetchSemanticsNode()
                .config.contains(SemanticsProperties.Disabled),
        )

        // Tapping it arms the scan (the card-write dialog appears) -
        // then Cancel disarms it again.
        compose.onNodeWithText("Scan a card").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Hold the tag's own card - the card whose NFC ID matches the tag's - against the back of the phone to save this tag's data to it. A card with a different NFC ID is not written.").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertFalse("the scan is disarmed after the cancel", viewModel.scanActive.value)
    }

    @Test
    fun aCardWhoseDiscoveryReportsNoCapacityPassesTheGate_andTheWriteIsAttempted() {
        val controller = controllerWithOpenTagPage()
        val viewModel = viewModelOf(controller.get())

        compose.onNodeWithText("Scan a card").performScrollTo()
        compose.onNodeWithText("Scan a card").performClick()
        compose.waitForIdle()

        // The physical write seam: the gate must pass the card through
        // to the write attempt - an unknown capacity is not a refusal
        // verdict (only a known-small capacity is); the seam stands in
        // for that attempt here.
        var writes = 0
        viewModel.tagWriter = { _, _ ->
            writes++
            true
        }

        // A writable card whose discovery data reports NO NDEF capacity
        // (the NDEF length missing from the presentation extras - the
        // framework answers 0): the gate must not report "too small
        // (0 bytes)" and refuse the write.
        sendTag(controller, forgeTag(TAG_UID, maxNdefLength = 0))

        idleUntil(
            { ShadowToast.getTextOfLatestToast() == "Saved to the card." },
            message = "the save confirmation",
        )
        assertEquals("the physical write was attempted on the card", 1, writes)
        assertTrue(!viewModel.scanActive.value)
        assertNull(viewModel.cardWriteTarget.value)
    }

    @Test
    fun aNonNdefCardIsReported_andNothingIsWritten() {
        val controller = controllerWithOpenTagPage()
        val viewModel = viewModelOf(controller.get())

        compose.onNodeWithText("Scan a card").performScrollTo()
        compose.onNodeWithText("Scan a card").performClick()
        compose.waitForIdle()

        var writes = 0
        viewModel.tagWriter = { _, _ ->
            writes++
            false
        }

        // A card without the NDEF tech: there is no NDEF area to write
        // to, and the write seam must never run.
        sendTag(controller, forgeTag(TAG_UID, ndef = false))

        idleUntil(
            { ShadowToast.getTextOfLatestToast() == "That card isn't an NDEF card - nothing was written." },
            message = "the non-NDEF report",
        )
        assertEquals("nothing was written to the non-NDEF card", 0, writes)
    }

    @Test
    fun aWriteThatFailsIsReported_andTheScanStillSettles() {
        val controller = controllerWithOpenTagPage()
        val viewModel = viewModelOf(controller.get())

        compose.onNodeWithText("Scan a card").performScrollTo()
        compose.onNodeWithText("Scan a card").performClick()
        compose.waitForIdle()

        // The card passes the gate (the tag's own card: NDEF, writable,
        // big enough) but the physical write itself fails (the card
        // left range mid-write).
        viewModel.tagWriter = { _, _ -> false }

        sendTag(controller, forgeTag(TAG_UID))

        idleUntil(
            { ShadowToast.getTextOfLatestToast() == "Couldn't write to that card - keep it on the phone and try again." },
            message = "the write-failure report",
        )
        assertTrue(!viewModel.scanActive.value)
        assertNull(viewModel.cardWriteTarget.value)
    }

    @Test
    fun cancellingTheCardWriteDisarmsIt_andLaterReadsAreNormalReads() {
        val controller = controllerWithOpenTagPage()
        val viewModel = viewModelOf(controller.get())

        compose.onNodeWithText("Scan a card").performScrollTo()
        compose.onNodeWithText("Scan a card").performClick()
        compose.waitForIdle()

        // The scan dialog's Cancel disarms the write (the target clears
        // with the scan). The dialog's button is its fixed bottom row
        // (no scrollable ancestor - a scroll there throws) and sits in
        // view: click it directly.
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertTrue(!viewModel.scanActive.value)
        assertNull(viewModel.cardWriteTarget.value)

        // A card presented after the cancel is a NORMAL read: the card
        // write is not re-armed. Registration is the scan's job (it is
        // disarmed), so a read of a card the app has never seen does
        // nothing at all - no write, no registration, no playback.
        var writes = 0
        viewModel.tagWriter = { _, _ ->
            writes++
            true
        }
        sendTag(controller, forgeTag(CARD_UID))
        // Let the read settle (it is handled synchronously off the
        // delivery), then assert the do-nothing outcome.
        Thread.sleep(500)
        ShadowLooper.idleMainLooper()
        compose.waitForIdle()
        assertEquals("the cancelled write never runs", 0, writes)
        assertNull("a post-cancel read does not register the card", viewModel.capturedUid.value)
        compose.onNodeWithText("Name (optional)").assertDoesNotExist()
    }

    // ---- the phone's own NFC gating (a card write needs the phone's radio) ----

    @Test
    fun theRadioOffDisablesTheSaveButton_withANoteSayingWhy() {
        // The phone's own NFC radio is off: a card write goes through the
        // phone's radio, so the save-to-card control is greyed out and the
        // note next to it says why.
        NfcTestState.radioEnabled = false
        dbAndGet { app().repository.addTag(TAG_UID, "Porch") }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        // The launch prompt (the radio is off) overlays the screen first:
        // dismiss it, then open the tag's page.
        compose.onNodeWithText("NFC is off").assertIsDisplayed()
        compose.onNodeWithText("Later").performClick()
        compose.waitForIdle()
        openTagPage()
        compose.onNodeWithText("Scan a card").performScrollTo()
        compose.waitForIdle()

        // The note next to the button says why...
        idleUntil(
            {
                try {
                    compose.onNodeWithText("Turn on NFC to save to card.").assertExists()
                    true
                } catch (e: AssertionError) {
                    false
                }
            },
            message = "the radio-off note",
        )
        // ...and the button carries the disabled semantic...
        assertTrue(
            "the save button is disabled while the radio is off",
            compose.onNodeWithText("Scan a card").fetchSemanticsNode()
                .config.contains(SemanticsProperties.Disabled),
        )
        // ...and a tap arms nothing: no scan, no card-write target.
        val viewModel = viewModelOf(controller.get())
        compose.onNodeWithText("Scan a card").performClick()
        compose.waitForIdle()
        assertFalse("the disabled button arms no scan", viewModel.scanActive.value)
        assertNull("the disabled button sets no card-write target", viewModel.cardWriteTarget.value)
    }

    @Test
    fun noNfcHardwareDisablesTheSaveButton_withItsOwnNote() {
        // The device has no NFC hardware at all: the same greyed-out
        // control, with its own note.
        NfcTestState.reset()
        NfcTestState.adapterExists = false
        dbAndGet { app().repository.addTag(TAG_UID, "Porch") }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        compose.onNodeWithText("NFC not available").assertIsDisplayed()
        compose.onNodeWithText("OK").performClick()
        compose.waitForIdle()
        openTagPage()
        compose.onNodeWithText("Scan a card").performScrollTo()
        compose.waitForIdle()
        idleUntil(
            {
                try {
                    compose.onNodeWithText("This device has no NFC, so it can't save to a card.").assertExists()
                    true
                } catch (e: AssertionError) {
                    false
                }
            },
            message = "the no-hardware note",
        )
        assertTrue(
            "the save button is disabled with no NFC hardware",
            compose.onNodeWithText("Scan a card").fetchSemanticsNode()
                .config.contains(SemanticsProperties.Disabled),
        )
    }

    @Test
    fun theRadioReenabledWhileTheAppIsOpenReenablesTheSaveButton() {
        // The radio is off on launch (the control greyed with its note);
        // the user then turns it on WITHOUT leaving the app (a
        // quick-settings toggle) - the state-changed broadcast re-syncs
        // the usability, so the control re-enables live: no restart or
        // re-entry.
        NfcTestState.radioEnabled = false
        dbAndGet { app().repository.addTag(TAG_UID, "Porch") }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        compose.onNodeWithText("NFC is off").assertIsDisplayed()
        compose.onNodeWithText("Later").performClick()
        compose.waitForIdle()
        openTagPage()
        compose.onNodeWithText("Scan a card").performScrollTo()
        compose.waitForIdle()
        assertTrue(
            "the save button starts disabled while the radio is off",
            compose.onNodeWithText("Scan a card").fetchSemanticsNode()
                .config.contains(SemanticsProperties.Disabled),
        )

        // The radio comes on while the activity stays resumed: the
        // broadcast is the signal (the action the platform posts on a
        // quick-settings toggle) - delivered the way the platform
        // delivers it, straight into the registered receiver.
        NfcTestState.radioEnabled = true
        RuntimeEnvironment.getApplication()
            .sendBroadcast(Intent(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED))
        ShadowLooper.idleMainLooper()
        compose.waitForIdle()

        idleUntil(
            {
                try {
                    compose.onNodeWithText("Turn on NFC to save to card.").assertDoesNotExist()
                    !compose.onNodeWithText("Scan a card").fetchSemanticsNode()
                        .config.contains(SemanticsProperties.Disabled)
                } catch (e: AssertionError) {
                    false
                }
            },
            message = "the save button to re-enable live",
        )
        // And the re-enabled button arms the write scan when tapped.
        val viewModel = viewModelOf(controller.get())
        compose.onNodeWithText("Scan a card").performClick()
        compose.waitForIdle()
        assertTrue("the re-enabled button arms the card-write scan", viewModel.scanActive.value)
    }

    // ---- the card's own writability gating (the tag's card, as its last
    // presented handle reported it, refuses the write) ----

    @Test
    fun aReadOnlyCardDisablesTheSaveButton_withANoteSayingWhy() {
        // The tag's OWN card, as its last presented handle reported it,
        // is READ-ONLY: the settle-time gate would refuse it from the
        // same discovery data, so the save-to-card control is greyed out
        // and the note next to it says why - a write that cannot succeed
        // cannot even be attempted.
        val controller = controllerWithOpenTagPage()
        val viewModel = viewModelOf(controller.get())

        // A normal read of the tag's own (read-only) card records its
        // discovery details for the tag's uid - the details section shows
        // the same fact (Writable: No) that the gate would reach...
        sendTag(controller, forgeTag(TAG_UID, writable = false))
        idleUntil(
            {
                try {
                    compose.onNodeWithText("No").assertExists()
                    true
                } catch (e: AssertionError) {
                    false
                }
            },
            message = "the read-only detail",
        )
        compose.onNodeWithText("Scan a card").performScrollTo()

        // ...the note next to the button says why...
        idleUntil(
            {
                try {
                    compose.onNodeWithText("That card is read-only, so it can't be written.").assertExists()
                    true
                } catch (e: AssertionError) {
                    false
                }
            },
            message = "the read-only note",
        )
        // ...and the button carries the disabled semantic...
        assertTrue(
            "the save button is disabled while the tag's card is read-only",
            compose.onNodeWithText("Scan a card").fetchSemanticsNode()
                .config.contains(SemanticsProperties.Disabled),
        )
        // ...and a tap arms nothing: no scan, no card-write target.
        compose.onNodeWithText("Scan a card").performClick()
        compose.waitForIdle()
        assertFalse("the disabled button arms no scan", viewModel.scanActive.value)
        assertNull("the disabled button sets no card-write target", viewModel.cardWriteTarget.value)
    }

    @Test
    fun aNonNdefCardDisablesTheSaveButton_withItsOwnNote() {
        // The tag's OWN card, as its last presented handle reported it,
        // carries NO NDEF tech: there is no NDEF area the write could
        // reach, so the save-to-card control is greyed out with its own
        // note, and a tap arms nothing.
        val controller = controllerWithOpenTagPage()
        val viewModel = viewModelOf(controller.get())

        sendTag(controller, forgeTag(TAG_UID, ndef = false))
        compose.onNodeWithText("Scan a card").performScrollTo()

        idleUntil(
            {
                try {
                    compose.onNodeWithText("That card isn't an NDEF card, so it can't be written.").assertExists()
                    true
                } catch (e: AssertionError) {
                    false
                }
            },
            message = "the non-NDEF note",
        )
        assertTrue(
            "the save button is disabled while the tag's card is not an NDEF card",
            compose.onNodeWithText("Scan a card").fetchSemanticsNode()
                .config.contains(SemanticsProperties.Disabled),
        )
        compose.onNodeWithText("Scan a card").performClick()
        compose.waitForIdle()
        assertFalse("the disabled button arms no scan", viewModel.scanActive.value)
        assertNull("the disabled button sets no card-write target", viewModel.cardWriteTarget.value)
    }

    @Test
    fun aWritableCardKeepsTheSaveButtonEnabled() {
        // The tag's OWN card, as its last presented handle reported it,
        // is NDEF and writable: the card offers no refusal - its notes
        // stay absent and the button stays enabled exactly as before, so
        // a tap arms the write scan.
        val controller = controllerWithOpenTagPage()
        val viewModel = viewModelOf(controller.get())

        sendTag(controller, forgeTag(TAG_UID))
        compose.onNodeWithText("Scan a card").performScrollTo()
        compose.waitForIdle()

        compose.onNodeWithText("That card is read-only, so it can't be written.").assertDoesNotExist()
        compose.onNodeWithText("That card isn't an NDEF card, so it can't be written.").assertDoesNotExist()
        assertFalse(
            "the save button stays enabled for the tag's own writable card",
            compose.onNodeWithText("Scan a card").fetchSemanticsNode()
                .config.contains(SemanticsProperties.Disabled),
        )
        compose.onNodeWithText("Scan a card").performClick()
        compose.waitForIdle()
        assertTrue("the enabled button arms the card-write scan", viewModel.scanActive.value)
    }
}

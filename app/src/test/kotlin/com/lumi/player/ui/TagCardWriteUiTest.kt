package com.lumi.player.ui

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
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
 * foreground-dispatch scan (no reader mode) targeted at a card write:
 * whatever card is presented is written the tag's data (its name and the
 * app's play mode - the card's own payload shape) through the existing
 * write path. The card's verdict is discovery-time data: a card that
 * isn't NDEF, is read-only, or is too small to hold the payload is
 * reported, and nothing is written to it; a card that passes is written
 * and the save is confirmed. The scan settles on any read (success,
 * failure, or cancel): further reads are normal reads - never re-routed
 * to the write, and never into registration.
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
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("NFC tags").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Porch").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Tag details").assertIsDisplayed()
        return controller
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
    fun savingToTheCard_writesTheTagsData_toAWritableCard() {
        val controller = controllerWithOpenTagPage()
        val viewModel = viewModelOf(controller.get())

        compose.onNodeWithText("Scan a card").performScrollTo()
        compose.onNodeWithText("Scan a card").performClick()
        compose.waitForIdle()
        // The scan dialog asks for a card (the card-write copy).
        compose.onNodeWithText("Hold any NFC card - even one the app has never seen - against the back of the phone to save this tag's data to it.").assertExists()

        // The physical write seam: record what is written; it must run
        // exactly once, for the card, with the tag's data.
        var writes = 0
        var written: Pair<Tag?, LumiTagData>? = null
        viewModel.tagWriter = { tag, data ->
            writes++
            written = tag to data
            true
        }

        // Present a writable card - a uid the app has never seen (the
        // card write accepts ANY card, not just registered tags).
        sendTag(controller, forgeTag(CARD_UID))

        idleUntil(
            { ShadowToast.getTextOfLatestToast() == "Saved to the card." },
            message = "the save confirmation",
        )
        assertEquals("the card write ran exactly once", 1, writes)
        assertEquals("the tag's name and the app's play mode are the payload",
            LumiTagData("Porch", viewModel.playMode.value),
            written?.second)
        // The scan settled (disarmed): the dialog is gone, the target
        // cleared, and the presented card never went to registration.
        assertTrue("the scan is disarmed after settling", !viewModel.scanActive.value)
        assertNull("the card-write target is cleared after settling", viewModel.cardWriteTarget.value)
        assertNull("the presented card was not captured for registration", viewModel.capturedUid.value)
        assertNull("no tag row was registered for the card", dbAndGet { app().repository.tagByUid(CARD_UID) })
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
        // never run.
        sendTag(controller, forgeTag(CARD_UID, writable = false))

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
        sendTag(controller, forgeTag(CARD_UID, maxNdefLength = 20))

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
    fun aCardWhoseDiscoveryReportsNoCapacityPassesTheGate_andTheWriteIsAttempted() {
        val controller = controllerWithOpenTagPage()
        val viewModel = viewModelOf(controller.get())

        compose.onNodeWithText("Scan a card").performScrollTo()
        compose.onNodeWithText("Scan a card").performClick()
        compose.waitForIdle()

        // The physical write seam: the gate must pass the card through
        // to it - the unknown capacity is not a verdict, and the write's
        // own capacity check (on the connected handle, where the real
        // size is known) is the one that decides.
        var writes = 0
        viewModel.tagWriter = { _, _ ->
            writes++
            true
        }

        // A writable card whose discovery data reports NO NDEF capacity
        // (the NDEF length missing from the presentation extras - the
        // framework answers 0): the gate must not report "too small
        // (0 bytes)" and refuse the write.
        sendTag(controller, forgeTag(CARD_UID, maxNdefLength = 0))

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
        sendTag(controller, forgeTag(CARD_UID, ndef = false))

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

        // The card passes the gate (NDEF, writable, big enough) but the
        // physical write itself fails (the card left range mid-write).
        viewModel.tagWriter = { _, _ -> false }

        sendTag(controller, forgeTag(CARD_UID))

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
}

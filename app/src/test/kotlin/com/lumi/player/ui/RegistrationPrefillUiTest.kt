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
import com.lumi.player.data.EntryType
import com.lumi.player.data.PlayMode
import com.lumi.player.nfc.LumiTagData
import com.lumi.player.nfc.LumiTagNdef
import com.lumi.player.nfc.TagInspect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * pre-fills the name field (and the play mode), a captured tag whose
 * payload is missing or nameless pre-fills the card's REMEMBERED name, and
 * the dialog offers plain Save only - registration NEVER writes the
 * physical card (the explicit card-write action is the only write path).
 * The mode only records what the tag was registered with (form pre-fill on
 * re-read); saving a tag never changes the app's play mode.
 *
 * The per-card memory coverage too: a save remembers the card's last saved
 * name + song list per card uid, so a card whose tag was deleted and is
 * re-scanned restores the remembered name and songs - from the card's own
 * payload when it carries the song list (the new format), else from the
 * remembered list.
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

    private fun openSettingsAndCapture(data: LumiTagData?): LumiViewModel {
        val viewModel = openSettings()
        viewModel.captureForTest(UID, data)
        compose.waitForIdle()
        return viewModel
    }

    private fun db(block: suspend () -> Unit) {
        Thread { runBlocking { block() } }.also { it.start() }.apply { join() }
    }

    private fun <T> dbAndGet(block: suspend () -> T): T {
        var result: T? = null
        Thread { result = runBlocking { block() } }.also { it.start() }.apply { join() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun idleUntil(cond: () -> Boolean, timeoutMs: Long = 5_000, message: String = "condition") {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        check(cond()) { "timed out waiting for $message" }
    }

    /** Idles the main looper for [durationMs] unconditionally. */
    private fun pumpMain(durationMs: Long) {
        val deadline = System.currentTimeMillis() + durationMs
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
    }

    private fun savedTagName(uid: String): String? =
        dbAndGet { app().repository.tagByUid(uid)?.name }

    /** The registered tag's song names, in link order (empty when unregistered). */
    private fun songNamesForTagUid(uid: String): List<String> = dbAndGet {
        val tag = app().repository.tagByUid(uid)
        if (tag != null) app().repository.entriesForTag(tag.id).map { it.name } else emptyList()
    }

    private fun playModeOf(viewModel: LumiViewModel): PlayMode = viewModel.playMode.value

    // ---- prefill from the tag's own payload ----

    @Test
    fun registration_tagWithLumiPayload_prefillsNameAndMode_andOffersOnlySave() {
        val viewModel = openSettings()
        // Live capture shape: the dialog opens before the async NDEF read
        // lands, so the payload arrives after the form has already composed.
        viewModel.captureForTest(UID, null)
        compose.waitForIdle()
        compose.onNodeWithText("Tag captured").assertIsDisplayed()
        compose.onNodeWithText("Name (optional)").assertIsDisplayed()
        viewModel.captureForTest(
            UID,
            LumiTagData("Hallway", PlayMode.PROXIMITY, listOf("First song")),
        )
        compose.waitForIdle()
        // The capture's prefill (the async DB merge) has to land before
        // the dialog's form fills from it.
        idleUntil({ viewModel.capturedTagData.value?.name == "Hallway" }, message = "the payload's prefill")
        // The name field picks up the tag's payload. Registration offers
        // plain Save only - it never writes the physical card.
        compose.onNodeWithText("Hallway").assertIsDisplayed()
        compose.onNodeWithText("Save and write to tag").assertDoesNotExist()
        compose.onNodeWithText("Save").assertIsDisplayed()

        // Saving must NOT change the app's play mode, even though the tag's
        // preselected mode (PROXIMITY) differs from the app's (CONTINUOUS).
        var writes = 0
        viewModel.tagWriter = { _, _ -> writes++; false }
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        idleUntil({ savedTagName(UID) != null })
        assertEquals(
            "saving must not change the app play mode",
            PlayMode.CONTINUOUS,
            playModeOf(viewModel),
        )
        assertEquals("The saved tag must carry the pre-filled name", "Hallway", savedTagName(UID))
        assertEquals(
            "registration must never write the physical card",
            0,
            writes,
        )
    }

    @Test
    fun registration_savingTagMode_doesNotChangeAppPlayMode() {
        // App is in CONTINUOUS; the tag says PROXIMITY. Saving with the
        // preselected mode records it on the tag payload only - the app's
        // play mode must stay CONTINUOUS.
        val viewModel = openSettingsAndCapture(
            data = LumiTagData("Porch", PlayMode.PROXIMITY),
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
    fun registration_savingNeverWritesTheCard_andStoresTheTrimmedName() {
        // Registration never writes the physical card - not even when the
        // card would take the payload: the write is the explicit card-write
        // action's job. The stored name is still trimmed (the payload a
        // later card write carries must match it).
        val viewModel = openSettingsAndCapture(data = null)
        var writes = 0
        viewModel.tagWriter = { _, _ -> writes++; true }
        compose.onNodeWithText("Name (optional)").performTextInput("  Porch  ")
        compose.waitForIdle()
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        idleUntil({ savedTagName(UID) != null })
        assertEquals("the DB stores the trimmed name", "Porch", savedTagName(UID))
        assertEquals("registration must never write the card", 0, writes)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun registration_nameInput_isClampedToTagPayloadLimit() {
        // The dialog's name field clamps to MAX_NAME_LENGTH, so the name
        // saved to the DB and the name a later card write carries can never
        // diverge by truncation.
        // Pre-fill the field so the editable node can be selected by its
        // (merged-tree) text, then overwrite it with an oversized name.
        val viewModel = openSettingsAndCapture(
            data = LumiTagData("Prefill", PlayMode.CONTINUOUS),
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
    fun registration_plainTag_emptyFormAndSaveOnly() {
        val viewModel = openSettingsAndCapture(data = null)
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
        assertEquals(
            "The saved tag must have the empty name from the empty form",
            "",
            savedTagName(UID),
        )
    }

    @Test
    fun registration_typingBeforePayloadLands_keepsTypedName() {
        // The dialog composes (empty form) before the async NDEF read lands,
        // so the user may start typing in that window; when the payload then
        // arrives it must not clobber what the user already typed.
        val viewModel = openSettings()
        viewModel.captureForTest(UID, null)
        compose.waitForIdle()
        compose.onNodeWithText("Name (optional)").performTextInput("Mine")
        compose.waitForIdle()
        viewModel.captureForTest(
            UID,
            LumiTagData("Hallway", PlayMode.PROXIMITY, listOf("First song")),
        )
        compose.waitForIdle()
        compose.onNodeWithText("Mine").assertIsDisplayed()
        compose.onNodeWithText("Hallway").assertDoesNotExist()
    }

    // ---- per-card memory: the name + songs that survive the tag's deletion ----

    /**
     * Seeds the card's first life: the content entries the card's song
     * names resolve to, a first registration under [UID] (saved with its
     * payload's song list), and the tag's deletion (the card leaves the
     * app). The memory written by the first save is what the re-scan
     * restores from when the card carries no payload.
     */
    private fun seedFirstLifeAndDelete(viewModel: LumiViewModel, songs: List<String>) {
        db {
            songs.forEachIndexed { i, name ->
                app().repository.addEntry(EntryType.AUDIO, name, "content://song$i")
            }
        }
        viewModel.captureForTest(
            UID,
            LumiTagData("Porch", PlayMode.PROXIMITY, songs),
        )
        compose.waitForIdle()
        // The capture's prefill + restore songs (the async DB merge) have
        // to be published before the Save click consumes them.
        idleUntil({ viewModel.capturedTagData.value != null }, message = "the capture's prefill")
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        idleUntil({ savedTagName(UID) != null })
        // The save's restore links land in the same coroutine, right
        // after the tag row: poll until every link is written.
        idleUntil({ songNamesForTagUid(UID).size == songs.size }, message = "the restored links")
        assertEquals("the first save restores the carried songs", songs, songNamesForTagUid(UID))
        // The save's rememberTag (the per-card memory) is the coroutine's
        // LAST DB step: it reads the links the save just wrote, so it has
        // to land - with its full song list - before the tag row goes.
        idleUntil(
            { dbAndGet { app().repository.memoryForUid(UID) }?.songs == songs },
            message = "the remembered memory",
        )
        db { app().repository.deleteTag(app().repository.tagByUid(UID)!!) }
        assertNull("the tag row is gone", savedTagName(UID))
    }

    @Test
    fun rescanningADeletedTagWhoseCardCarriesTheSongList_restoresTheNameAndSongs() {
        val viewModel = openSettings()
        seedFirstLifeAndDelete(viewModel, listOf("First song", "Gone song"))

        // Re-scan the same card: the card was written in the new format, so
        // its payload still carries the ordered song list - the restore
        // source.
        viewModel.captureForTest(
            UID,
            LumiTagData("Porch", PlayMode.PROXIMITY, listOf("First song", "Gone song")),
        )
        compose.waitForIdle()
        idleUntil({ viewModel.capturedTagData.value != null }, message = "the capture's prefill")
        // The payload's name prefills the form again...
        compose.onNodeWithText("Porch").assertIsDisplayed()
        // ...and saving brings the tag back with its songs - name-for-name,
        // in the carried order, each resolving to the live content entry.
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        idleUntil({ savedTagName(UID) != null })
        idleUntil(
            { songNamesForTagUid(UID).size == 2 },
            message = "the restored links",
        )
        assertEquals(
            "the re-saved tag's songs are restored from the card payload",
            listOf("First song", "Gone song"),
            songNamesForTagUid(UID),
        )
        val resolved = dbAndGet {
            val tagId = app().repository.tagByUid(UID)!!.id
            app().repository.entriesForTag(tagId).map { it.entry }
        }
        assertEquals(
            "every restored song resolves to a live entry (plays again)",
            2,
            resolved.count { it != null },
        )
    }

    @Test
    fun rescanningADeletedTagWhoseCardCarriesNoPayload_restoresTheRememberedNameAndSongs() {
        val viewModel = openSettings()
        seedFirstLifeAndDelete(viewModel, listOf("First song", "Gone song"))

        // Re-scan the same card: a legacy card the app never wrote (its
        // payload carries no song list - or no payload at all). The
        // REMEMBERED name prefills the form and the REMEMBERED songs
        // restore on save.
        viewModel.captureForTest(UID, null)
        compose.waitForIdle()
        idleUntil({ viewModel.capturedTagData.value?.name == "Porch" }, message = "the remembered name's prefill")
        compose.onNodeWithText("Porch").assertIsDisplayed()
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        idleUntil({ savedTagName(UID) != null })
        assertEquals("the remembered name is saved again", "Porch", savedTagName(UID))
        idleUntil(
            { songNamesForTagUid(UID).size == 2 },
            message = "the remembered links",
        )
        assertEquals(
            "the remembered songs are restored onto the re-saved tag",
            listOf("First song", "Gone song"),
            songNamesForTagUid(UID),
        )
    }

    @Test
    fun rescanningADeletedTagWhoseCardCarriesAClampedLongSongName_restoresItToTheLiveEntry() {
        // A song whose content label exceeds the payload's 50-char name
        // cap: the card write carries its clamped form, and the re-scan's
        // save must resolve the clamped name back to the live entry (the
        // song plays again and the add-song picker flags the real row by
        // its full label) instead of stranding it as a "song not found"
        // row.
        val fullLabel = "a".repeat(45) + "-very-long-song-title.mp3"
        val viewModel = openSettings()
        seedFirstLifeAndDelete(viewModel, listOf(fullLabel))

        // The card was written in the new format: its payload carries the
        // song list in the codec's clamped form (the 50-char name cap).
        val written = checkNotNull(
            LumiTagNdef.fromJson(
                LumiTagNdef.toJson(
                    LumiTagData("Porch", PlayMode.PROXIMITY, listOf(fullLabel)),
                ),
            ),
        )
        viewModel.captureForTest(UID, written)
        compose.waitForIdle()
        idleUntil({ viewModel.capturedTagData.value != null }, message = "the capture's prefill")
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        idleUntil({ savedTagName(UID) != null })
        idleUntil({ songNamesForTagUid(UID).size == 1 }, message = "the restored link")

        // The clamped carried name resolved back to the content entry: the
        // restored link carries the entry's FULL label (the live name
        // resolution matches it) with the entry as its provenance.
        val rows = dbAndGet { app().repository.entriesForTag(app().repository.tagByUid(UID)!!.id) }
        assertEquals(1, rows.size)
        assertEquals(
            "the link stores the entry's full label, not the clamped one",
            fullLabel,
            rows.single().name,
        )
        assertEquals(
            "the link's provenance is the live entry its full label names",
            dbAndGet { app().repository.observeEntries().first().single { it.label == fullLabel }.id },
            rows.single().entryId,
        )
        assertNotNull("the restored song resolves to the live entry and plays", rows.single().entry)
    }

    // ---- stale capture read must not overwrite a newer session ----

    /**
     * A synthetic tag with the given UID. The stub SDK omits Tag's test
     * hooks; the real framework (loaded by Robolectric at runtime) exposes
     * the public constructor, so build it reflectively (same approach as
     * NfcAlwaysListeningUiTest). It carries no NDEF handle, so the real
     * reader would see nothing - tests replace [LumiViewModel.tagInspector].
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
        viewModel.tagInspector = { _, _ ->
            if (entered.incrementAndGet() == 1) {
                gateA.await()
                TagInspect(LumiTagData("Stale", PlayMode.PROXIMITY), false)
            } else {
                gateB.await()
                TagInspect(LumiTagData("Fresh", PlayMode.CONTINUOUS), false)
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

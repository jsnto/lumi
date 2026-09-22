package com.lumi.player.ui

import android.app.Application
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import com.lumi.player.LumiApp
import com.lumi.player.data.EntryEntity
import com.lumi.player.data.EntryType
import com.lumi.player.data.FolderEntity
import com.lumi.player.data.PlayMode
import com.lumi.player.player.PlayerService
import com.lumi.player.player.PlayerStateHolder
import com.lumi.player.player.PlayerUiState
import com.lumi.player.player.PlaybackStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * Manual (untagged) playback against the NFC receive path. The content play
 * buttons start the session unattached to any tag; afterwards a read of the
 * previously active tag must still start that tag's own list - never be
 * ignored while the manual song plays, and never resume the manual queue
 * (paused or not) in the tag's place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ManualPlayTagSwitchUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
        PlayerStateHolder.update { PlayerUiState() }
    }

    private fun app(): LumiApp = ApplicationProvider.getApplicationContext()

    private fun startMainActivity(): ActivityController<MainActivity> {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        compose.waitForIdle()
        return controller
    }

    private fun viewModelOf(controller: ActivityController<MainActivity>): LumiViewModel {
        val field = MainActivity::class.java.getDeclaredField("viewModel")
        field.isAccessible = true
        return field.get(controller.get()) as LumiViewModel
    }

    /**
     * A synthetic tag with [uid] (the stub SDK's [Tag] constructor throws
     * and omits the test hooks, so the real six-arg constructor is used
     * reflectively - the same shape the foreground-dispatch tests build).
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

    /** Delivers a tag the way the NFC stack's foreground dispatch would. */
    private fun sendTag(controller: ActivityController<MainActivity>, uid: String) {
        controller.newIntent(Intent(Intent.ACTION_MAIN).putExtra(NfcAdapter.EXTRA_TAG, tagWithUid(uid)))
        compose.waitForIdle()
    }

    /**
     * Consumes the next started-service intent, pumping until it arrives.
     * Null when nothing is started within the window - which is exactly
     * what the "not ignored" assertions below watch for.
     */
    private fun nextServiceIntent(timeoutMs: Long = 5_000): Intent? {
        var intent: Intent? = null
        val deadline = System.currentTimeMillis() + timeoutMs
        while (intent == null && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            intent = shadowOf(app() as Application).nextStartedService
            Thread.sleep(10)
        }
        return intent
    }

    /** Proximity mode; tag A linked to its own song, plus a manual song. */
    private fun setupDb(): EntryEntity = runBlocking {
        app().repository.setPlayMode(PlayMode.PROXIMITY)
        val tag = app().repository.addTag(UID_A, "Tag A")!!
        val entry = app().repository.addEntry(EntryType.AUDIO, "Song A", "content://a")
        app().repository.updateTagEntries(tag.id) { it + entry.id }
        app().repository.addEntry(EntryType.AUDIO, "Manual song", "content://manual")
    }

    /** A stored folder holding one manual song (the folder-play variant). */
    private fun setupFolder(): FolderEntity = runBlocking {
        val song = app().repository.addEntry(EntryType.AUDIO, "Folder manual song", "content://manual-folder")
        val folder = app().repository.rememberFolder("content://docs/tree:manual", "Manual folder")
        app().repository.markEntriesFromFolder(folder.id, setOf(song.payload))
        folder
    }

    @Test
    fun manualPlay_thenTagRead_startsTheTagList_notIgnored() {
        val manual = setupDb()
        val controller = startMainActivity()

        // Tag A's list is playing (its read started it).
        sendTag(controller, UID_A)
        val start = nextServiceIntent()
        assertNotNull("a tag read must start the player service", start)
        assertEquals(PlayerService.ACTION_START, start!!.action)
        assertEquals(UID_A, start.getStringExtra(PlayerService.EXTRA_TAG_UID))
        PlayerStateHolder.update {
            PlayerUiState().copy(status = PlaybackStatus.PLAYING, activeTagUid = UID_A)
        }

        // The content play button starts the manual song, unattached to
        // any tag.
        viewModelOf(controller).playEntry(manual)
        val manualStart = nextServiceIntent()
        assertNotNull("the content play button must start the player service", manualStart)
        assertEquals(PlayerService.ACTION_START, manualStart!!.action)
        assertNull(
            "manual playback must not attach a tag",
            manualStart.getStringExtra(PlayerService.EXTRA_TAG_UID),
        )

        // Reading the tag again must switch back to the tag's own list -
        // not be ignored while the manual song plays.
        sendTag(controller, UID_A)
        val switch = nextServiceIntent()
        assertNotNull(
            "a later tag read must start the tag's own list after a manual play, not be ignored",
            switch,
        )
        assertEquals(PlayerService.ACTION_START, switch!!.action)
        assertEquals(UID_A, switch.getStringExtra(PlayerService.EXTRA_TAG_UID))
    }

    @Test
    fun pausedManualPlay_thenTagRead_startsTheTagList_notResumesManualQueue() {
        setupDb()
        val folder = setupFolder()
        val controller = startMainActivity()

        // Tag A's list is playing (its read started it).
        sendTag(controller, UID_A)
        val start = nextServiceIntent()
        assertNotNull("a tag read must start the player service", start)
        assertEquals(PlayerService.ACTION_START, start!!.action)
        assertEquals(UID_A, start.getStringExtra(PlayerService.EXTRA_TAG_UID))
        PlayerStateHolder.update {
            PlayerUiState().copy(status = PlaybackStatus.PLAYING, activeTagUid = UID_A)
        }

        // The folder's play button starts the manual queue, and the user
        // pauses it (the session stays, unattached to any tag).
        viewModelOf(controller).playFolder(folder)
        val manualStart = nextServiceIntent()
        assertNotNull("the folder play button must start the player service", manualStart)
        assertEquals(PlayerService.ACTION_START, manualStart!!.action)
        assertNull(
            "manual playback must not attach a tag",
            manualStart.getStringExtra(PlayerService.EXTRA_TAG_UID),
        )
        PlayerStateHolder.update {
            PlayerUiState().copy(status = PlaybackStatus.PAUSED, activeTagUid = null)
        }

        // Reading the tag must start the tag's own list - "reading
        // overrides pause" applies to the tag's list, not to the manual
        // queue.
        sendTag(controller, UID_A)
        val switch = nextServiceIntent()
        assertNotNull("a tag read over a paused manual session must not be swallowed", switch)
        assertEquals(
            "reading the tag must start the tag's own list, not resume the manual queue",
            PlayerService.ACTION_START,
            switch!!.action,
        )
        assertEquals(UID_A, switch.getStringExtra(PlayerService.EXTRA_TAG_UID))
    }

    companion object {
        const val UID_A = "04A1B2C3"
    }
}

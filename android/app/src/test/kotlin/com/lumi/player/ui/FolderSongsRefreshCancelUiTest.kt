package com.lumi.player.ui

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.lumi.player.LumiApp
import com.lumi.player.data.EntryType
import com.lumi.player.data.FolderChild
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import com.lumi.player.ui.testutil.idleUntil
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import com.lumi.player.ui.testutil.app
import com.lumi.player.ui.testutil.dbAndGet

/**
 * Regression coverage for the folder page's in-flight walk capture
 * (the `folderSongsJob = scope.launch` in
 * [FolderContentManager.refreshFolderSongs]): re-selecting the folder
 * page (or a rescan's refresh) cancels the walk still in flight, so a
 * walk that settles LATE - after the newer walk has already settled -
 * can never overwrite the page's song list with its stale payload set.
 *
 * The pre-fix shape (a bare `scope.launch` with the job field never
 * assigned) made the `folderSongsJob?.cancel()` a permanent no-op: an
 * in-flight walk whose last provider listing returned after the newer
 * walk's settle passed the `selectedFolder?.id` check (same folder) and
 * overwrote the page with its stale payload set, so the folder page
 * silently showed a song list missing the songs the newer walk just
 * reported. This test drives that exact sequence against the real view
 * model and its folder collaborator: the first walk's last listing is
 * held (a latch on the fake documents-provider seam, on the walk's real
 * IO thread), the folder is re-selected so the second walk starts and
 * (with the fix) the first is cancelled, the second walk settles with
 * the newer song set, and only then the first walk's listing is
 * released with the stale set. The page must not end on the stale
 * set: before the fix it does (the stale overwrite lands); with the
 * fix the first walk's job is cancelled and its late settle carries no
 * stale payload list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FolderSongsRefreshCancelUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        markSetupCompleteForTests()
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
    }

    @Test
    fun reselectingTheFolderCancelsTheInFlightWalkSoItsStaleSettleCannotOverwriteTheNewerList() {
        // The real activity builds the real view model (and its folder
        // collaborator) the way the app does.
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        compose.waitForIdle()
        val field = MainActivity::class.java.getDeclaredField("viewModel")
        field.isAccessible = true
        val viewModel = field.get(activity) as LumiViewModel

        // The folder and its two stored songs (payloads are the walk's
        // child uris, so the page's `entriesByPayloads` resolves them).
        // Stored AFTER the view model is constructed: the startup backfill
        // walked an empty folders table at construction and will not walk
        // this folder through the seam below.
        val folder = dbAndGet { app().repository.rememberFolder(TREE_URI, "Gate Fixture") }
        dbAndGet {
            app().repository.addEntryUnique(EntryType.AUDIO, "song-a.mp3", URI_A)
            app().repository.addEntryUnique(EntryType.AUDIO, "song-b.mp3", URI_B)
        }

        // The fake documents-provider seam: the walk's ROOT listing call is
        // held on a latch (the walk runs on its real IO thread, so the
        // latch parks the walk without touching the main looper). The
        // first root listing (walk W1, the first selection) answers the
        // STALE set (song-a only); the second (walk W2, the re-selection)
        // answers the NEWER set (song-a + song-b).
        val rootCalls = AtomicInteger(0)
        val w1Blocked = AtomicBoolean(false)
        val w2Blocked = AtomicBoolean(false)
        val w1Release = CountDownLatch(1)
        val w2Release = CountDownLatch(1)
        viewModel.folderChildren = { folderUri ->
            if (folderUri.toString() == TREE_URI) {
                when (rootCalls.incrementAndGet()) {
                    1 -> {
                        w1Blocked.set(true)
                        w1Release.await()
                        listOf(FolderChild("song-a.mp3", isDirectory = false, childUri = URI_A))
                    }
                    else -> {
                        w2Blocked.set(true)
                        w2Release.await()
                        listOf(
                            FolderChild("song-a.mp3", isDirectory = false, childUri = URI_A),
                            FolderChild("song-b.mp3", isDirectory = false, childUri = URI_B),
                        )
                    }
                }
            } else emptyList()
        }

        // Walk W1 starts on the first selection and parks on its root
        // listing (the folder page's song list is in flight).
        viewModel.selectFolder(folder)
        compose.idleUntil(
            { w1Blocked.get() },
            message = "the first walk to reach its (held) root listing",
        )

        // The user re-selects the same folder (the page's re-entry / a
        // rescan's refresh): the in-flight walk is cancelled and a fresh
        // walk starts.
        viewModel.selectFolder(folder)
        compose.idleUntil(
            { w2Blocked.get() },
            message = "the second walk to reach its (held) root listing",
        )

        // The newer walk settles first, with both songs.
        w2Release.countDown()
        compose.idleUntil(
            { viewModel.folderSongs.value?.entries?.map { it.label }?.toSet() == setOf("song-a.mp3", "song-b.mp3") },
            message = "the re-selected walk to settle with the newer song set",
        )

        // Only now does the first walk's last listing return - with the
        // STALE set (song-a only). Before the fix this late settle passed
        // the same-folder check and overwrote the page with [song-a].
        w1Release.countDown()
        // Pump a real fixed window (wall clock + main looper) so the stale
        // settle's write (if any) has time to land on the main thread.
        val pumpDeadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < pumpDeadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        // What the page ends on.
        val settled = viewModel.folderSongs.value

        assertEquals(
            "the folder page must not end on the stale single-song list (the late stale settle must not overwrite the newer state); it settled as: $settled",
            false,
            settled?.readable == true && settled.entries.map { it.label } == listOf("song-a.mp3"),
        )
        // And the page's list, when readable, is the newer set - never a
        // subset of it.
        if (settled?.readable == true) {
            assertEquals(
                "a readable page holds the newer walk's song set; it settled as: $settled",
                setOf("song-a.mp3", "song-b.mp3"),
                settled.entries.map { it.label }.toSet(),
            )
        }
    }

    private companion object {
        /** A documents tree uri (folder selected with ":tree" access). */
        const val TREE_URI = "content://docs/document/audio%3A1:tree"
        const val URI_A = "content://docs/tree/fixture/document/audio:100"
        const val URI_B = "content://docs/tree/fixture/document/audio:101"
    }
}

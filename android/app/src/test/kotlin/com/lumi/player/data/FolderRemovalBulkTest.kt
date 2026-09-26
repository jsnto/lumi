package com.lumi.player.data

import com.lumi.player.LumiApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Bulk coverage for folder removal's payload match: a folder with more
 * imported songs than a single IN-clause query may bind (SQLite before 3.32,
 * Android <= 11, caps bound variables at 999) must still count and delete
 * every song, plus the folder row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LumiApp::class)
class FolderRemovalBulkTest {

    private fun repository(): LumiRepository =
        (RuntimeEnvironment.getApplication() as LumiApp).repository

    @Test
    fun aFolderWithMoreThanNineHundredImportedSongsIsCountedAndRemovedInFull() = runBlocking {
        val repo = repository()
        val payloads = (0 until 1000).map { "content://docs/document/song%3A$it" }.toSet()
        payloads.forEach { payload ->
            repo.addEntry(EntryType.AUDIO, payload, payload)
        }
        val folder = repo.rememberFolder("content://docs/document/audio%3A1:tree", "Big folder")

        assertEquals(1000, repo.countEntriesByPayloads(payloads))

        // The folder row and every imported song leave in the same
        // transaction, across the chunk boundary.
        assertEquals(1000, repo.removeFolder(folder, payloads))
        assertEquals(0, repo.observeEntries().first().size)
        assertEquals(0, repo.observeFolders().first().size)
    }
}

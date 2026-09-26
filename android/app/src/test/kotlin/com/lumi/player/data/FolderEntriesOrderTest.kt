package com.lumi.player.data

import com.lumi.player.LumiApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The folder detail page's song list ([LumiRepository.entriesByPayloads]) is
 * matched in 900-row IN-clauses (the SQLite bound-variable cap), and each
 * clause answers its own rows in content-list order. A folder whose walked
 * payload set spans more than one clause must still come back in the
 * content list's order: the concatenated clauses are re-sorted globally, so
 * a walk that lists the newer songs first and the older songs after the
 * 900-row boundary does not surface a song list with the newer batch first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LumiApp::class)
class FolderEntriesOrderTest {

    private fun app(): LumiApp = RuntimeEnvironment.getApplication() as LumiApp

    @Test
    fun entriesByPayloadsKeepsTheContentListOrderAcrossTheClauseBoundary() = runBlocking {
        val dao = app().database.dao()
        // 100 songs stored earlier (older createdAt), then 900 more.
        for (i in 0 until 100) {
            dao.insertEntry(entry("old", i, createdAt = 1_000L + i))
        }
        for (i in 0 until 900) {
            dao.insertEntry(entry("new", i, createdAt = 2_000L + i))
        }

        // The folder's walk lists the 900 newer songs first and the 100
        // older after the 900-row clause boundary.
        val walkOrder =
            (0 until 900).map { "content://docs/document/new%3A$it" } +
                (0 until 100).map { "content://docs/document/old%3A$it" }

        val result = app().repository.entriesByPayloads(walkOrder.toSet())

        // The content list's order is global (createdAt ASC, then id ASC):
        // the 100 older songs lead, no matter where the clause boundary
        // fell.
        val expected =
            (0 until 100).map { "content://docs/document/old%3A$it" } +
                (0 until 900).map { "content://docs/document/new%3A$it" }
        assertEquals(expected, result.map { it.payload })
    }

    private fun entry(kind: String, index: Int, createdAt: Long): EntryEntity =
        EntryEntity(
            type = EntryType.AUDIO.storageValue,
            label = "$kind $index",
            payload = "content://docs/document/$kind%3A$index",
            createdAt = createdAt,
        )
}

package com.lumi.player.data

import com.lumi.player.LumiApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Tests for [LumiRepository.renameTag]: the stored record of a registered
 * tag follows a name written back to the tag, and renaming an unknown UID
 * is a no-op.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LumiApp::class)
class TagRenameTest {

    private fun repository(): LumiRepository =
        (RuntimeEnvironment.getApplication() as LumiApp).repository

    @Test
    fun renameTag_updatesTheStoredName() = runBlocking {
        val repo = repository()
        repo.addTag("UID-A", "First")
        repo.renameTag("UID-A", "Second")
        assertEquals("Second", repo.tagByUid("UID-A")?.name)
    }

    @Test
    fun renameTag_unknownUid_isANoOp() = runBlocking {
        val repo = repository()
        repo.renameTag("UNKNOWN", "Whatever")
        assertNull(repo.tagByUid("UNKNOWN"))
    }
}

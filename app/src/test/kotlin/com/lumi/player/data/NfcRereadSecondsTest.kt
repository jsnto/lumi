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
 * Tests for the persisted "tag re-read wait" setting: how many seconds of
 * absence of reports on the active tag (proximity mode) count as removal.
 * Unset and invalid stored values must fall back to the default (15 s) so
 * the absence-timeout fallback never gets a zero/negative window.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LumiApp::class)
class NfcRereadSecondsTest {

    private fun app(): LumiApp = RuntimeEnvironment.getApplication() as LumiApp

    @Test
    fun unset_defaultIsFifteenSeconds() = runBlocking {
        val repo = app().repository
        assertEquals(
            LumiRepository.NFC_REREAD_SECONDS_DEFAULT,
            repo.observeNfcRereadSeconds().first(),
        )
        assertEquals(15L, repo.observeNfcRereadSeconds().first())
    }

    @Test
    fun customValue_roundTrips() = runBlocking {
        val repo = app().repository
        repo.setNfcRereadSeconds(7L)
        assertEquals(7L, repo.observeNfcRereadSeconds().first())
    }

    @Test
    fun outOfRangeValue_isClamped() = runBlocking {
        val repo = app().repository
        repo.setNfcRereadSeconds(0L)
        assertEquals(1L, repo.observeNfcRereadSeconds().first())
        repo.setNfcRereadSeconds(999L)
        assertEquals(120L, repo.observeNfcRereadSeconds().first())
    }

    @Test
    fun invalidStoredValue_fallsBackToDefault() = runBlocking {
        val repo = app().repository
        // A raw, unparsable stored value (e.g. written by an older build or
        // by hand) must not break the fallback.
        app().database.dao().upsertSetting(
            SettingsEntity(SettingsEntity.NFC_REREAD_SECONDS, "not-a-number"),
        )
        assertEquals(
            LumiRepository.NFC_REREAD_SECONDS_DEFAULT,
            repo.observeNfcRereadSeconds().first(),
        )
    }
}

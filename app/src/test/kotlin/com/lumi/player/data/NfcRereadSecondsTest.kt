package com.lumi.player.data

import com.lumi.player.LumiApp
import com.lumi.player.nfc.GenericPresencePoller
import com.lumi.player.nfc.MifarePresencePoller
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun minimumBoundaryValues_roundTrip() = runBlocking {
        // The tightest windows the user can pick (1 s, 5 s) must survive a
        // round trip unchanged: the presence poll relies on exactly these
        // values as its absence window.
        val repo = app().repository
        for (seconds in listOf(1L, 5L)) {
            repo.setNfcRereadSeconds(seconds)
            assertEquals(seconds, repo.observeNfcRereadSeconds().first())
        }
    }

    @Test
    fun minimumWindow_staysWiderThanThePresencePollRate() {
        // Both fast paths (the Mifare page-0 reads and the generic
        // probes) refresh the presence record at a rate several
        // times faster than the tightest allowed absence window (1 s), so a
        // held-still tag keeps its record fresh long before the window can
        // trip a false pause at the 1 s / 5 s boundaries.
        val minWindowMs = LumiRepository.NFC_REREAD_SECONDS_RANGE.first * 1_000L
        assertTrue(
            "multiple presence checks must fit inside the minimum window",
            minWindowMs >= 2 * MifarePresencePoller.READ_INTERVAL_MS &&
                minWindowMs >= 2 * GenericPresencePoller.READ_INTERVAL_MS,
        )
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

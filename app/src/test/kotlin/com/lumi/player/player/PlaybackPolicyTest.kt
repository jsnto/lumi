package com.lumi.player.player

import com.lumi.player.data.PlayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPolicyTest {

    private val uidA = "04:AA:BB:CC"
    private val uidB = "04:DD:EE:FF"

    // ---- continuous mode ----

    @Test
    fun continuous_firstTapStarts() {
        val policy = PlaybackPolicy(PlayMode.CONTINUOUS)
        assertEquals(NfcAction.StartPlayback, policy.onTagDetected(uidA))
        assertEquals(uidA, policy.activeTagUid)
    }

    @Test
    fun continuous_reTapOfActiveTagIsIgnored() {
        val policy = PlaybackPolicy(PlayMode.CONTINUOUS)
        policy.onTagDetected(uidA)
        assertEquals(NfcAction.Ignore, policy.onTagDetected(uidA))
        assertTrue(policy.active)
    }

    @Test
    fun continuous_differentTagSwitches() {
        val policy = PlaybackPolicy(PlayMode.CONTINUOUS)
        policy.onTagDetected(uidA)
        assertEquals(NfcAction.StartPlayback, policy.onTagDetected(uidB))
        assertEquals(uidB, policy.activeTagUid)
    }

    @Test
    fun continuous_tagLossIsIgnoredAndKeepsPlaying() {
        val policy = PlaybackPolicy(PlayMode.CONTINUOUS)
        policy.onTagDetected(uidA)
        assertEquals(NfcAction.Ignore, policy.onTagLost(uidA))
        assertTrue(policy.active)
        // The tag stays active: re-reading it is a no-op, or a resume when
        // the user had paused; a different tag still switches.
        assertEquals(NfcAction.Ignore, policy.onTagDetected(uidA))
        assertEquals(NfcAction.Resume, policy.onTagDetected(uidA, paused = true))
        assertEquals(NfcAction.StartPlayback, policy.onTagDetected(uidB))
    }

    // ---- proximity mode ----

    @Test
    fun proximity_detectStartsAndLossStops() {
        val policy = PlaybackPolicy(PlayMode.PROXIMITY)
        assertEquals(NfcAction.StartPlayback, policy.onTagDetected(uidA))
        assertEquals(NfcAction.StopPlayback, policy.onTagLost(uidA))
        assertFalse(policy.active)
    }

    @Test
    fun proximity_reDetectAfterLossStartsAgain() {
        val policy = PlaybackPolicy(PlayMode.PROXIMITY)
        policy.onTagDetected(uidA)
        policy.onTagLost(uidA)
        assertEquals(NfcAction.StartPlayback, policy.onTagDetected(uidA))
    }

    @Test
    fun proximity_lossOfInactiveTagIsIgnored() {
        val policy = PlaybackPolicy(PlayMode.PROXIMITY)
        policy.onTagDetected(uidA)
        assertEquals(NfcAction.Ignore, policy.onTagLost(uidB))
        assertEquals(uidA, policy.activeTagUid)
    }

    @Test
    fun proximity_reDetectWhileActiveIsIgnored() {
        val policy = PlaybackPolicy(PlayMode.PROXIMITY)
        policy.onTagDetected(uidA)
        assertEquals(NfcAction.Ignore, policy.onTagDetected(uidA))
        assertTrue(policy.active)
    }

    // ---- reading overrides play/pause ----

    @Test
    fun reReadOfActiveTagWhilePausedResumesInBothModes() {
        for (mode in PlayMode.entries) {
            val policy = PlaybackPolicy(mode)
            policy.onTagDetected(uidA)
            assertEquals(NfcAction.Ignore, policy.onTagDetected(uidA))
            assertEquals(NfcAction.Resume, policy.onTagDetected(uidA, paused = true))
            // The list is still the same tag's; a different tag still switches.
            assertEquals(NfcAction.StartPlayback, policy.onTagDetected(uidB, paused = true))
            assertEquals(uidB, policy.activeTagUid)
        }
    }

    // ---- shared ----

    @Test
    fun playbackStoppedClearsActiveTag() {
        val policy = PlaybackPolicy(PlayMode.CONTINUOUS)
        policy.onTagDetected(uidA)
        policy.onPlaybackStopped()
        assertFalse(policy.active)
        // The same tag can start again after a manual stop.
        assertEquals(NfcAction.StartPlayback, policy.onTagDetected(uidA))
    }

    @Test
    fun seededActiveTagIsTreatedAsPlaying() {
        val policy = PlaybackPolicy(PlayMode.CONTINUOUS)
        policy.seedActiveTagUid(uidA)
        assertEquals(uidA, policy.activeTagUid)
        // A re-read of the carried tag (e.g. after a recreated view model)
        // must not restart playback.
        assertEquals(NfcAction.Ignore, policy.onTagDetected(uidA))
        // Continuous mode ignores tag loss: the music keeps playing.
        assertEquals(NfcAction.Ignore, policy.onTagLost(uidA))
        assertTrue(policy.active)
    }

    @Test
    fun seededActiveTagLossStopsInProximity() {
        val policy = PlaybackPolicy(PlayMode.PROXIMITY)
        policy.seedActiveTagUid(uidA)
        assertEquals(NfcAction.StopPlayback, policy.onTagLost(uidA))
        assertFalse(policy.active)
        assertEquals(NfcAction.StartPlayback, policy.onTagDetected(uidA))
    }
}

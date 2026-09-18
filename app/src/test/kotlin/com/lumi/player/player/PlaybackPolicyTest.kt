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
        // A re-tap of the carried tag (e.g. after a recreated view model)
        // must not restart playback.
        assertEquals(NfcAction.Ignore, policy.onTagDetected(uidA))
        assertEquals(NfcAction.StopPlayback, policy.onTagLost(uidA))
        assertFalse(policy.active)
        assertEquals(NfcAction.StartPlayback, policy.onTagDetected(uidA))
    }
}

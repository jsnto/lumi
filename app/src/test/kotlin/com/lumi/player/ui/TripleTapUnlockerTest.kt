package com.lumi.player.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the soft-lock tap counting: [TripleTapUnlocker] unlocks on
 * [TripleTapUnlocker.REQUIRED_TAPS] taps, only while the taps stay inside the
 * time window; a tap after the window starts a fresh count.
 */
class TripleTapUnlockerTest {

    private val window = 2_000L

    @Test
    fun twoTaps_doNotUnlock() {
        val unlocker = TripleTapUnlocker(window)
        assertFalse(unlocker.recordTap(100))
        assertFalse(unlocker.recordTap(500))
        assertEquals(2, unlocker.count)
    }

    @Test
    fun thirdTap_withinWindow_unlocks() {
        val unlocker = TripleTapUnlocker(window)
        unlocker.recordTap(100)
        unlocker.recordTap(1_000)
        assertTrue("the third in-window tap must complete the unlock", unlocker.recordTap(1_900))
    }

    @Test
    fun tap_afterWindow_resetsCount() {
        val unlocker = TripleTapUnlocker(window)
        unlocker.recordTap(0)
        unlocker.recordTap(500)
        // A stray tap long after the window must not unlock...
        assertFalse("a tap after the window must not complete the unlock", unlocker.recordTap(10_000))
        // ...and it starts a fresh count: two more in-window taps do.
        unlocker.recordTap(10_100)
        assertTrue(unlocker.recordTap(10_200))
    }

    @Test
    fun tap_exactlyAtWindowBoundary_stillCounts() {
        val unlocker = TripleTapUnlocker(window)
        unlocker.recordTap(0)
        // Exactly windowMs after the first tap is still "in the window".
        unlocker.recordTap(window)
        assertTrue(unlocker.recordTap(window + 1))
    }

    @Test
    fun afterUnlock_counterStartsFresh() {
        val unlocker = TripleTapUnlocker(window)
        unlocker.recordTap(0)
        unlocker.recordTap(100)
        assertTrue(unlocker.recordTap(200))
        assertEquals(0, unlocker.count)
        // Later taps count from zero again.
        assertFalse(unlocker.recordTap(300))
        assertFalse(unlocker.recordTap(400))
        assertTrue(unlocker.recordTap(500))
    }

    @Test
    fun reset_clearsPartialCount() {
        val unlocker = TripleTapUnlocker(window)
        unlocker.recordTap(0)
        unlocker.recordTap(100)
        unlocker.reset()
        assertEquals(0, unlocker.count)
        assertFalse(unlocker.recordTap(200))
        assertFalse(unlocker.recordTap(300))
        assertTrue(unlocker.recordTap(400))
    }

    @Test
    fun defaults_matchSpec() {
        assertEquals(3, TripleTapUnlocker.REQUIRED_TAPS)
        assertEquals(2_000L, TripleTapUnlocker.UNLOCK_TAP_WINDOW_MS)
    }
}

package com.lumi.player.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the soft-lock tap counting: [TripleTapUnlocker] unlocks on
 * [TripleTapUnlocker.REQUIRED_TAPS] fast taps, only while each tap stays
 * inside the time window of the previous one; a slow tap (gap longer than
 * the window) starts a fresh count.
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
        // The default window is the fast-triple-tap window: quick taps
        // ~100-400ms apart, but never a slow tap (the spec allows 400-500ms;
        // 450ms is the picked constant).
        assertEquals(450L, TripleTapUnlocker.UNLOCK_TAP_WINDOW_MS)
    }

    @Test
    fun defaultWindow_fastTripleTap_unlocks() {
        // A real fast triple-tap: taps ~150ms apart with the default window.
        val unlocker = TripleTapUnlocker()
        unlocker.recordTap(0)
        unlocker.recordTap(150)
        assertTrue(
            "three quick taps (each within the default 450ms window) must " +
                "unlock with the default constructor",
            unlocker.recordTap(280),
        )
    }

    @Test
    fun defaultWindow_slowTaps_doNotUnlock() {
        // Deliberate, far-apart taps (600ms gaps > the 450ms window) must
        // never accumulate into an unlock, no matter how many arrive.
        val unlocker = TripleTapUnlocker()
        assertFalse(unlocker.recordTap(0))
        assertFalse("a 600ms gap exceeds the 450ms window and resets", unlocker.recordTap(600))
        assertFalse(unlocker.recordTap(1_200))
        assertFalse(unlocker.recordTap(1_800))
    }

    @Test
    fun defaultWindow_boundary_gapsBehaveCorrectly() {
        // Exactly at the window boundary the tap still counts; one
        // millisecond past it resets the count.
        val atBoundary = TripleTapUnlocker()
        atBoundary.recordTap(0)
        atBoundary.recordTap(450)
        assertTrue("a tap exactly 450ms after the previous one still counts", atBoundary.recordTap(900))

        val pastBoundary = TripleTapUnlocker()
        pastBoundary.recordTap(0)
        pastBoundary.recordTap(451)
        assertFalse("a tap 451ms after the previous one resets the count", pastBoundary.recordTap(902))
        assertEquals(1, pastBoundary.count)
    }
}

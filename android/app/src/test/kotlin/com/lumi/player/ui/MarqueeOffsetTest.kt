package com.lumi.player.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The marquee's offset math (the pure half of the long-title auto-scroll):
 * the title holds at its start for the hold fraction of the cycle, then
 * eases across by the overflow distance (ease-in-out cubic), ending with
 * its tail filling the box (offset -distance) before the cycle wraps back
 * to the start. A title that does not overflow holds at 0 the whole time.
 */
class MarqueeOffsetTest {

    @Test
    fun theHoldStartsAtZeroAndRunsForTheHoldFraction() {
        // The whole hold window sits at the start (the first screenful is
        // readable at rest).
        assertEquals(0f, marqueeOffsetPx(0f, 100f, 0.34f), 0.0f)
        assertEquals(0f, marqueeOffsetPx(0.17f, 100f, 0.34f), 0.0f)
        assertEquals(0f, marqueeOffsetPx(0.34f, 100f, 0.34f), 0.0f)
    }

    @Test
    fun theSweepEndsAtTheFullDistance() {
        // The sweep ends exactly with the tail filling the box: the
        // offset is the full overflow distance (the title has scrolled
        // its (width - box) out of the box).
        assertEquals(-100f, marqueeOffsetPx(1f, 100f, 0.34f), 0.001f)
        assertEquals(-1234f, marqueeOffsetPx(1f, 1234f, 0.34f), 0.1f)
    }

    @Test
    fun theSweepIsMonotonicLeftwardAndEased() {
        // Every offset during the sweep is between the start (0) and the
        // end (-distance), strictly leftward of the previous sample.
        var previous = 0f
        var moved = false
        var phase = 0.34f
        while (phase <= 1f) {
            val offset = marqueeOffsetPx(phase, 100f, 0.34f)
            assertTrue(offset <= 0f)
            assertTrue(offset >= -100f)
            assertTrue(offset <= previous)
            if (offset < previous) moved = true
            previous = offset
            phase += 0.01f
        }
        assertTrue(moved)
        // Ease-in-out: the sweep's midpoint sits at half the distance
        // (the ease is symmetric), a step just after the hold is close to
        // the start (it accelerates out).
        assertEquals(-50f, marqueeOffsetPx(0.5f + 0.34f / 2f, 100f, 0.34f), 0.001f)
        assertTrue(marqueeOffsetPx(0.34f + 0.01f, 100f, 0.34f) > -5f)
    }

    @Test
    fun aFittingTitleNeverMoves() {
        // Zero or negative overflow (the title fits the box): the whole
        // cycle holds at the start - nothing animates.
        for (phase in listOf(0f, 0.1f, 0.34f, 0.5f, 0.9f, 1f)) {
            assertEquals(0f, marqueeOffsetPx(phase, 0f, 0.34f), 0.0f)
            assertEquals(0f, marqueeOffsetPx(phase, -10f, 0.34f), 0.0f)
        }
    }
}

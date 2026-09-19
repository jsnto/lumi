package com.lumi.player.ui

/**
 * Tap-counting state machine for the soft lock: [REQUIRED_TAPS] taps in a row
 * unlock the screen.
 *
 * A tap only extends the count while its gap from the previous tap is within
 * [windowMs]; a tap that arrives after the window starts a fresh count (a
 * stray tap long after the last one never unlocks). The clock is caller-
 * supplied ([now] in milliseconds), so the behavior is fully deterministic
 * and unit-testable.
 */
class TripleTapUnlocker(private val windowMs: Long = UNLOCK_TAP_WINDOW_MS) {

    /** Taps accumulated so far, [REQUIRED_TAPS] short of nothing - resets to 0. */
    var count: Int = 0
        private set

    private var lastTapAt: Long = 0L

    /**
     * Records a tap at [now]. Returns true when this tap completes the
     * unlock; the counter resets afterwards so later taps start a fresh
     * count.
     */
    fun recordTap(now: Long): Boolean {
        if (count > 0 && now - lastTapAt > windowMs) {
            count = 0
        }
        count += 1
        lastTapAt = now
        if (count >= REQUIRED_TAPS) {
            count = 0
            return true
        }
        return false
    }

    /** Clears any partial count (e.g. when the lock re-engages). */
    fun reset() {
        count = 0
        lastTapAt = 0L
    }

    companion object {
        const val REQUIRED_TAPS = 3
        const val UNLOCK_TAP_WINDOW_MS = 2_000L
    }
}

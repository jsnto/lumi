package com.lumi.player.data

/**
 * The auto-lock idle timer's decision logic, pure: fed with playback
 * transitions and wall-clock timestamps (never with a clock of its own), it
 * decides exactly when the configured idle delay has elapsed with no active
 * playback.
 *
 * An idle stretch starts when playback is (or becomes) inactive - stopped or
 * paused both count as idle - and it resets on any active playback. While a
 * stretch is running, [tick] returns true exactly once: at the moment the
 * configured delay has elapsed, when the screen should be locked. It never
 * re-fires for the same stretch (a fired stretch stays quiet until playback
 * goes active again and the next idle stretch is measured), and a delay
 * change mid-stretch is honoured from the stretch's own start - a delay
 * shortened below the already-idle span fires at the next tick, a lengthened
 * one waits.
 */
class AutoLockTimer {

    private var delayMs: Long = 0L
    /** The idle stretch's start, or null while playback is active (or off). */
    private var idleSinceMs: Long? = null
    /** The current stretch has already fired. */
    private var firedThisStretch: Boolean = false

    /** The configured idle delay; 0 (off) disarms the timer. */
    fun setDelay(delayMs: Long, nowMs: Long) {
        this.delayMs = delayMs
        if (delayMs <= 0L) {
            idleSinceMs = null
            firedThisStretch = false
        } else if (idleSinceMs == null) {
            // The delay was turned on (or lengthened into being) while the
            // app is idle: measure the stretch from now.
            idleSinceMs = nowMs
        }
    }

    /** Feeds a playback status change; arms a new stretch or resets it. */
    fun onPlaybackStatus(isPlaying: Boolean, nowMs: Long) {
        if (isPlaying) {
            idleSinceMs = null
            firedThisStretch = false
        } else if (idleSinceMs == null) {
            idleSinceMs = nowMs
        }
    }

    /**
     * One idle check (the driver calls this periodically). Returns true
     * exactly when the idle delay has now elapsed and the screen should be
     * locked.
     */
    fun tick(nowMs: Long): Boolean {
        val start = idleSinceMs ?: return false
        if (delayMs <= 0L || firedThisStretch) return false
        if (nowMs - start >= delayMs) {
            firedThisStretch = true
            return true
        }
        return false
    }

    /** The configured idle delay in milliseconds (0 while off). */
    fun delayMs(): Long = delayMs
}

package com.lumi.player.player

/**
 * What a SCREEN_OFF report resolves to (the screen-off pause's decision).
 */
enum class ScreenOffVerdict {
    /** The screen is really off: pause through the service's own ACTION_PAUSE. */
    Pause,

    /** The isInteractive() reading was interactive: one delayed recheck decides. */
    Recheck,

    /** Still interactive - a spoofed broadcast or the screen back on: touch nothing. */
    Ignore,
}

/**
 * Pure decision logic for the screen-off pause, decoupled from Android so it
 * can be unit tested.
 *
 * The platform exposes no pre-lock signal; the deterministic event is the
 * screen going dark (ACTION_SCREEN_OFF), which on a normally locked phone is
 * the lock sequence starting. Two trust problems around that broadcast:
 *
 *  - It is NOT a protected broadcast, so any app can emit a spoof.
 *  - Even for a genuine one, PowerManager.isInteractive() can still report
 *    true for a short race right after the broadcast.
 *
 * So the broadcast is never believed on its own: a non-interactive reading
 * at broadcast time confirms the screen off and pauses at once; a
 * still-interactive one gets ONE delayed recheck after the race window, and
 * only a non-interactive recheck pauses (an interactive recheck is a spoof
 * or the screen back on, and the report is ignored). There is deliberately
 * no resume on screen-on: the user resumes explicitly.
 */
object ScreenOffPolicy {

    /** The delay before the single recheck, in ms (the isInteractive race window). */
    const val RECHECK_DELAY_MS: Long = 300L

    /**
     * The PowerManager.isInteractive() reading at SCREEN_OFF broadcast time.
     * A confirmed-off screen pauses at once; an interactive one is decided
     * by the recheck ([onRecheck]) after the race window.
     */
    fun onScreenOff(isInteractive: Boolean): ScreenOffVerdict =
        if (isInteractive) ScreenOffVerdict.Recheck else ScreenOffVerdict.Pause

    /**
     * The delayed recheck's isInteractive() reading (only reached when the
     * broadcast-time reading was interactive). Only a non-interactive
     * recheck confirms the screen off; an interactive one (a spoofed
     * SCREEN_OFF with the screen genuinely on, or the screen back on)
     * ignores the report.
     */
    fun onRecheck(isInteractive: Boolean): ScreenOffVerdict =
        if (isInteractive) ScreenOffVerdict.Ignore else ScreenOffVerdict.Pause
}

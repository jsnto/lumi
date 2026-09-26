package com.lumi.player.data

/**
 * The auto-lock idle delay (the System setting's "Auto-lock after idle"):
 * how many minutes of idle - no song actively playing (stopped or paused) -
 * count before the phone's screen is locked, so a phone left with the app
 * open does not drain its battery doing nothing. [OFF] is the default and
 * the setting's off state; the rest is the minute ladder the picker offers.
 * The choice persists in the app's key/value settings table as the minute
 * count's text (off is "0").
 */
enum class AutoLockDelay(val minutes: Int) {
    OFF(0),
    FIVE_MINUTES(5),
    TEN_MINUTES(10),
    FIFTEEN_MINUTES(15),
    THIRTY_MINUTES(30),
}

/**
 * Reads a stored auto-lock delay. Unset or invalid values - and a minute
 * count the ladder does not carry - fall back to [AutoLockDelay.OFF]: the
 * screen is never locked automatically unless the user picked a ladder
 * option.
 */
fun autoLockDelayFromStored(value: String?): AutoLockDelay =
    AutoLockDelay.entries.firstOrNull { it.minutes.toString() == value?.trim() }
        ?: AutoLockDelay.OFF

/** The settings-table value [this] delay stores under. */
fun AutoLockDelay.storedValue(): String = minutes.toString()

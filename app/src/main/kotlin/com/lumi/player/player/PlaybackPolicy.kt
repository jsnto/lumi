package com.lumi.player.player

import com.lumi.player.data.PlayMode

/** What the app should do in response to an NFC event. */
sealed interface NfcAction {
    /** Start (or switch to) playback of the tag's content list. */
    data object StartPlayback : NfcAction

    /** Resume the paused list that is already playing. */
    data object Resume : NfcAction

    /** Do nothing (e.g. re-read of the tag already playing). */
    data object Ignore : NfcAction

    /** Pause playback (proximity mode: the tag left the reader range). */
    data object PausePlayback : NfcAction

}

/**
 * Pure decision logic for the two play modes, decoupled from Android so it
 * can be unit tested.
 *
 * Both modes keep NFC always listening: a registered tag read starts or
 * switches playback, and a re-read of the tag already playing resumes it
 * when it is paused (NFC reading overrides play/pause).
 *
 * CONTINUOUS: playback keeps going after the tag is removed; the tag stays
 * "active" until a different tag is read. Tag loss is ignored.
 * PROXIMITY: a detected tag starts playback and stays active while it is
 * detected; losing the active tag PAUSES playback and keeps the tag active,
 * so a re-read of the same tag resumes the paused list instead of restarting
 * it. The tag is cleared only when playback itself stops.
 */
class PlaybackPolicy(private val mode: PlayMode) {

    /** UID of the tag whose list is currently playing, if any. */
    var activeTagUid: String? = null
        private set

    /**
     * A registered tag was read. [paused] reports whether the active tag's
     * list is currently paused so a re-read of it can resume it (reading
     * overrides the user's pause).
     */
    fun onTagDetected(uid: String, paused: Boolean = false): NfcAction =
        if (uid == activeTagUid) {
            if (paused) NfcAction.Resume else NfcAction.Ignore
        } else {
            activeTagUid = uid
            NfcAction.StartPlayback
        }

    /**
     * The active tag left the reader range. In proximity mode this pauses
     * playback; the tag STAYS active so a re-read of it resumes (the
     * "reading overrides play/pause" behavior) rather than restarting.
     */
    fun onTagLost(uid: String): NfcAction =
        if (mode == PlayMode.PROXIMITY && uid == activeTagUid) {
            NfcAction.PausePlayback
        } else {
            NfcAction.Ignore
        }

    fun seedActiveTagUid(uid: String?) {
        activeTagUid = uid
    }

    /** The service stopped on its own (user pressed stop, app closed, ...). */
    fun onPlaybackStopped() {
        activeTagUid = null
    }

    val active: Boolean
        get() = activeTagUid != null
}

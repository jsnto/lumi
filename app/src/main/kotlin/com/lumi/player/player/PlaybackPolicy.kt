package com.lumi.player.player

import com.lumi.player.data.PlayMode

/** What the app should do in response to an NFC event. */
sealed interface NfcAction {
    /** Start (or switch to) playback of the tag's content list. */
    data object StartPlayback : NfcAction

    /** Do nothing (e.g. re-tap of the tag already playing). */
    data object Ignore : NfcAction

    /** Stop playback (proximity mode: the tag left the reader range). */
    data object StopPlayback : NfcAction
}

/**
 * Pure decision logic for the two play modes, decoupled from Android so it
 * can be unit tested.
 *
 * CONTINUOUS: a tag tap starts/switches playback; the tag is "active" until a
 * different tag is tapped. Re-tapping the active tag is ignored.
 * PROXIMITY: a detected tag starts playback and stays active only while it is
 * detected; losing the active tag stops playback.
 */
class PlaybackPolicy(private val mode: PlayMode) {

    /** UID of the tag whose list is currently playing, if any. */
    var activeTagUid: String? = null
        private set

    fun onTagDetected(uid: String): NfcAction =
        if (uid == activeTagUid) {
            NfcAction.Ignore
        } else {
            activeTagUid = uid
            NfcAction.StartPlayback
        }

    fun onTagLost(uid: String): NfcAction =
        if (uid == activeTagUid) {
            activeTagUid = null
            NfcAction.StopPlayback
        } else {
            NfcAction.Ignore
        }

    /** The service stopped on its own (user pressed stop, app closed, ...). */
    fun onPlaybackStopped() {
        activeTagUid = null
    }

    val active: Boolean
        get() = activeTagUid != null
}

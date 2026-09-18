package com.lumi.player.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Coarse playback status shown on the front screen. */
enum class PlaybackStatus { IDLE, PLAYING, PAUSED }

/**
 * UI-facing state for what is currently playing. The [PlayerService] updates
 * it; the front screen observes it.
 *
 * [currentType] is the [com.lumi.player.data.EntryType.storageValue] of the
 * current entry so the screen can represent audio/video/url entries alike.
 */
data class PlayerUiState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val currentLabel: String? = null,
    val currentType: String? = null,
    val queueSize: Int = 0,
    val activeTagUid: String? = null,
) {
    val isPlaying: Boolean
        get() = status == PlaybackStatus.PLAYING
}

/**
 * Process-wide holder so the service and the UI share state without a direct
 * reference. Lives for the process lifetime (in-memory only; the service is
 * the source of truth).
 */
object PlayerStateHolder {

    private val _state = MutableStateFlow(PlayerUiState())

    val state: StateFlow<PlayerUiState> = _state

    fun update(transform: (PlayerUiState) -> PlayerUiState) {
        _state.update(transform)
    }
}

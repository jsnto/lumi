package com.lumi.player.core

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * A serializable snapshot of one content entry, passed to the
 * [com.lumi.player.player.PlayerService] so it does not need database access.
 * [type] is the storage value of [com.lumi.player.data.EntryType].
 */
@Parcelize
data class EntrySpec(
    val type: String,
    val label: String,
    val payload: String,
) : Parcelable

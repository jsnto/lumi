package com.lumi.player.player

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * A serializable snapshot of one content entry, passed to the [PlayerService]
 * so it does not need database access. [type] is the storage value of
 * [com.lumi.player.data.EntryType].
 */
@Parcelize
data class EntrySpec(
    val type: String,
    val label: String,
    val payload: String,
) : Parcelable

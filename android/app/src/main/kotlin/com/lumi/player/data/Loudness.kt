package com.lumi.player.data

import androidx.room.ColumnInfo
import com.lumi.player.core.gainFor

/**
 * The loudness rows' service-facing shapes and the audio level setting's
 * gain convenience. The pure loudness math (the K-weighted streaming
 * meter, [com.lumi.player.core.LoudnessResult], the measure-to-target gain,
 * the soft clip and its knee) lives in the [com.lumi.player.core] package
 * (shared with the playback processors without a data<->player cycle).
 */

/**
 * A measured loudness sample as the player service consumes it (the
 * per-payload snapshot kept by [com.lumi.player.data.LoudnessStore]).
 * [lufs] / [truePeakDbTp] follow [com.lumi.player.core.LoudnessResult]'s
 * nulls.
 */
data class LoudnessSample(
    val lufs: Double?,
    val truePeakDbTp: Double?,
    val fingerprint: String,
    val measuredAt: Long,
)

/**
 * A loudness row joined to its content row's payload: the DAO mapping
 * behind [LoudnessStore]'s service snapshot.
 */
data class EntryLoudnessPayload(
    @ColumnInfo(name = "entry_id") val entryId: Long,
    @ColumnInfo(name = "lufs") val lufs: Double?,
    @ColumnInfo(name = "true_peak_db_tp") val truePeakDbTp: Double?,
    @ColumnInfo(name = "fingerprint") val fingerprint: String,
    @ColumnInfo(name = "measured_at") val measuredAt: Long,
    val payload: String,
)

/**
 * The audio level's measurement status for the settings pane's status row:
 * [measured] of [total] audio entries carry a stored measurement.
 */
data class LoudnessStatus(
    val measured: Int,
    val total: Int,
) {
    /** The row's copy: "N of M songs measured" ("1 of 1 song" when one). */
    val text: String
        get() {
            val song = if (total == 1) "song" else "songs"
            return "$measured of $total $song measured"
        }
}

/**
 * The audio level setting's convenience: no gain while the mode is off, or
 * while the track is unmeasured or carries no loudness; otherwise the
 * measure-to-target gain capped by the track's peak headroom (the pure
 * [com.lumi.player.core.gainFor]).
 */
fun gainFor(
    mode: AudioLevelMode,
    targetLufs: Double,
    sample: LoudnessSample?,
): Double =
    if (mode == AudioLevelMode.OFF || sample == null || sample.lufs == null) {
        0.0
    } else {
        gainFor(sample.lufs, sample.truePeakDbTp, targetLufs)
    }

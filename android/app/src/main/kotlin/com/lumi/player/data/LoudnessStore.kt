package com.lumi.player.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The process-wide loudness cache (the [TagPositionStore]-pattern store,
 * extended with the app-singleton snapshot the [com.lumi.player.player.PlayerService]
 * reads): the per-payload loudness rows (the on-device R128 measurements),
 * plus a live in-memory snapshot keyed by the content payload.
 *
 * The service resolves the current entry's gain from [snapshot] - a volatile
 * read, no database access on the playback path. The snapshot is kept live
 * from the Room rows by an observation attached to the app-lifetime scope
 * ([attach]).
 */
class LoudnessStore(private val db: LumiDatabase) {

    @Volatile
    private var payloadSnapshot: Map<String, LoudnessSample> = emptyMap()

    /**
     * The current loudness per content payload (a cheap read for the
     * service's per-track gain). Payloads shared by two entries map to the
     * same measurement (a shared file decodes the same loudness).
     */
    fun snapshot(): Map<String, LoudnessSample> = payloadSnapshot

    /** Keeps [snapshot] live from the Room rows (the app-lifetime scope). */
    fun attach(scope: CoroutineScope) {
        scope.launch {
            db.dao().observeEntryLoudnessWithPayload().collect { rows ->
                val byPayload = HashMap<String, LoudnessSample>()
                for (row in rows) {
                    byPayload[row.payload] =
                        LoudnessSample(row.lufs, row.truePeakDbTp, row.fingerprint, row.measuredAt)
                }
                payloadSnapshot = byPayload
            }
        }
    }
}

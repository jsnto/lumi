package com.lumi.player.ui

import android.content.Context
import android.net.Uri
import android.util.Log
import com.lumi.player.data.EntryEntity
import com.lumi.player.data.EntryType
import com.lumi.player.data.LoudnessMeasurer
import com.lumi.player.core.LoudnessResult
import com.lumi.player.data.LoudnessStatus
import com.lumi.player.data.LumiRepository
import com.lumi.player.data.fingerprintOf
import com.lumi.player.core.EntrySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * The audio level's measurement orchestration (the [LumiViewModel]'s
 * loudness surface): the explicit "Normalize now" pass, the on-add
 * measurement of freshly added songs, the content-fingerprint staleness
 * check, the single measurement slot (at most one loudness decode at a
 * time), and the two observed statuses (the pass's live "N of M songs
 * measured" row, and the set of entries the app will actually play
 * volume-normalized). The [LumiViewModel] keeps same-named delegates, so
 * the UI and the test suite never touch this class directly.
 */
class LoudnessOrchestrator(
    private val context: Context,
    private val repository: LumiRepository,
    private val onEvent: (String) -> Unit,
    private val scope: CoroutineScope,
) {

    /**
     * The audio level's measurement status ("N of M songs measured", the
     * settings pane's status row): the app's audio entries against the
     * stored measurements (a silent file's measurement still counts as
     * measured - the read settled, there is just nothing to normalize).
     */
    val loudnessStatus: StateFlow<LoudnessStatus> =
        repository.observeLoudnessStatus().stateIn(scope, SharingStarted.Eagerly, LoudnessStatus(0, 0))

    /**
     * The ids of the content entries the app will actually play
     * volume-normalized (a stored loudness reading AND the audio level's
     * mode enabled - OFF applies no gain), live: the song lists (the
     * content pane's rows, the folder pages' song rows, the tag pages'
     * song rows) badge the entries in this set.
     */
    val normalizedEntryIds: StateFlow<Set<Long>> =
        repository.observeNormalizedEntryIds().stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** True while an explicit "Normalize now" pass is running. */
    private val _normalizing = MutableStateFlow(false)

    val normalizing: StateFlow<Boolean> = _normalizing.asStateFlow()

    private var normalizeJob: Job? = null

    /**
     * The single measurement slot: every loudness read - the on-add
     * measurements and the explicit pass alike - holds it for the full
     * read, so at most one entry decodes at a time; a folder add's burst
     * of fresh songs queues behind the in-flight read, one decode at a
     * time.
     */
    private val measurementLock = Mutex()

    /**
     * The explicit "Normalize now" pass (the audio level settings pane's
     * action): measures every audio entry that has no measurement yet, or
     * whose content changed since the last measurement (the content
     * fingerprint no longer matches the stored one). Best-effort per entry:
     * a failure leaves that entry unmeasured with its own event and the
     * pass keeps going. A second pass while one is running is ignored, so
     * a slow earlier pass can never run against a newer selection; the
     * live progress is the [loudnessStatus] row, plus the pass's own
     * start / completion events (the app's usual event language).
     */
    fun normalizeNow() {
        if (normalizeJob?.isActive == true) return
        normalizeJob = scope.launch {
            _normalizing.value = true
            try {
                val pending = repository.observeEntries().first().filter {
                    EntryType.fromStorage(it.type) == EntryType.AUDIO && isStaleForLoudness(it)
                }
                if (pending.isEmpty()) {
                    onEvent("Everything is already measured.")
                    return@launch
                }
                onEvent(
                    if (pending.size == 1) {
                        "Measuring 1 song for audio level…"
                    } else {
                        "Measuring ${pending.size} songs for audio level…"
                    },
                )
                var failed = 0
                for (entry in pending) {
                    if (!measureEntryBestEffort(entry, reportEvent = true)) failed++
                }
                onEvent(
                    if (failed > 0) {
                        "Audio level ready - $failed " +
                            "${if (failed == 1) "song" else "songs"} couldn't be measured."
                    } else {
                        "Audio level ready."
                    },
                )
            } finally {
                _normalizing.value = false
            }
        }
    }

    /**
     * Best-effort measurement of one freshly added audio entry (the
     * on-add measurement: a song added to the content list is measured
     * right away, so the audio level works the moment it is turned on).
     * A failure leaves the entry unmeasured with its own event (the song
     * plays at its recorded level; "Normalize now" can retry it).
     */
    fun measureEntry(entry: EntryEntity) {
        scope.launch {
            measureEntryBestEffort(entry, reportEvent = true)
        }
    }

    /**
     * The measurement pass for [entry]: the content fingerprint check
     * first (an unchanged file keeps its stored measurement - no re-read),
     * then the best-effort read (off the main thread and time-limited - any
     * failure settles to unmeasured, with an event when [reportEvent]). The
     * whole pass holds the single [measurementLock], so on-add measurements
     * and the explicit pass queue behind each other - never two decodes at
     * once. Returns whether the entry now has a stored measurement.
     */
    private suspend fun measureEntryBestEffort(entry: EntryEntity, reportEvent: Boolean): Boolean =
        measurementLock.withLock {
            val fingerprint = fingerprintOf(listOf(entry.toSpec()))
            val existing = repository.loudnessForEntry(entry.id)
            if (existing != null && existing.fingerprint == fingerprint) return@withLock true
            val result = try {
                withTimeout(LoudnessMeasurer.MEASURE_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) { loudnessMeasurer(Uri.parse(entry.payload)) }
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "Measuring the loudness of ${entry.label} timed out", e)
                if (reportEvent) onEvent("Couldn't measure “${entry.label}” for audio level.")
                return@withLock false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't measure the loudness of ${entry.label}", e)
                if (reportEvent) onEvent("Couldn't measure “${entry.label}” for audio level.")
                return@withLock false
            }
            repository.recordLoudness(entry.id, result.lufs, result.truePeakDbTp, fingerprint)
            true
        }

    /** Whether [entry]'s stored measurement (if any) matches its content now. */
    private suspend fun isStaleForLoudness(entry: EntryEntity): Boolean {
        val row = repository.loudnessForEntry(entry.id)
        return row == null || row.fingerprint != fingerprintOf(listOf(entry.toSpec()))
    }

    /**
     * Test seam: the loudness measurement read, replaced in unit tests.
     * The default decodes the file at decode speed (the [LoudnessMeasurer]:
     * an ExoPlayer - driven on the player's application thread, the main
     * looper; see its KDoc - with the meter's [LoudnessMeterProcessor] and
     * the [MeterAudioSink] that discards the audio) and is time-limited
     * (a stalled decode settles into a failure); a read failure surfaces as
     * the exception the caller settles to unmeasured.
     */
    internal var loudnessMeasurer: suspend (Uri) -> LoudnessResult = { uri ->
        LoudnessMeasurer(context).measure(uri)
    }

    private fun EntryEntity.toSpec(): EntrySpec =
        EntrySpec(type = type, label = label, payload = payload)

    private companion object {
        /** Log tag: kept on the view model's tag (the failures are attributed there). */
        private const val TAG = "LumiViewModel"
    }
}

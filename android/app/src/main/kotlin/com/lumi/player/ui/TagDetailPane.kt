package com.lumi.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.ModeEdit
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lumi.player.data.EntryEntity
import com.lumi.player.data.TagEntity
import com.lumi.player.data.EntryType
import com.lumi.player.data.TagSongRow
import com.lumi.player.data.TagSyncState
import com.lumi.player.nfc.LumiTagData
import com.lumi.player.nfc.LumiTagNdef
import com.lumi.player.nfc.TagCardDetails
import com.lumi.player.nfc.ndefTypeName
import kotlin.math.roundToInt

/**
 * One registered tag, opened by tapping its row in the NFC tags pane. The
 * page shows the tag's details (name + UID) with an edit action that opens
 * the edit dialog (the name is the only editable detail; the UID is
 * hardware-derived and read-only), its current song list - in the order the
 * songs were added, reorderable by dragging a row's handle, and each row
 * individually removable (the row's remove action unlinks that ONE song
 * from THIS tag - the song stays in the content list). A song is
 * identified by its NAME, resolved live against the content: a song that
 * was removed from the library and later re-imported under the same name
 * becomes playable again without re-adding it, and a name that resolves
 * to no content shows as a "song not found" row (the row's link survives
 * the song's removal; the row renders its snapshot name) - a warning
 * sign at its original position, no play action, removable like any other
 * row - with an add-song action aimed at THIS tag, and ends in a danger
 * zone whose delete removes the tag - and only the tag and its links.
 *
 * The song list reuses the shared selection plumbing: opening the page
 * selects the tag, so [linked] (the selected tag's entries) is this tag's
 * list. The add-song action opens the in-app content list, where the user
 * checks the songs to add and saves the selection at once - each checked
 * song links an already-stored content entry to the tag
 * ([LumiViewModel.linkEntryToTag], one link per checked song) - no file
 * picker, no duplicated song.
 * A completed drag reorders the list and persists it through
 * [LumiViewModel.reorderTagSongs] - the play order is the link order, so
 * the persisted order is the play order. A row's remove action confirms
 * ([LumiViewModel.unlinkSongFromTag], the inverse of the add-song link)
 * and drops only that tag's link to that one song: the song stays in the
 * content list (for a "song not found" row the dead link itself goes),
 * and if the list empties the page falls back to the
 * "This tag plays nothing yet." state. A row whose live entry the app
 * will actually play volume-normalized badges it (the [normalized] ids).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TagDetailPane(
    viewModel: LumiViewModel,
    tag: TagEntity,
    linked: List<TagSongRow>,
    normalized: Set<Long>,
    onBack: () -> Unit,
    onAddSong: () -> Unit,
    onEdit: (TagEntity) -> Unit,
    onDelete: (TagEntity) -> Unit,
    onRemoveSong: (TagSongRow) -> Unit,
) {
    // Pointing the shared selection at this tag makes the observed [linked]
    // list this tag's song list.
    LaunchedEffect(tag.id) { viewModel.selectTag(tag.id) }
    val scheme = MaterialTheme.colorScheme
    // The additional card details of the tag's last presented handle (see
    // the details section below): observed through the flow, so the page
    // re-composes when a read publishes the tag's details.
    val tagDetails by viewModel.tagCardDetails.collectAsState()
    Scaffold(topBar = { PaneTopAppBar(tag.name.ifEmpty { "Tag" }, onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // The edit action sits on the section's title row (the settled
            // title-row action language, like the Songs section's add
            // button) and opens the edit dialog: the name is the only
            // stored detail, the UID is hardware-derived and read-only.
            SectionCard(
                title = "Tag details",
                trailing = {
                    SoftIconButton(
                        icon = Icons.Filled.ModeEdit,
                        contentDescription = "Edit tag",
                        onClick = { onEdit(tag) },
                    )
                },
            ) {
                SettingRow(
                    leading = {
                        IconChip(
                            icon = Icons.Filled.Label,
                            background = scheme.secondaryContainer,
                            content = scheme.onSecondaryContainer,
                        )
                    },
                    title = tag.name.ifEmpty { "Tag" },
                    detail = tag.uid,
                )
                // The additional card information the tag's last presented
                // handle carried (observed on a read - a scan, a
                // registration, or a playback read; discovery-time data
                // only - no connect, no RF): each field shows only when
                // the presented handle actually carried the value, never
                // guessed. The details are recorded PER UID (a read of
                // another tag updates only that tag's entry), so this
                // page keeps showing this tag's own details no matter
                // what is scanned afterwards - and a tag whose details
                // were never observed shows the section empty.
                val details = tagDetails[tag.uid]
                if (details != null) {
                    if (details.techNames.isNotEmpty()) {
                        CardDetailField("Tech", details.techNames.joinToString(", "))
                    }
                    details.ndefType?.let { CardDetailField("NDEF type", ndefTypeName(it)) }
                    details.maxNdefSizeBytes?.let { CardDetailField("NDEF capacity", "$it bytes") }
                    details.ndefWritable?.let { CardDetailField("Writable", if (it) "Yes" else "No") }
                }
            }
            // The + button on this section's title row opens the in-app
            // content list: the user checks the songs to add and saves the
            // selection at once - each checked song links one of the app's
            // existing content entries (individual or folder-imported) to
            // THIS tag, no file picker, so nothing is stored twice. The
            // rows keep the settled row presentation (icon chip, label,
            // detail) and end in a drag handle that reorders the list (the
            // persisted link order - the play order - follows the drag).
            SectionCard(
                title = "Songs",
                addLabel = "Add song",
                onAdd = { onAddSong() },
            ) {
                if (linked.isEmpty()) {
                    Text(
                        text = "This tag plays nothing yet.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    ReorderableSongList(
                        rows = linked,
                        onReordered = { viewModel.reorderTagSongs(tag, it) },
                        onRemoveSong = onRemoveSong,
                        normalized = normalized,
                    )
                }
            }
            SaveToTagSection(
                viewModel = viewModel,
                tag = tag,
                linked = linked,
                tagDetails = tagDetails,
            )
            // The danger zone: a visually separated, clearly destructive
            // section. The detail copy states the effect the confirm will
            // carry out - the tag and its song links go; the songs stay in
            // the content list (deleteTag never deletes entries).
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = scheme.errorContainer),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "Danger zone",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = scheme.onErrorContainer,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconChip(
                            icon = Icons.Default.Delete,
                            background = scheme.error,
                            content = scheme.onError,
                            size = 36.dp,
                            iconSize = 19.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Delete tag",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                color = scheme.onErrorContainer,
                            )
                            Text(
                                "Removes the tag and its song links. Its songs stay in the content list.",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onErrorContainer,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        DestructivePillButton("Delete", onClick = { onDelete(tag) })
                    }
                }
            }
        }
    }
}

/**
 * The tag detail page's song list, reorderable by dragging: each row ends in
 * a remove action (the row-level delete, which confirms and unlinks that ONE
 * song from THIS tag - the song stays in the content list) and a drag handle
 * that is pressed and dragged vertically. The two are separate regions, so
 * the remove never fights the drag gesture. A row whose song NAME resolves
 * to no content (the tag's "song not found" row) renders a warning sign
 * instead of the song, titled by the row's own snapshot name: it keeps its
 * position, offers no play action and no drag handle, and its remove action
 * drops the dead link. While the drag is
 * held, the list previews the new order live - the held row follows the
 * pointer and swaps places as it crosses a row boundary - and a completed
 * drag persists the order (the tag links' sortOrder) through [onReordered],
 * the page's full row list (missing rows included), so a persisted reorder
 * never drops a link whose song is missing.
 * A drag that ends where it started - or a plain tap on the handle -
 * persists nothing.
 *
 * The row pitch is measured from the laid-out rows (the gap between the tops
 * of the first two rows), so the pointer math tracks the layout instead of
 * assuming a row height. The page's vertical scroll never fights the drag:
 * once a move has started on the handle it is consumed by the drag, so a
 * drag scrolls nothing; the handle is the only row region that captures the
 * pointer, so a scroll started elsewhere on the page is untouched.
 */
/**
 * The tag details page's "Save to NFC tag" section: the write/compare scan
 * buttons, plus - above them - the in-sync indicator (DERIVED, not
 * event-driven): the tag's current system data (its name
 * + its ordered song display names - exactly what the write
 * path composes) against its last known tag state (its
 * per-uid saved memory: name + songs). InSync = the memory
 * exists and equals it (no change since the last save);
 * Differs = the memory exists but no longer matches - any
 * tag edit (a rename, a song added/removed/reordered) flips
 * it at once; Unknown = no saved memory for the uid yet
 * (nothing saved or compared yet). A successful write records
 * the memory; the compare scan records what the tag actually
 * carries (a blank tag clears the record).
 *
 * The buttons: "Scan to compare" arms the compare scan - the
 * next read of this tag's OWN tag (its NFC ID matches the
 * tag's) is READ, not written, and its actual contents become
 * the tag's per-uid memory. "Write to NFC tag" arms the
 * EXISTING write scan - the next read of this tag's own tag
 * is written the tag's data (its name and its ordered song
 * list, the tag's own payload shape) when it is writable and
 * can hold it. A tag with a different NFC ID is reported and
 * left untouched (the write path's own verdict language).
 *
 * The WRITE button mirrors the write's own size check LIVE:
 * the payload it arms (the tag's name and the tag's ordered
 * song list - the same payload the app's write cap measures
 * at settle time, in NfcTagCoordinator's card-write settle) is measured on every
 * recomposition, and while it is over the cap NO tag can
 * take it - so the button is greyed out and the note says
 * so: a write that cannot succeed cannot even be attempted.
 * The settle-side pre-check stays as the backstop (the list
 * can change after the button is rendered).
 *
 * The WRITE button is ALSO greyed out (with its own note)
 * while the phone's own NFC is not usable - no NFC hardware,
 * or the radio off: a tag write goes through the phone's
 * radio, so without it no write can succeed. The usability
 * flags are published by MainActivity (re-checked on resume
 * AND on the radio's state-changed broadcast), so
 * re-enabling the radio while the app is open re-enables the
 * button live - no restart or re-entry.
 *
 * AND while the tag's OWN tag is known non-writable from the
 * last presented handle's discovery data (the same uid-keyed
 * details the details section shows): a tag that carries no
 * NDEF tech, or whose NDEF area is read-only, is refused by
 * the settle-time gate the same way - so a write that cannot
 * succeed cannot even be attempted, and the note mirrors the
 * gate's own verdict copy. A tag whose details were never
 * observed is NOT refused here (unknown is not non-writable):
 * the gate decides for the tag actually presented at settle
 * time. The three conditions compose: the write button is
 * never tappable while any of them holds. The COMPARE button
 * is gated on the radio's usability alone: it READS the tag
 * (a read-only or non-NDEF tag still answers - or answers
 * with no data, which is a legitimate compare result).
 */
@Composable
private fun SaveToTagSection(
    viewModel: LumiViewModel,
    tag: TagEntity,
    linked: List<TagSongRow>,
    tagDetails: Map<String, TagCardDetails>,
) {
    val scheme = MaterialTheme.colorScheme
    SectionCard(title = "Save to NFC tag") {
        Text(
            "Writes this tag's data (its name and its song list) to the NFC tag. Only this tag's own tag is written - a tag with a different NFC ID is not.",
            style = MaterialTheme.typography.bodyMedium,
        )
        // The in-sync indicator (derived, above the buttons -
        // see the section's note): the observed state of the
        // selected tag (this page selects it on open).
        val syncState by viewModel.tagSaveSync.collectAsState()
        Text(
            when (syncState) {
                TagSyncState.InSync -> "In sync - no changes since the last save."
                TagSyncState.Differs -> "This tag's data differs from the saved tag."
                TagSyncState.Unknown -> "Nothing saved or compared yet."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        val payloadBytes = LumiTagNdef.messageByteLength(
            LumiTagNdef.toNdefMessage(
                LumiTagData(tag.name, linked.map { it.name }),
            ),
        )
        val overCap = payloadBytes > LumiTagNdef.MAX_PAYLOAD_BYTES
        if (overCap) {
            Text(
                "This tag's data is too large for the tag payload - nothing can be written.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        // The phone's own NFC usability (published by MainActivity,
        // live off the radio's state-changed broadcast): a tag
        // write needs the radio, so the control is unusable while
        // the hardware is missing or the radio is off.
        val nfcMissing by viewModel.nfcMissing.collectAsState()
        val nfcDisabled by viewModel.nfcDisabled.collectAsState()
        val nfcUsable = !nfcMissing && !nfcDisabled
        if (!nfcUsable) {
            Text(
                if (nfcMissing) {
                    "This device has no NFC, so it can't save to a tag."
                } else {
                    "Turn on NFC to save to tag."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        // The tag's own tag, as its last presented handle
        // reported it (the same uid-keyed record the details
        // section shows): no NDEF tech, or a read-only NDEF area,
        // is refused by the settle-time gate the same way, so the
        // write control is unusable while that is what the last
        // read saw. A tag whose details were never observed is
        // not refused here: the gate decides for the tag actually
        // presented at settle time.
        val cardDetails = tagDetails[tag.uid]
        val cardUnwritable = cardDetails != null &&
            (cardDetails.ndefType == null || cardDetails.ndefWritable == false)
        if (cardUnwritable) {
            Text(
                if (cardDetails?.ndefType == null) {
                    "That tag isn't an NDEF tag, so it can't be written."
                } else {
                    "That tag is read-only, so it can't be written."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        // Button 1: scan to compare - the compare scan READS this
        // tag's own tag (never writes it) and records its actual
        // contents as the tag's per-uid memory; the indicator
        // recomputes from the comparison. Gated on the radio's
        // usability alone (a read is refused by nothing else up
        // front - a non-NDEF or blank tag is a legitimate
        // compare result).
        FilledTonalButton(
            onClick = { viewModel.startCardCompare(tag) },
            enabled = nfcUsable,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = scheme.secondaryContainer,
                contentColor = scheme.onSecondaryContainer,
            ),
        ) {
            Icon(
                Icons.Filled.Nfc,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Scan to compare",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        // Button 2: write to the NFC tag - the existing write
        // flow (the next read of this tag's own tag is written
        // the tag's data), its enabled-state composition
        // UNCHANGED: an over-cap or unwritable situation still
        // blocks it exactly as before.
        FilledTonalButton(
            onClick = { viewModel.startCardWrite(tag) },
            enabled = !overCap && nfcUsable && !cardUnwritable,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = scheme.secondaryContainer,
                contentColor = scheme.onSecondaryContainer,
            ),
        ) {
            Text(
                "Write to NFC tag",
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun ReorderableSongList(
    rows: List<TagSongRow>,
    onReordered: (List<TagSongRow>) -> Unit,
    onRemoveSong: (TagSongRow) -> Unit,
    normalized: Set<Long>,
) {
    val scheme = MaterialTheme.colorScheme
    // The observed list, mirrored into state: the drag's pointer closure
    // outlives recompositions, so it must read the CURRENT observed list
    // through state - a plain capture of the [rows] parameter would go
    // stale (e.g. after a song is added) and a drag would reorder against
    // a list that is no longer the one on screen.
    var observed by remember { mutableStateOf(rows) }
    LaunchedEffect(rows) { observed = rows }

    // The in-flight drag: the row being held, the order it is previewing, the
    // held row's offset past its last crossed boundary, and the indices the
    // drag started at and currently sits at (-1 while no drag is in flight).
    // Rows are keyed by linkId - the row's stored link's id, the row's stable
    // identity (a re-imported entry changes the row's entry reference, not
    // its identity) - unique per row.
    var draggedId by remember { mutableStateOf<Long?>(null) }
    var order by remember { mutableStateOf<List<TagSongRow>>(emptyList()) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    var dragStartIndex by remember { mutableIntStateOf(-1) }
    var dragCurrentIndex by remember { mutableIntStateOf(-1) }
    // The order the last completed drag wrote, shown until the observed
    // list echoes it back - so releasing a drag never snaps the list back
    // to the pre-drag order for a frame while the write lands. The
    // baseline is the list the drag previewed from (captured when the
    // drag's snapshot is taken): an observed list that settles to
    // something that is neither the baseline nor the pending order means
    // the write did not land as dragged (the list changed in flight),
    // and the provisional order is dropped so the page shows the
    // observed list.
    var pending by remember { mutableStateOf<List<TagSongRow>?>(null) }
    var pendingBaseline by remember { mutableStateOf<List<TagSongRow>?>(null) }
    LaunchedEffect(pending, pendingBaseline, observed) {
        val pendingOrder = pending
        if (pendingOrder != null) {
            val settled = observed
            if (settled != pendingOrder && settled != pendingBaseline) {
                pending = null
                pendingBaseline = null
            }
        }
    }

    // The measured row tops (root coordinates) and the row pitch - the gap
    // between the first two laid-out rows, which scrolling cannot distort -
    // both state so the pointer closure reads the current layout.
    val rowTops = remember { mutableMapOf<Long, Float>() }
    val pitch = remember { mutableFloatStateOf(0f) }

    fun displayedList(): List<TagSongRow> {
        val pendingOrder = pending
        return when {
            draggedId != null -> order
            pendingOrder != null && pendingOrder != observed -> pendingOrder
            else -> observed
        }
    }
    val shown = displayedList()

    fun handleDragged(linkId: Long, totalDeltaY: Float) {
        val pitchPx = pitch.floatValue
        if (pitchPx <= 0f) return
        val shown = displayedList()
        if (dragStartIndex < 0) {
            // First move of the drag: snapshot the order (the baseline the
            // pending order is echoed against) and mark the row.
            val start = shown.indexOfFirst { it.linkId == linkId }
            if (start < 0) return
            dragStartIndex = start
            dragCurrentIndex = start
            order = shown
            pendingBaseline = shown
            draggedId = linkId
        }
        val target = (dragStartIndex + (totalDeltaY / pitchPx).roundToInt())
            .coerceIn(0, shown.size - 1)
        if (target != dragCurrentIndex) {
            order = order.moved(dragCurrentIndex, target)
            dragCurrentIndex = target
        }
        dragOffsetPx = totalDeltaY - (dragCurrentIndex - dragStartIndex) * pitchPx
    }

    fun handleDragEnded() {
        if (dragStartIndex < 0) return
        if (dragCurrentIndex != dragStartIndex) {
            pending = order
            onReordered(order)
        }
        draggedId = null
        dragOffsetPx = 0f
        dragStartIndex = -1
        dragCurrentIndex = -1
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        shown.forEach { row ->
            val entry = row.entry
            // The row's own name: the live row's current label (the name
            // its link resolved to), the missing row's snapshot name -
            // a blank snapshot (a link stored before the name column
            // existed, whose entry is gone) falls back to the generic
            // marker.
            val songName = row.name.ifBlank { "Song not found" }
            val isDragging = row.linkId == draggedId
            SettingRow(
                leading = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconChip(
                            // A row whose song name resolves to no content
                            // renders the warning sign (the "song not found"
                            // marker) instead of the song's icon.
                            icon = if (entry != null) EntryPresentation.icon(EntryType.fromStorage(entry.type)) else Icons.Filled.Warning,
                            background = if (entry != null) scheme.primaryContainer else scheme.errorContainer,
                            content = if (entry != null) scheme.onPrimaryContainer else scheme.onErrorContainer,
                        )
                        // The app will actually play this row's live entry
                        // volume-normalized (a stored loudness reading AND
                        // the audio level's mode on); a "song not found"
                        // row's entry is gone, so it never badges.
                        if (entry != null && entry.id in normalized) {
                            Spacer(Modifier.width(6.dp))
                            NormalizedBadge()
                        }
                    }
                },
                // The row's own name (see [songName]): never a guessed
                // placeholder for a row that remembers its song.
                title = songName,
                detail = if (entry != null) {
                    detailFor(entry.type, entry.payload)
                } else {
                    "this song is no longer in the content list"
                },
                modifier = Modifier
                    .graphicsLayer {
                        if (isDragging) translationY = dragOffsetPx
                    }
                    .onGloballyPositioned { coords ->
                        rowTops[row.linkId] = coords.localToRoot(Offset.Zero).y
                        val tops = shown.mapNotNull { rowTops[it.linkId] }
                        if (draggedId == null && tops.size >= 2) {
                            pitch.floatValue = (tops[1] - tops[0]).coerceAtLeast(0f)
                        }
                    },
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // The per-song removal (the content pane's row-level
                        // delete language): the tag's link to this one song
                        // goes; the song stays in the content list (for a
                        // missing row the dead link itself goes). A
                        // separate region from the drag handle, so the
                        // remove never fights the drag gesture.
                        SoftIconButton(
                            icon = Icons.Default.Delete,
                            contentDescription = "Remove $songName from the tag",
                            onClick = { onRemoveSong(row) },
                        )
                        // The drag handle exists only for rows that still
                        // carry a song: a missing row keeps its position
                        // but offers no play action, so no reorder either.
                        if (entry != null) {
                            Spacer(Modifier.width(8.dp))
                            SongDragHandle(
                                linkId = row.linkId,
                                contentDescription = "Reorder $songName",
                                onDrag = { id, delta -> handleDragged(id, delta) },
                                onDragEnd = { handleDragEnded() },
                            )
                        }
                    }
                },
            )
        }
    }
}

/**
 * One additional card-detail field of the tag page: a label column and a
 * single-line value (shown only when the presented tag's discovery data
 * carried the value - the section shows no field it does not have).
 */
@Composable
private fun CardDetailField(label: String, value: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Moves the item at [from] to index [to] (the other items keep their order). */
private fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from == to || from < 0 || from >= size || to < 0 || to >= size) return this
    val item = this[from]
    val copy = toMutableList()
    copy.removeAt(from)
    copy.add(to, item)
    return copy
}

/**
 * A song row's drag handle: the settled 40 dp soft circle (the row-level
 * action buttons' own treatment) carrying the drag grip. Pressing it and
 * moving the pointer vertically drags the row the handle renders at the
 * press: every move is reported to [onDrag] (with that row's id) as the
 * CUMULATIVE vertical delta (pixels, up negative - measured from the press
 * point in the handle's own coordinates) and [onDragEnd] fires when the
 * pointer lifts; the press-drag-lift cycle repeats for the life of the
 * node (the list reuses the node positionally across reorders), so the
 * handle is ready for the next gesture on whatever row it now shows. A
 * plain tap reports nothing - no delta, no reorder.
 *
 * The handle consumes every move from the first one on, so the page's
 * vertical scroll does not run while the handle is held.
 */
@Composable
private fun SongDragHandle(
    linkId: Long,
    contentDescription: String,
    onDrag: (linkId: Long, totalDeltaY: Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val rowId = rememberUpdatedState(linkId)
    Box(
        modifier = Modifier
            .size(40.dp)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        // Press and hold: report the cumulative vertical delta
                        // (from the press point, in the handle's own coordinates)
                        // on every move; end the drag when the pointer lifts.
                        val down = awaitFirstDown()
                        val id = rowId.value
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            onDrag(id, change.position.y - down.position.y)
                            change.consume()
                        }
                        onDragEnd()
                    }
                }
            }
            .semantics { this.contentDescription = contentDescription }
            .background(scheme.surfaceContainerHigh, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.DragHandle,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = scheme.onSurfaceVariant,
        )
    }
}

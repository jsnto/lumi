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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.ModeEdit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import com.lumi.player.data.EntryEntity
import com.lumi.player.data.TagEntity
import com.lumi.player.data.EntryType
import kotlin.math.roundToInt

/**
 * One registered tag, opened by tapping its row in the NFC tags pane. The
 * page shows the tag's details (name + UID) with an edit action that opens
 * the edit dialog (the name is the only editable detail; the UID is
 * hardware-derived and read-only), its current song list - in the order the
 * songs were added, reorderable by dragging a row's handle, and each row
 * individually removable (the row's remove action unlinks that ONE song
 * from THIS tag - the song stays in the content list) - with an add-song
 * action aimed at THIS tag, and ends in a danger zone whose delete removes
 * the tag - and only the tag and its links.
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
 * ([LumiViewModel.unlinkEntryFromTag], the inverse of the add-song link)
 * and drops only that tag's link to that one song: the song stays in the
 * content list, and if the list empties the page falls back to the
 * "This tag plays nothing yet." state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TagDetailPane(
    viewModel: LumiViewModel,
    tag: TagEntity,
    linked: List<EntryEntity>,
    onBack: () -> Unit,
    onAddSong: () -> Unit,
    onEdit: (TagEntity) -> Unit,
    onDelete: (TagEntity) -> Unit,
    onRemoveSong: (EntryEntity) -> Unit,
) {
    // Pointing the shared selection at this tag makes the observed [linked]
    // list this tag's song list.
    LaunchedEffect(tag.id) { viewModel.selectTag(tag.id) }
    val scheme = MaterialTheme.colorScheme
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
                        songs = linked,
                        onReordered = { viewModel.reorderTagSongs(tag, it) },
                        onRemoveSong = onRemoveSong,
                    )
                }
            }
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
 * the remove never fights the drag gesture. While the drag is
 * held, the list previews the new order live - the held row follows the
 * pointer and swaps places as it crosses a row boundary - and a completed
 * drag persists the order (the tag links' sortOrder) through [onReordered].
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
@Composable
private fun ReorderableSongList(
    songs: List<EntryEntity>,
    onReordered: (List<EntryEntity>) -> Unit,
    onRemoveSong: (EntryEntity) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // The observed list, mirrored into state: the drag's pointer closure
    // outlives recompositions, so it must read the CURRENT observed list
    // through state - a plain capture of the [songs] parameter would go
    // stale (e.g. after a song is added) and a drag would reorder against
    // a list that is no longer the one on screen.
    var observed by remember { mutableStateOf(songs) }
    LaunchedEffect(songs) { observed = songs }

    // The in-flight drag: the row being held, the order it is previewing, the
    // held row's offset past its last crossed boundary, and the indices the
    // drag started at and currently sits at (-1 while no drag is in flight).
    var draggedId by remember { mutableStateOf<Long?>(null) }
    var order by remember { mutableStateOf<List<EntryEntity>>(emptyList()) }
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
    var pending by remember { mutableStateOf<List<EntryEntity>?>(null) }
    var pendingBaseline by remember { mutableStateOf<List<EntryEntity>?>(null) }
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

    fun displayedList(): List<EntryEntity> {
        val pendingOrder = pending
        return when {
            draggedId != null -> order
            pendingOrder != null && pendingOrder != observed -> pendingOrder
            else -> observed
        }
    }
    val shown = displayedList()

    fun handleDragged(entryId: Long, totalDeltaY: Float) {
        val pitchPx = pitch.floatValue
        if (pitchPx <= 0f) return
        val shown = displayedList()
        if (dragStartIndex < 0) {
            // First move of the drag: snapshot the order (the baseline the
            // pending order is echoed against) and mark the row.
            val start = shown.indexOfFirst { it.id == entryId }
            if (start < 0) return
            dragStartIndex = start
            dragCurrentIndex = start
            order = shown
            pendingBaseline = shown
            draggedId = entryId
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
        shown.forEach { entry ->
            val isDragging = entry.id == draggedId
            SettingRow(
                leading = {
                    IconChip(
                        icon = EntryPresentation.icon(EntryType.fromStorage(entry.type)),
                        background = scheme.primaryContainer,
                        content = scheme.onPrimaryContainer,
                    )
                },
                title = entry.label,
                detail = detailFor(entry.type, entry.payload),
                modifier = Modifier
                    .graphicsLayer {
                        if (isDragging) translationY = dragOffsetPx
                    }
                    .onGloballyPositioned { coords ->
                        rowTops[entry.id] = coords.localToRoot(Offset.Zero).y
                        val tops = shown.mapNotNull { rowTops[it.id] }
                        if (draggedId == null && tops.size >= 2) {
                            pitch.floatValue = (tops[1] - tops[0]).coerceAtLeast(0f)
                        }
                    },
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // The per-song removal (the content pane's row-level
                        // delete language): the tag's link to this one song
                        // goes; the song stays in the content list. A
                        // separate region from the drag handle, so the
                        // remove never fights the drag gesture.
                        SoftIconButton(
                            icon = Icons.Default.Delete,
                            contentDescription = "Remove ${entry.label} from the tag",
                            onClick = { onRemoveSong(entry) },
                        )
                        Spacer(Modifier.width(8.dp))
                        SongDragHandle(
                            entryId = entry.id,
                            contentDescription = "Reorder ${entry.label}",
                            onDrag = { id, delta -> handleDragged(id, delta) },
                            onDragEnd = { handleDragEnded() },
                        )
                    }
                },
            )
        }
    }
}

/** Moves the item at [from] to index [to] (the other items keep their order). */
private fun List<EntryEntity>.moved(from: Int, to: Int): List<EntryEntity> {
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
    entryId: Long,
    contentDescription: String,
    onDrag: (entryId: Long, totalDeltaY: Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val rowId = rememberUpdatedState(entryId)
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

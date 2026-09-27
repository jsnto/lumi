// The NFC tags pane + the per-tag detail page (the Android app's
// TagsPane + the tag detail sub-screen).
//
// The pane: NO + button - the single scan entry point is the prominent
// "Scan NFC" button above the tag list (visually separated - it must read
// as an action, not as another list row), and tapping a tag row opens the
// tag's detail page. The scan-in-progress dialog copy is the Android
// app's ("Hold the back of the phone against any NFC tag..."), and the
// scan settles on the FIRST read (the controller's single read path).
//
// The detail page: the tag's name + UID (the UID is hardware-derived and
// read-only), the edit action (the name is the only stored detail), the
// song list (drag-reorderable, per-song remove, per-song play/pause),
// the "Add song" in-app content picker, the "Save to NFC tag" section
// (the derived in-sync indicator + the "Scan to compare" read and the
// "Write to NFC tag" write with its uid match + size cap + NFC usability
// gates, mirroring the Android app's save-section gates), and the danger
// zone.

import SwiftUI
import LumiCore

struct TagsPane: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var nav: NavState

    var body: some View {
        ScrollView {
            // The scan action, above the list (the Android pane's
            // placement: separated, reading as an action).
            Button {
                model.startTagScan()
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "wifi")
                    Text("Scan NFC").font(.headline)
                }
                .foregroundStyle(LumiPalette.onPrimaryContainer)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 14)
                .background(LumiPalette.primaryContainer)
                .clipShape(RoundedRectangle(cornerRadius: 20))
            }
            .buttonStyle(.plain)
            .padding(.horizontal, 12)
            .padding(.bottom, 12)

            SectionCard(title: "Tags") {
                if model.tags.isEmpty {
                    LumiCard {
                        Text("No tags yet. Scan one to register it.")
                            .font(.subheadline).foregroundStyle(LumiPalette.onSurfaceVariant)
                    }
                } else {
                    LumiCard {
                        ForEach(model.tags) { tag in
                            // The row's OWN tap pushes the tag's page (a
                            // NavigationLink wrapper would swallow it -
                            // the menu rows' observed bug).
                            MenuRowView(
                                title: tag.name,
                                subtitle: "ID: \(tag.uid)",
                                systemImage: "nfc",
                                onTap: { nav.path.append(Route.tagDetail(tag.id)) },
                            )
                            if tag.id != model.tags.last?.id { Divider() }
                        }
                    }
                }
            }
        }
        .background(LumiPalette.background)
        .navigationTitle("NFC tags")
        .overlay(alignment: .bottom) {
            if let event = model.uiEvent { EventBanner(message: event) }
        }
    }
}

/// The tag naming dialog (the Android app's registration dialog: it asks
/// for the name ONLY - there is no play-mode selector; saving restores
/// the captured song list onto an empty tag).
struct NamingDialog: View {
    @EnvironmentObject private var model: AppModel
    let capture: AppModel.CaptureState
    @State private var name: String = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("New tag") {
                    TextField("Tag name", text: $name)
                }
                if !capture.restoreSongs.isEmpty {
                    Section("Songs from this card") {
                        ForEach(capture.restoreSongs, id: \.self) { song in
                            Text(song).font(.subheadline)
                        }
                    }
                }
            }
            .navigationTitle("Name this tag")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { model.discardCapture() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        save()
                    }
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .onAppear { name = capture.name }
        }
    }

    private func save() {
        // The app model's confirmTagName reads model.capture; the edited
        // name lands there first.
        var c = capture
        c.name = name
        model.capture = c
        model.confirmTagName()
    }
}

struct TagDetailPane: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var nav: NavState
    let tagId: Int
    @State private var editingName = false
    @State private var renameText = ""
    @State private var addingSong = false
    @State private var removingLink: TagSongRow? = nil
    @State private var confirmingDelete = false

    private var tag: Tag? { model.store.tagById(tagId) }

    var body: some View {
        // A List (not a ScrollView + cards): the song rows' drag
        // reorder (.onMove) is only reachable inside a List - a ForEach
        // in a ScrollView compiles, but the move capability is inert
        // there and the list's reorder was unreachable (the observed
        // bug). The other sections keep the card chrome (a LumiCard in
        // a clear list row).
        List {
            // The tag details (the Android page's 'Tag details' section:
            // name + the hardware-derived read-only UID, the Edit action
            // on the section's title row).
            Section {
                LumiCard {
                    if let tag {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(tag.name).font(.title2).bold().foregroundStyle(LumiPalette.onSurface)
                            Text("ID: \(tag.uid)")
                                .font(.footnote).foregroundStyle(LumiPalette.onSurfaceVariant)
                        }
                    } else {
                        Text("This tag was removed.")
                            .font(.subheadline).foregroundStyle(LumiPalette.onSurfaceVariant)
                    }
                }
            } header: {
                ListSectionHeader("Tag details", trailingLabel: "Edit", onTrailing: beginRename)
            }
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets())

            // The song list: added order, drag-reorderable (.onMove),
            // per-song play/pause + remove.
            Section {
                if let tag, !model.tagRows(tag).isEmpty {
                    ForEach(model.tagRows(tag)) { row in
                        TagSongRowView(
                            row: row,
                            isCurrent: row.entry.map { $0.label == model.engine.currentLabel && model.engine.status != .idle } ?? false,
                            onRemove: { removingLink = row },
                        )
                    }
                    .onMove(perform: moveSongs)
                } else {
                    LumiCard {
                        Text("This tag plays nothing yet. Add a song or scan the tag to restore its list.")
                            .font(.subheadline).foregroundStyle(LumiPalette.onSurfaceVariant)
                    }
                }
            } header: {
                ListSectionHeader("Songs", addLabel: "Add song", onAdd: { addingSong = true })
            }
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets())

            // The "Save to NFC tag" section (the Android page's save
            // section: the derived in-sync indicator + the two actions
            // with their live gates).
            if let tag {
                Section {
                    LumiCard {
                        saveSection(for: tag)
                    }
                } header: {
                    ListSectionHeader("Save to NFC tag")
                }
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets())
            }

            // The danger zone.
            Section {
                LumiCard(fill: LumiPalette.errorContainer.opacity(0.4)) {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Deleting removes the tag and its song links. The songs stay in the content list.")
                            .font(.footnote).foregroundStyle(LumiPalette.onSurfaceVariant)
                        Button(role: .destructive) {
                            confirmingDelete = true
                        } label: {
                            Text("Delete tag")
                                .font(.subheadline).fontWeight(.semibold)
                                .foregroundStyle(.white)
                                .padding(.horizontal, 16).padding(.vertical, 8)
                                .background(Capsule().fill(LumiPalette.error))
                        }
                        .buttonStyle(.plain)
                    }
                }
            } header: {
                ListSectionHeader("Delete tag")
            }
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets())
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(LumiPalette.background)
        .navigationTitle(tag?.name ?? "Tag")
        .sheet(isPresented: $editingName) {
            if let tag {
                RenameDialog(tag: tag, initial: tag.name)
            }
        }
        .sheet(isPresented: $addingSong) {
            AddSongPicker(tagId: tagId)
        }
        .alert("Remove song from the tag?", isPresented: Binding(
            get: { removingLink != nil },
            set: { if !$0 { removingLink = nil } },
        )) {
            Button("Cancel", role: .cancel) { removingLink = nil }
            Button("Remove", role: .destructive) {
                if let tag, let row = removingLink {
                    model.unlinkSongFromTag(tagId: tag.id, linkId: row.linkId)
                }
                removingLink = nil
            }
        } message: {
            if let tag, let row = removingLink, row.entry == nil {
                Text("\"\(row.name)\" is already gone from the content list; this removes its row from \(tag.name).")
            } else if let tag, let row = removingLink {
                Text("The tag's link to \"\(row.name)\" goes; the song stays in the content list.")
            }
        }
        .alert("Delete tag?", isPresented: $confirmingDelete) {
            Button("Cancel", role: .cancel) {}
            Button("Delete", role: .destructive) {
                if let tag {
                    model.removeTag(tag)
                    // Unwinding to the tags list (the page's row lived
                    // there) - the navigation pop happens via the empty
                    // tag state below.
                }
            }
        } message: {
            if let tag {
                Text("The tag \(tag.name) and its song links go. The songs stay in the content list.")
            }
        }
        .overlay(alignment: .bottom) {
            if let event = model.uiEvent { EventBanner(message: event) }
        }
        .onChange(of: tag) { newValue in
            // The tag was removed while the page is open: fall back to
            // the tags list (the Android page's removed-while-open
            // fallback: the page's row lived there).
            if newValue == nil {
                nav.path.removeLast()
            }
        }
    }

    /// The save section's content (the Android page's "Save to NFC tag"
    /// section): the derived in-sync indicator (the tag's current system
    /// data against its last known per-uid saved memory - Unknown with no
    /// memory, InSync when it matches, Differs when it no longer does) +
    /// the two actions. "Scan to compare" is a READ - it works on
    /// read-only and blank tags (a blank card is a legitimate compare
    /// result), so its only gate is the phone's own NFC; "Write to NFC
    /// tag" keeps the write's live gates (over-cap payload, NFC missing).
    @ViewBuilder
    private func saveSection(for tag: Tag) -> some View {
        let data = LumiTagData(name: tag.name, songs: model.store.songNamesForTag(tag.id))
        let overCap = NdefCodec.messageByteLength(data) > NdefCodec.maxPayloadBytes
        let nfcOk = model.isNfcSupported
        let sync = tagSyncState(
            name: tag.name,
            songs: model.store.songNamesForTag(tag.id),
            memory: model.store.memoryForUid(tag.uid),
        )
        VStack(alignment: .leading, spacing: 8) {
            Text(syncCopy(sync))
                .font(.footnote).foregroundStyle(LumiPalette.onSurfaceVariant)
            HStack(spacing: 10) {
                saveActionButton("Scan to compare", systemImage: "magnifyingglass", enabled: nfcOk) {
                    _ = model.startCardCompare(tag)
                }
                saveActionButton("Write to NFC tag", systemImage: "nfc", enabled: !overCap && nfcOk) {
                    _ = model.startCardWrite(tag)
                }
            }
            if overCap {
                Text("The tag's data is too large for the tag payload (\(NdefCodec.messageByteLength(data)) of \(NdefCodec.maxPayloadBytes) bytes).")
                    .font(.footnote).foregroundStyle(LumiPalette.onSurfaceVariant)
            } else if !nfcOk {
                Text("This iPhone has no NFC reader, so the tag can't be written.")
                    .font(.footnote).foregroundStyle(LumiPalette.onSurfaceVariant)
            }
        }
    }

    /// The in-sync indicator's copy (the Android section's text).
    private func syncCopy(_ state: TagSyncState) -> String {
        switch state {
        case .inSync:
            "In sync - no changes since the last save."
        case .differs:
            "This tag's data differs from the saved tag."
        case .unknown:
            "Nothing saved or compared yet."
        }
    }

    /// One of the save section's capsule buttons (the page's peach-fill
    /// language, greyed out while its gate holds).
    private func saveActionButton(
        _ title: String,
        systemImage: String,
        enabled: Bool,
        action: @escaping () -> Void,
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Image(systemName: systemImage)
                Text(title).font(.subheadline).fontWeight(.semibold)
            }
            .foregroundStyle(enabled ? LumiPalette.onPrimaryContainer : LumiPalette.onSurfaceVariant)
            .padding(.horizontal, 14).padding(.vertical, 9)
            .background(Capsule().fill(enabled ? LumiPalette.primaryContainer : LumiPalette.surfaceContainerHigh))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }

    /// The drag reorder's settle (the Android page's onMove port): the
    /// new order of link ids, committed through the app model's
    /// permutation-checked write.
    private func moveSongs(from source: IndexSet, to destination: Int) {
        guard let tag else { return }
        var ids = model.tagRows(tag).map { $0.linkId }
        ids.move(fromOffsets: source, toOffset: destination)
        model.reorderTagSongs(tagId: tag.id, newOrder: ids)
    }

    private func beginRename() {
        guard let tag else { return }
        renameText = tag.name
        editingName = true
    }
}

/// One row of a tag's song list: the (live-resolved) name, the play/pause
/// button when the song is in the content list, the "song not found"
/// chip when it is not, and the per-song remove (LEFT of nothing - iOS
/// rows have no drag handle; the reorder is the list's own drag).
struct TagSongRowView: View {
    let row: TagSongRow
    let isCurrent: Bool
    let onRemove: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            if let entry = row.entry {
                VStack(alignment: .leading, spacing: 2) {
                    Text(entry.label).font(.subheadline).foregroundStyle(LumiPalette.onSurface)
                }
                Spacer()
                SongPlayButton(entry: entry, isCurrent: isCurrent)
            } else {
                HStack(spacing: 8) {
                    Image(systemName: "exclamationmark.triangle")
                        .foregroundStyle(LumiPalette.error)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(row.name).font(.subheadline).foregroundStyle(LumiPalette.onSurface)
                        Text("this song is no longer in the content list")
                            .font(.footnote).foregroundStyle(LumiPalette.onSurfaceVariant)
                    }
                }
                Spacer()
            }
            Button(action: onRemove) {
                Image(systemName: "xmark")
                    .font(.footnote.bold())
                    .foregroundStyle(LumiPalette.onSurfaceVariant)
                    .frame(width: 30, height: 30)
                    .background(Circle().fill(LumiPalette.surfaceContainerHigh))
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Remove \(row.name) from the tag")
        }
        .padding(.vertical, 6)
    }
}

/// The rename dialog (the Android app's TagEditDialog: the name is the
/// only stored detail; the UID is hardware-derived and read-only).
struct RenameDialog: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let tag: Tag
    let initial: String
    @State private var name: String = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("Tag name") {
                    TextField("Name", text: $name)
                }
                Section("ID") {
                    Text(tag.uid).font(.footnote).textSelection(.enabled)
                }
            }
            .navigationTitle("Edit tag")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        model.renameTag(tag, to: name)
                        dismiss()
                    }
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .onAppear { name = initial }
        }
    }
}

/// The in-app content picker (the Android page's AddSongPickerDialog,
/// reduced to a FLAT checkable list on iOS): the added folders' songs
/// first (in the folders' render order), the individual songs after -
/// duplicate labels offered once (the first stored copy) - where the
/// user CHECKS the songs to add and saves the selection at once with the
/// Save button (dimmed while nothing is checked). A song the tag already
/// plays is flagged and non-checkable. The commit order is the list's
/// render order (folders first, individual last) - NOT the order the
/// rows were checked, the Android picker's own commit-order rule.
struct AddSongPicker: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let tagId: Int
    @State private var checked: Set<Int> = [] // entry ids

    private var tag: Tag? { model.store.tagById(tagId) }
    private var onTagNames: Set<String> {
        tag.map { Set(model.store.songNamesForTag($0.id)) } ?? []
    }

    private struct Pickable: Identifiable {
        let id: Int // the entry id
        let label: String
        let already: Bool
    }

    private var picks: [Pickable] {
        // The tree's render order: the folders first with their nested
        // rows (each level's songs), the individual songs last (the
        // Android picker's commit order).
        var result: [Pickable] = []
        for folder in model.sortedFolders {
            for entry in model.folderSongs(folder) {
                result.append(Pickable(id: entry.id, label: entry.label, already: onTagNames.contains(entry.label)))
            }
        }
        for entry in model.sortedIndividualEntries {
            result.append(Pickable(id: entry.id, label: entry.label, already: onTagNames.contains(entry.label)))
        }
        // Duplicate labels offered once (the first stored copy).
        var seen = Set<String>()
        return result.filter { seen.insert($0.label).inserted }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                List {
                    ForEach(picks) { pick in
                        HStack {
                            Button {
                                toggle(pick)
                            } label: {
                                HStack {
                                    Image(systemName: pick.already ? "checkmark" : (checked.contains(pick.id) ? "checkmark.square" : "square"))
                                        .foregroundStyle(pick.already ? LumiPalette.onSurfaceVariant : LumiPalette.primary)
                                    Text(pick.label)
                                        .font(.subheadline)
                                        .foregroundStyle(pick.already ? LumiPalette.onSurfaceVariant : LumiPalette.onSurface)
                                    if pick.already {
                                        Text("already on this tag")
                                            .font(.footnote)
                                            .foregroundStyle(LumiPalette.onSurfaceVariant)
                                    }
                                }
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .disabled(pick.already)
                        }
                    }
                }
                HStack {
                    Button("Cancel") { dismiss() }
                    Spacer()
                    Button("Save") {
                        save()
                    }
                    .fontWeight(.semibold)
                    .disabled(checked.isEmpty)
                }
                .padding()
                .background(LumiPalette.background)
            }
            .navigationTitle("Add songs")
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    private func toggle(_ pick: Pickable) {
        if checked.contains(pick.id) {
            checked.remove(pick.id)
        } else {
            checked.insert(pick.id)
        }
    }

    private func save() {
        // The bulk save in the selection's commit order (the tree's
        // render order, not the check order).
        let order = picks.filter { $0.already == false }.map { $0.id }
        let selected = order.filter { checked.contains($0) }
        _ = model.linkEntriesToTag(tagId: tagId, entryIds: selected)
        dismiss()
    }
}

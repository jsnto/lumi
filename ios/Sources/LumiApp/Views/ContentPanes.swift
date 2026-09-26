// The content pane + the per-folder detail page + the song profile
// (the Android app's ContentPane + the folder detail sub-screen + the
// entry detail sub-screen).
//
// The pane is Folders-first: the Folders section leads (each row opens
// the folder's detail page - the rescan and the removal live on the
// page, not on the row), and the Individual content section (the
// unmarked rows - folder-imported songs stay OUT of it) carries the
// per-song play buttons. The sort control ("Sort by title" + A-Z/Z-A)
// sorts both sections by label (the shared content_sort_direction).
//
// The pickers: "Add content" is the ONLY file picker user; "Add folder"
// (on the Folders section's title row) uses the directory picker - two
// separate pickers, mirroring the Android app's two SAF launchers.

import SwiftUI
import UniformTypeIdentifiers

// MARK: - the content pane

struct ContentPane: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var nav: NavState
    @State private var addingFiles = false
    @State private var addingFolders = false
    @State private var deletingEntry: Entry? = nil

    var body: some View {
        ScrollView {
            // The sort control (the Android content pane's ContentSortRow).
            ContentSortRow()
                .padding(.horizontal, 16)
                .padding(.top, 8)

            // Folders first.
            SectionCard(title: "Folders", addLabel: "Add folder", onAdd: { addingFolders = true }) {
                if model.folders.isEmpty {
                    LumiCard {
                        Text("No folders yet. Add a folder to import its songs.")
                            .font(.subheadline).foregroundStyle(LumiPalette.onSurfaceVariant)
                    }
                } else {
                    LumiCard {
                        ForEach(model.sortedFolders) { folder in
                            // The row's OWN tap pushes the folder's page
                            // (a NavigationLink wrapper would swallow it -
                            // the menu rows' observed bug).
                            MenuRowView(
                                title: folder.label,
                                subtitle: "\(model.store.entriesForFolder(folder.id).count) song(s)",
                                systemImage: "folder",
                                onTap: { nav.path.append(Route.folderDetail(folder.id)) },
                            )
                            if folder.id != model.sortedFolders.last?.id { Divider() }
                        }
                    }
                }
            }

            // The individual content (the Folders-first split: the
            // unmarked rows only).
            SectionCard(title: "Individual content", addLabel: "Add content", onAdd: { addingFiles = true }) {
                if model.sortedIndividualEntries.isEmpty {
                    LumiCard {
                        Text("Nothing here yet. Add a song.")
                            .font(.subheadline).foregroundStyle(LumiPalette.onSurfaceVariant)
                    }
                } else {
                    LumiCard {
                        ForEach(model.sortedIndividualEntries) { entry in
                            HStack(spacing: 10) {
                                NavigationLink(value: Route.entryDetail(entry.id)) {
                                    Text(entry.label)
                                        .font(.subheadline).foregroundStyle(LumiPalette.onSurface)
                                        .lineLimit(1)
                                        .contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)
                                Spacer()
                                SongPlayButton(entry: entry, isCurrent: entry.label == model.engine.currentLabel && model.engine.status != .idle)
                                Button {
                                    deletingEntry = entry
                                } label: {
                                    Image(systemName: "trash")
                                        .font(.footnote.bold())
                                        .foregroundStyle(LumiPalette.onSurfaceVariant)
                                        .frame(width: 30, height: 30)
                                        .background(Circle().fill(LumiPalette.surfaceContainerHigh))
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel("Remove \(entry.label)")
                            }
                            .padding(.vertical, 6)
                            if entry.id != model.sortedIndividualEntries.last?.id { Divider() }
                        }
                    }
                }
            }
        }
        .background(LumiPalette.background)
        .navigationTitle("Content")
        .sheet(isPresented: $addingFiles) {
            DocumentPickerSheet(
                directory: false,
                onPick: { urls in
                    _ = model.addFileEntries(urls: urls)
                },
            )
        }
        .sheet(isPresented: $addingFolders) {
            DocumentPickerSheet(
                directory: true,
                onPick: { urls in
                    model.addFolderEntries(urls: urls)
                },
            )
        }
        .alert("Remove song from the content list?", isPresented: Binding(
            get: { deletingEntry != nil },
            set: { if !$0 { deletingEntry = nil } },
        )) {
            Button("Cancel", role: .cancel) { deletingEntry = nil }
            Button("Remove", role: .destructive) {
                if let entry = deletingEntry {
                    model.deleteEntry(entry)
                }
                deletingEntry = nil
            }
        } message: {
            if let entry = deletingEntry {
                Text("\"\(entry.label)\" leaves the content list. Tags that play it keep it as a \"song not found\" row.")
            }
        }
        .overlay(alignment: .bottom) {
            if let event = model.uiEvent { EventBanner(message: event) }
        }
    }
}

// MARK: - the folder detail page

/// One added folder, opened by tapping its row in the content pane. The
/// page shows the folder's details (label + the picked uri), its
/// imported songs as the picked folder's OWN TREE - the subfolders lead
/// at every level (each carrying its own nested levels), the songs
/// follow, each row carries the tree's left connector lines, each
/// subfolder row toggles the rows nested under it (show/hide), every
/// song row opens the song's profile with its own play/pause button -
/// a rescan on the Songs section's title row, and the danger zone.
///
/// The tree's per-level order is the shared content sort (the content
/// pane's own control, applied at each depth). The show/hide state is
/// held at the SCREEN level (NavState.collapsedFolders - the Android
/// page's top-level collapse state) so it survives navigation: a per-
/// pane @State would reset on every round trip.
struct FolderDetailPane: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var nav: NavState
    let folderId: Int
    @State private var confirmingRemoval = false

    private var folder: Folder? { model.store.folderById(folderId) }

    /// The page's tree: the folder's imported songs arranged as the
    /// picked folder's own structure (a song's payload is the file's
    /// full path; the segments below the folder's path are the song's
    /// subfolder path), every level in the shared sort's order.
    private var level: FolderTreeLevel? {
        guard let folder else { return nil }
        return folderTreeStructure(rootPath: folder.uri, entries: model.folderSongs(folder))
            .sortedTree(direction: model.contentSort)
    }

    /// The screen-level collapse keys for this folder (the subfolder
    /// paths from the picked root that are currently hidden).
    private var collapsed: Set<String> { nav.collapsedFolders[folderId] ?? [] }

    private func toggleCollapsed(_ key: String) {
        var keys = nav.collapsedFolders[folderId] ?? []
        if keys.contains(key) {
            keys.remove(key)
        } else {
            keys.insert(key)
        }
        nav.collapsedFolders[folderId] = keys
    }

    /// One rendered tree row: a subfolder (the show/hide toggle, its
    /// collapse key the path from the picked root) or a song (the body
    /// opens the profile, the play/pause button the separate region).
    private struct TreeRenderRow: Identifiable {
        let id: String
        let depth: Int
        let hasVisibleBranch: Bool
        let isLast: Bool
        let isFolder: Bool
        let label: String
        let key: String?
        let isCollapsed: Bool
        let entry: Entry?
    }

    /// The rows the tree renders, in render order (subfolders first,
    /// then the songs, at every depth - the sorted level's order):
    /// the count-driven last row carries no bottom spacing, so no
    /// connector line dangles past the tree.
    private var renderRows: [TreeRenderRow] {
        guard let level else { return [] }
        var rows: [TreeRenderRow] = []
        var remaining = level.visibleRowCount(collapsed: collapsed)
        func walk(_ level: FolderTreeLevel, depth: Int, prefix: String) {
            for node in level.folders {
                let key = prefix.isEmpty ? node.displayName : prefix + "/" + node.displayName
                let isCollapsed = collapsed.contains(key)
                let hasVisibleBranch = !isCollapsed
                    && (!node.children.folders.isEmpty || !node.children.songs.isEmpty)
                rows.append(TreeRenderRow(
                    id: "d:\(key)",
                    depth: depth,
                    hasVisibleBranch: hasVisibleBranch,
                    isLast: remaining == 1,
                    isFolder: true,
                    label: node.displayName,
                    key: key,
                    isCollapsed: isCollapsed,
                    entry: nil,
                ))
                remaining -= 1
                if !isCollapsed {
                    walk(node.children, depth: depth + 1, prefix: key)
                }
            }
            for entry in level.songs {
                rows.append(TreeRenderRow(
                    id: "e:\(entry.id)",
                    depth: depth,
                    hasVisibleBranch: false,
                    isLast: remaining == 1,
                    isFolder: false,
                    label: entry.label,
                    key: nil,
                    isCollapsed: false,
                    entry: entry,
                ))
                remaining -= 1
            }
        }
        walk(level, depth: 0, prefix: "")
        return rows
    }

    var body: some View {
        ScrollView {
            // The folder's details (the Android page's details card).
            SectionCard(title: "Folder details") {
                LumiCard {
                    if let folder {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(folder.label).font(.title2).bold().foregroundStyle(LumiPalette.onSurface)
                            Text(folder.uri).font(.footnote).foregroundStyle(LumiPalette.onSurfaceVariant).lineLimit(2)
                        }
                    } else {
                        Text("This folder was removed.")
                            .font(.subheadline).foregroundStyle(LumiPalette.onSurfaceVariant)
                    }
                }
            }

            // The sort control: the content pane's own title sort,
            // applied per level of the tree (the Android page's own
            // caption).
            ContentSortRow(caption: "Applies to the folders and the songs at each level below.")
                .padding(.horizontal, 12)

            // The imported songs (the tree rows). The rescan action sits
            // on the section's title row (re-walks the tree for new
            // songs).
            SectionCard(
                title: "Songs",
                trailingLabel: "Rescan",
                onTrailing: { if let folder { model.rescanFolder(folder) } },
            ) {
                LumiCard {
                    if let folder, renderRows.isEmpty {
                        Text("This folder has no songs.")
                            .font(.subheadline).foregroundStyle(LumiPalette.onSurfaceVariant)
                    } else {
                        // The tree's rows under a SINGLE non-spaced stack:
                        // the rows' own 12 pt bottom spacing is the only
                        // inter-row spacing, so it cannot sit between the
                        // rows and break the connector rails (each row
                        // draws its rails only inside its own frame).
                        VStack(alignment: .leading, spacing: 0) {
                            ForEach(renderRows) { row in
                                TreeRowFrame(
                                    depth: row.depth,
                                    hasVisibleBranch: row.hasVisibleBranch,
                                    isLast: row.isLast,
                                ) {
                                    if row.isFolder, let key = row.key {
                                        Button {
                                            toggleCollapsed(key)
                                        } label: {
                                            HStack(spacing: 6) {
                                                Image(
                                                    systemName: row.isCollapsed ? "chevron.right" : "chevron.down",
                                                )
                                                .font(.footnote).foregroundStyle(LumiPalette.primary)
                                                Image(systemName: "folder")
                                                    .foregroundStyle(LumiPalette.primary)
                                                Text(row.label).font(.subheadline).foregroundStyle(LumiPalette.onSurface)
                                            }
                                            .contentShape(Rectangle())
                                        }
                                        .buttonStyle(.plain)
                                        .accessibilityLabel(row.isCollapsed ? "Show \(row.label)" : "Hide \(row.label)")
                                    } else if let entry = row.entry {
                                        HStack(spacing: 10) {
                                            NavigationLink(value: Route.entryDetail(entry.id)) {
                                                Text(entry.label)
                                                    .font(.subheadline).foregroundStyle(LumiPalette.onSurface)
                                                    .lineLimit(1)
                                                    .contentShape(Rectangle())
                                            }
                                            .buttonStyle(.plain)
                                            Spacer()
                                            SongPlayButton(
                                                entry: entry,
                                                isCurrent: entry.label == model.engine.currentLabel && model.engine.status != .idle,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // The danger zone (the Android page's removal).
            SectionCard(title: "Delete folder from the app") {
                LumiCard(fill: LumiPalette.errorContainer.opacity(0.4)) {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Removing deletes the folder and its imported songs from the app. The music files on your device are not touched.")
                            .font(.footnote).foregroundStyle(LumiPalette.onSurfaceVariant)
                        Button(role: .destructive) {
                            confirmingRemoval = true
                        } label: {
                            Text("Delete folder")
                                .font(.subheadline).fontWeight(.semibold)
                                .foregroundStyle(.white)
                                .padding(.horizontal, 16).padding(.vertical, 8)
                                .background(Capsule().fill(LumiPalette.error))
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
        .background(LumiPalette.background)
        .navigationTitle(folder?.label ?? "Folder")
        .alert("Remove folder?", isPresented: $confirmingRemoval) {
            Button("Cancel", role: .cancel) {}
            Button("Remove", role: .destructive) {
                if let folder {
                    model.removeFolder(folder)
                }
                nav.path.removeLast()
            }
        } message: {
            if let folder {
                Text("\(folder.label) and its imported songs leave the content list. The files on your device are untouched.")
            }
        }
        .onChange(of: folder) { newValue in
            // Removed while open: fall back to the content pane (the
            // Android page's fallback).
            if newValue == nil {
                nav.path.removeLast()
            }
        }
        .overlay(alignment: .bottom) {
            if let event = model.uiEvent { EventBanner(message: event) }
        }
    }
}

// MARK: - the song profile (the entry detail page)

struct EntryDetailPane: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var nav: NavState
    let entryId: Int
    @State private var choosingTag = false

    private var entry: Entry? { model.store.entryById(entryId) }

    /// The tags that currently play this song (by name - the tag song's
    /// identity), live.
    private var assignedTags: [Tag] {
        guard let entry else { return [] }
        return model.tags.filter { tag in
            model.store.entriesForTag(tag.id).contains { $0.name == entry.label }
        }
    }

    var body: some View {
        ScrollView {
            if let entry {
                // The profile header (the Android song profile's fields,
                // best-effort: the metadata read settles to placeholders).
                SectionCard(title: "Song") {
                    LumiCard {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(entry.label).font(.title2).bold().foregroundStyle(LumiPalette.onSurface)
                            metaRow("Artist", model.entryMetadata?.artist)
                            metaRow("Album", model.entryMetadata?.album)
                            metaRow(
                                "Duration",
                                model.entryMetadata?.durationMs.map { formatDuration($0) } ?? "\u{2014}",
                            )
                        }
                    }
                }

                // The assign-to-tag section (the Android profile's
                // assign: the tags the song is currently on, the
                // "Choose a tag" option, and the "Scan a tag to assign").
                SectionCard(title: "Tags") {
                    LumiCard {
                        if assignedTags.isEmpty {
                            Text("Not on any tag yet.").font(.footnote).foregroundStyle(LumiPalette.onSurfaceVariant)
                                .padding(.bottom, 8)
                        } else {
                            ForEach(assignedTags) { tag in
                                HStack {
                                    Image(systemName: "tag").foregroundStyle(LumiPalette.primary)
                                    Text(tag.name).font(.subheadline).foregroundStyle(LumiPalette.onSurface)
                                    Spacer()
                                }
                                .padding(.vertical, 4)
                            }
                            Divider().padding(.vertical, 6)
                        }
                        Button {
                            choosingTag = true
                        } label: {
                            HStack(spacing: 8) {
                                Image(systemName: "plus")
                                Text("Choose a tag").font(.subheadline).fontWeight(.semibold)
                            }
                            .foregroundStyle(LumiPalette.onPrimaryContainer)
                            .padding(.horizontal, 14).padding(.vertical, 9)
                            .background(Capsule().fill(LumiPalette.primaryContainer))
                        }
                        .buttonStyle(.plain)
                        .disabled(model.tags.isEmpty)
                        if model.tags.isEmpty {
                            Text("Register a tag first (NFC tags pane).")
                                .font(.footnote).foregroundStyle(LumiPalette.onSurfaceVariant)
                        }
                        Button {
                            model.startTagScanForEntry(entry)
                        } label: {
                            HStack(spacing: 8) {
                                Image(systemName: "wifi")
                                Text("Scan a tag to assign").font(.subheadline).fontWeight(.semibold)
                            }
                            .foregroundStyle(LumiPalette.onSurfaceVariant)
                            .padding(.horizontal, 14).padding(.vertical, 9)
                            .background(Capsule().fill(LumiPalette.surfaceContainerHigh))
                        }
                        .buttonStyle(.plain)
                        .padding(.top, 8)
                    }
                }
            } else {
                LumiCard {
                    Text("This song was removed from the content list.")
                        .font(.subheadline).foregroundStyle(LumiPalette.onSurfaceVariant)
                }
            }
        }
        .background(LumiPalette.background)
        .navigationTitle(entry?.label ?? "Song")
        .onAppear {
            if let entry {
                model.selectEntry(entry) // the best-effort metadata read
            }
        }
        .sheet(isPresented: $choosingTag) {
            if let entry {
                ChooseTagSheet(entry: entry)
            }
        }
        .onChange(of: entry) { newValue in
            // Removed while open: fall back to its opener (the content
            // pane for v1 - the Android profile's fallback unwinds to
            // the pane the song was opened from).
            if newValue == nil {
                nav.path.removeLast()
            }
        }
        .overlay(alignment: .bottom) {
            if let event = model.uiEvent { EventBanner(message: event) }
        }
    }

    private func metaRow(_ title: String, _ value: String?) -> some View {
        HStack {
            Text(title).font(.footnote).foregroundStyle(LumiPalette.onSurfaceVariant)
            Spacer()
            Text(value ?? "\u{2014}")
                .font(.footnote).foregroundStyle(LumiPalette.onSurface)
        }
    }
}

/// The "Choose a tag" sheet (the Android profile's assign dialog): the
/// tags that already play the song are flagged and a pick is the
/// reported no-op.
struct ChooseTagSheet: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let entry: Entry

    private var already: Set<Int> {
        Set(model.tags.filter { tag in
            model.store.entriesForTag(tag.id).contains { $0.name == entry.label }
        }.map { $0.id })
    }

    var body: some View {
        NavigationStack {
            List(model.tags) { tag in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(tag.name).font(.subheadline).foregroundStyle(LumiPalette.onSurface)
                        if already.contains(tag.id) {
                            Text("already on this tag").font(.footnote).foregroundStyle(LumiPalette.onSurfaceVariant)
                        }
                    }
                    Spacer()
                    if already.contains(tag.id) {
                        Image(systemName: "checkmark").foregroundStyle(LumiPalette.onSurfaceVariant)
                    }
                }
                .contentShape(Rectangle())
                .onTapGesture {
                    if !already.contains(tag.id) {
                        _ = model.assignEntryToTag(tag: tag, entry: entry)
                        dismiss()
                    }
                }
            }
            .navigationTitle("Choose a tag")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }
}

// MARK: - the document pickers

/// The file/directory picker wrapper (the iOS counterparts of the
/// Android app's SAF launchers). The picked urls come back security-
/// scoped; the app model stores their bookmarks (the persistent grants).
struct DocumentPickerSheet: UIViewControllerRepresentable {
    let directory: Bool
    let onPick: ([URL]) -> Void
    @Environment(\.dismiss) private var dismiss

    func makeCoordinator() -> DocumentPickerCoordinator {
        DocumentPickerCoordinator(
            onPick: { [onPick] urls in onPick(urls) },
            dismiss: { [dismiss] in dismiss() },
        )
    }

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let controller: UIDocumentPickerViewController
        if directory {
            // The folder picker (the Android app's OpenDocumentTree
            // launcher): directories, multi-select.
            controller = UIDocumentPickerViewController(forOpeningDirectories: true)
        } else {
            // The file picker (the Android app's shared OpenDocument
            // launcher): the audio types, multi-select.
            controller = UIDocumentPickerViewController(
                forOpeningContentTypes: FolderAccess.audioFileTypes,
                allowsMultipleSelection: true,
            )
        }
        controller.allowsMultipleSelection = true
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ uiViewController: UIDocumentPickerViewController, context: Context) {}
}

/// The coordinator: delivers the picked urls to the app model and
/// dismisses the sheet.
final class DocumentPickerCoordinator: NSObject, UIDocumentPickerDelegate {
    let onPick: ([URL]) -> Void
    let dismiss: () -> Void

    init(onPick: @escaping ([URL]) -> Void, dismiss: @escaping () -> Void) {
        self.onPick = onPick
        self.dismiss = dismiss
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        let scoped = urls.map { url -> URL in
            _ = url.startAccessingSecurityScopedResource()
            return url
        }
        onPick(scoped)
        dismiss()
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        dismiss()
    }
}

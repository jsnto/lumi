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
    @State private var addingFiles = false
    @State private var addingFolders = false
    @State private var deletingEntry: Entry? = nil

    var body: some View {
        ScrollView {
            // The sort control (the Android content pane's ContentSortRow).
            HStack(spacing: 8) {
                Text("Sort by title")
                    .font(.subheadline).foregroundStyle(LumiPalette.onSurfaceVariant)
                Spacer()
                sortChip("A-Z", ascending: true)
                sortChip("Z-A", ascending: false)
            }
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
                            NavigationLink(value: Route.folderDetail(folder.id)) {
                                MenuRowView(
                                    title: folder.label,
                                    subtitle: "\(model.store.entriesForFolder(folder.id).count) song(s)",
                                    systemImage: "folder",
                                )
                            }
                            .buttonStyle(.plain)
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

    private func sortChip(_ label: String, ascending: Bool) -> some View {
        let direction: ContentSortDirection = ascending ? .ascending : .descending
        let selected = model.contentSort == direction
        return Button {
            model.setContentSort(direction)
        } label: {
            Text(label)
                .font(.footnote).fontWeight(.semibold)
                .foregroundStyle(selected ? LumiPalette.onPrimaryContainer : LumiPalette.onSurfaceVariant)
                .padding(.horizontal, 12).padding(.vertical, 6)
                .background(Capsule().fill(selected ? LumiPalette.primaryContainer : LumiPalette.surfaceContainerHigh))
        }
        .buttonStyle(.plain)
    }
}

// MARK: - the folder detail page

/// A folder page row: either a subfolder (the show/hide toggle - the
/// Android page's tree subfolder row) or a song (body opens the profile,
/// trailing play/pause). The rows carry the tree's indent; the v1 iOS
/// page keeps the show/hide state pane-local (the Android page holds it
// at the screen's top level so it survives navigation - a documented
/// v1 simplification).
struct FolderDetailPane: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var nav: NavState
    let folderId: Int
    @State private var hidden: Set<String> = [] // subfolder paths (collapsed)
    @State private var confirmingRemoval = false

    private var folder: Folder? { model.store.folderById(folderId) }

    /// The page's tree: the folder's imported songs grouped by their
    /// subfolder path (the payload's components under the folder's path),
    /// with the root's own songs at the top (the Android folder page's
    /// tree, reduced to one level of grouping - the v1 shape).
    private struct TreeLine: Identifiable {
        let id: String
        let isFolder: Bool
        let label: String
        var entry: Entry? = nil
    }

    private var lines: [TreeLine] {
        guard let folder else { return [] }
        let root = folder.uri.hasSuffix("/") ? String(folder.uri.dropLast()) : folder.uri
        func rel(_ payload: String) -> String? {
            guard payload.hasPrefix(root + "/") else { return nil }
            return String(payload.dropFirst(root.count + 1))
        }
        var lines: [TreeLine] = []
        var subfolders: [String: [Entry]] = [:]
        for entry in model.folderSongs(folder) {
            guard let relPath = rel(entry.payload) else {
                lines.append(TreeLine(id: "e:\(entry.id)", isFolder: false, label: entry.label, entry: entry))
                continue
            }
            let parts = relPath.split(separator: "/")
            if parts.count > 1 {
                let dir = parts.dropLast().joined(separator: "/")
                subfolders[dir, default: []].append(entry)
            } else {
                lines.append(TreeLine(id: "e:\(entry.id)", isFolder: false, label: entry.label, entry: entry))
            }
        }
        for dir in subfolders.keys.sorted() {
            let key = dir
            lines.append(TreeLine(id: "d:\(key)", isFolder: true, label: (dir as NSString).lastPathComponent))
            if !hidden.contains(key) {
                for entry in subfolders[dir]! {
                    lines.append(TreeLine(id: "e:\(entry.id)", isFolder: false, label: entry.label, entry: entry))
                }
            }
        }
        // The root's own songs first, then the subfolders (the Android
        // page's row order: subfolder rows first then the level's songs,
        // reduced here to the single-level v1 shape).
        return lines
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

            // The imported songs (the tree rows).
            SectionCard(
                title: "Songs",
                trailingLabel: "Rescan",
                onTrailing: { if let folder { model.rescanFolder(folder) } },
            ) {
                LumiCard {
                    if let folder, lines.isEmpty {
                        Text("This folder has no songs.")
                            .font(.subheadline).foregroundStyle(LumiPalette.onSurfaceVariant)
                    } else {
                        ForEach(lines) { line in
                            if line.isFolder {
                                Button {
                                    toggle(line.id.dropFirst(2))
                                } label: {
                                    HStack {
                                        Image(systemName: hidden.contains(line.id.dropFirst(2)) ? "chevron.right" : "chevron.down")
                                            .font(.footnote).foregroundStyle(LumiPalette.primary)
                                        Image(systemName: "folder")
                                            .foregroundStyle(LumiPalette.primary)
                                        Text(line.label).font(.subheadline).foregroundStyle(LumiPalette.onSurface)
                                    }
                                    .contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)
                                .padding(.vertical, 6)
                            } else if let entry = line.entry {
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
                                }
                                .padding(.vertical, 6)
                            }
                            if line.id != lines.last?.id { Divider() }
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

    private func toggle(_ path: Substring) {
        if hidden.contains(String(path)) {
            hidden.remove(String(path))
        } else {
            hidden.insert(String(path))
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

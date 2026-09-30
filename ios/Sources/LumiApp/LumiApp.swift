// The app entry point.
//
// The NFC always-listening session runs while the scene is active
// (CoreNFC's reader sessions are foreground-only - a platform-forced
// difference from the Android app, whose foreground dispatch also
// receives tags into the recent-apps state; see the ios/ README): a held
// tag re-detects when the app returns and resumes the paused list (reading
// overrides pause).

import SwiftUI
import LumiCore

@main
struct LumiApp: App {
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .preferredColorScheme(.light) // the settled "Sunlit amber" light scheme (v1)
        }
        .onChange(of: scenePhase) { phase in
            switch phase {
            case .active:
                // Foreground edge: re-arm the always-listening session and
                // re-seed the presence record from the carried active tag
                // (the Android reconfigureNfc re-seed).
                model.nfc.startListening()
                model.nfc.seedWatchedTag(uid: model.engine.state.activeTagUid)
            case .background, .inactive:
                // Backgrounding tears the session down WITHOUT a tag-loss
                // event (the Android onPause teardown): a held tag
                // re-detects on the return.
                model.nfc.stopListening(backgrounded: true)
            @unknown default:
                break
            }
        }
        .onAppear {
            // The app-start backfill (the Android backfillFolderEntries):
            // rows stored before the folder marker existed get it stamped
            // on a re-walk of every remembered folder.
            model.backfillFolderEntries()
        }
    }
}

/// The root: the front screen, with the settings stack pushed from it.
/// Navigation is value-based (NavigationLink(value:) anywhere in the
/// stack + the destinations here), so every pane pushes without shared
/// state, and the system back / in-pane chevron unwind one level at a
/// time (the Android app's BackHandler semantics, satisfied by the
/// NavigationStack's own pop).
struct RootView: View {
    @StateObject private var nav = NavState()
    @EnvironmentObject private var model: AppModel

    var body: some View {
        NavigationStack(path: $nav.path) {
            PlayerScreenView()
                .navigationDestination(for: Route.self) { route in
                    routeView(route)
                }
        }
        .environmentObject(nav)
        // The unified scan's in-progress dialog (the Android
        // SettingsScreen's scan AlertDialog, at the SCREEN level so it
        // overlays any pane - the card-write and compare scans are armed
        // from the tag page, the scan-to-assign from the song profile):
        // the title is always "Scan a tag" and the copy branches on what
        // the scan is for (the Android SettingsScreen's own branches).
        .alert(
            "Scan a tag",
            isPresented: Binding(get: { model.scanActive }, set: { if !$0 { model.stopTagScan() } }),
        ) {
            Button("Cancel", role: .cancel) { model.stopTagScan() }
        } message: {
            Text(scanDialogMessage)
        }
        // The tag naming dialog (the Android capture -> naming-dialog
        // flow, at the screen level too): a scan that settled on an
        // unknown tag published the capture with its prefill.
        .sheet(isPresented: Binding(get: { model.capture != nil }, set: { if !$0 { model.discardCapture() } })) {
            if let capture = model.capture {
                NamingDialog(capture: capture)
            }
        }
        // A registered tag the unified scan just resolved (the Android
        // pendingTagProfile -> tag detail page route, consumed here at
        // the screen level so the navigation works no matter which pane
        // armed the scan): open its page.
        .onChange(of: model.scannedTagId) { newValue in
            guard let newValue else { return }
            model.scannedTagId = nil
            nav.path.append(Route.tagDetail(newValue))
        }
    }

    /// The scan dialog's copy (the Android SettingsScreen's branches,
    /// in the Android's own priority order): the scan-to-assign names
    /// the song it will link, the compare asks for the tag's own tag to
    /// read what it carries, the card-write asks for it to save the
    /// tag's data to it, and the plain scan asks for any tag.
    private var scanDialogMessage: String {
        if let entryId = model.scanAssignEntryId, let entry = model.store.entryById(entryId) {
            return "Hold the back of the phone against any NFC tag to add “\(entry.label)” to it."
        }
        if model.cardCompareTargetId != nil {
            return "Hold the tag's own tag - the tag whose NFC ID matches the tag's - against the back of the phone to read what it carries. A tag with a different NFC ID is not recorded."
        }
        if model.cardWriteTargetId != nil {
            return "Hold the tag's own tag - the tag whose NFC ID matches the tag's - against the back of the phone to save this tag's data to it. A tag with a different NFC ID is not written."
        }
        return "Hold the back of the phone against any NFC tag…"
    }

    @ViewBuilder
    private func routeView(_ route: Route) -> some View {
        switch route {
        case .menu:
            SettingsMenuPane()
        case .playMode:
            PlayModePane()
        case .tags:
            TagsPane()
        case let .tagDetail(tagId):
            TagDetailPane(tagId: tagId)
        case .content:
            ContentPane()
        case .system:
            AdvancedPane()
        case let .folderDetail(folderId):
            FolderDetailPane(folderId: folderId)
        case let .entryDetail(entryId):
            EntryDetailPane(entryId: entryId)
        }
    }
}

/// One level of the settings navigation (the Android app's SettingsPane
/// sub-screens; system back and the in-pane arrow both unwind one level,
/// the NavigationStack's own pop).
enum Route: Hashable {
    case menu
    case playMode
    case tags
    case tagDetail(Int)
    case content
    case system
    case folderDetail(Int)
    case entryDetail(Int)
}

// The app entry point.
//
// The NFC always-listening session runs while the scene is active
// (CoreNFC's reader sessions are foreground-only - a platform-forced
// difference from the Android app, whose foreground dispatch also
// receives tags into the recent-apps state; see the ios/ README): a held
// tag re-detects when the app returns and resumes the paused list (reading
// overrides pause).

import SwiftUI

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

    var body: some View {
        NavigationStack(path: $nav.path) {
            PlayerScreenView()
                .navigationDestination(for: Route.self) { route in
                    routeView(route)
                }
        }
        .environmentObject(nav)
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
            SystemPane()
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

// The settings menu (the Android app's SettingsScreen menu): one MenuRow
// card per sub-screen - play mode, NFC tags, content, system - in the
// pinned order. Tapping a row pushes that setting's own sub-screen; the
// system back / in-pane chevron unwind one level (the NavigationStack's
// own pop - the Android app's BackHandler semantics).

import SwiftUI

struct SettingsMenuPane: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        ScrollView {
            VStack(spacing: 10) {
                LumiCard {
                    NavigationLink(value: Route.playMode) {
                        MenuRowView(
                            title: "Play mode",
                            subtitle: model.playMode == .continuous
                                ? "Keeps playing when a tag leaves"
                                : "Pauses when a tag leaves",
                        )
                    }
                    .buttonStyle(.plain)
                }

                LumiCard {
                    NavigationLink(value: Route.tags) {
                        MenuRowView(
                            title: "NFC tags",
                            subtitle: model.tags.isEmpty ? "No tags yet" : "\(model.tags.count) tag(s)",
                        )
                    }
                    .buttonStyle(.plain)
                }

                LumiCard {
                    NavigationLink(value: Route.content) {
                        MenuRowView(
                            title: "Content",
                            subtitle: "\(model.store.individualEntries.count) song(s), \(model.folders.count) folder(s)",
                        )
                    }
                    .buttonStyle(.plain)
                }

                LumiCard {
                    NavigationLink(value: Route.system) {
                        MenuRowView(
                            title: "System",
                            subtitle: "Tag re-read wait: \(model.nfcRereadSeconds) s",
                        )
                    }
                    .buttonStyle(.plain)
                }

                Text("lumi iOS - a port of the Android lumi player. See the ios/ README for the platform differences.")
                    .font(.footnote)
                    .foregroundStyle(LumiPalette.onSurfaceVariant)
                    .padding(.top, 8)
            }
            .padding(.vertical, 8)
        }
        .background(LumiPalette.background)
        .navigationTitle("Settings")
        .overlay(alignment: .bottom) {
            if let event = model.uiEvent { EventBanner(message: event) }
        }
    }
}

/// The play-mode sub-screen (the Android app's PlayModePane): the two
/// modes as rows, the pick persisted through the store (the Android app's
/// settings round trip).
struct PlayModePane: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        ScrollView {
            SectionCard(title: "Play mode") {
                LumiCard {
                    modeRow(.continuous, "Continuous", "Keeps playing after the tag leaves the reader range, until a different tag is read.")
                    Divider()
                    modeRow(.proximity, "Proximity", "Pauses when the tag leaves the reader range. Reading the tag again resumes the paused list.")
                }
            }
        }
        .background(LumiPalette.background)
        .navigationTitle("Play mode")
    }

    private func modeRow(_ mode: PlayMode, _ title: String, _ detail: String) -> some View {
        Button {
            model.setPlayMode(mode)
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.headline).foregroundStyle(LumiPalette.onSurface)
                    Text(detail).font(.footnote).foregroundStyle(LumiPalette.onSurfaceVariant)
                }
                Spacer()
                if model.playMode == mode {
                    Image(systemName: "checkmark")
                        .font(.body.bold())
                        .foregroundStyle(LumiPalette.primary)
                }
            }
            .contentShape(Rectangle())
            .padding(.vertical, 6)
        }
        .buttonStyle(.plain)
    }
}

/// The system sub-screen (the Android app's System page, reduced to what
/// this platform allows): the tag re-read wait is shown (it has no
/// settings UI on the Android app either - the value is persisted and
/// read by the proximity monitor), and the platform-forced differences
/// (screen lock / auto-lock / keep-screen-on / themes) are documented
/// rather than offered.
struct SystemPane: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        ScrollView {
            SectionCard(title: "NFC") {
                LumiCard {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Tag re-read wait").font(.headline).foregroundStyle(LumiPalette.onSurface)
                        Text("\(model.nfcRereadSeconds) seconds - how long a removed tag may be out of range before proximity mode pauses its list. (No in-app stepper on the Android app either; the value is persisted.)")
                            .font(.footnote).foregroundStyle(LumiPalette.onSurfaceVariant)
                    }
                }
            }
            SectionCard(title: "Platform notes") {
                LumiCard {
                    Text(
                        "The Android app's screen-lock (auto-lock after idle, keep screen on) and appearance settings are not available: iOS apps cannot lock the device screen or override the system theme. See the ios/ README.",
                    )
                    .font(.footnote).foregroundStyle(LumiPalette.onSurfaceVariant)
                }
            }
        }
        .background(LumiPalette.background)
        .navigationTitle("System")
    }
}

// The settings menu (the Android app's SettingsScreen menu): one MenuRow
// card per sub-screen - play mode, NFC tags, content, advanced options -
// in the pinned order. Tapping a row pushes that setting's own sub-screen; the
// system back / in-pane chevron unwind one level (the NavigationStack's
// own pop - the Android app's BackHandler semantics).

import SwiftUI
import LumiCore

struct SettingsMenuPane: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var nav: NavState

    var body: some View {
        ScrollView {
            VStack(spacing: 10) {
                // The menu rows: the tap is the row's OWN action (the app
                // pushes the destination through the shared nav state).
                // A NavigationLink wrapper here is the observed bug: the
                // link installs its programmatic destination into the
                // stack and swallows the row tap, so the row reads as
                // unresponsive.
                LumiCard {
                    MenuRowView(
                        title: "Play mode",
                        subtitle: model.playMode == .continuous
                            ? "Keeps playing when a tag leaves"
                            : "Pauses when a tag leaves",
                        onTap: { nav.path.append(Route.playMode) },
                    )
                }

                LumiCard {
                    MenuRowView(
                        title: "NFC tags",
                        subtitle: model.tags.isEmpty ? "No tags yet" : "\(model.tags.count) tag(s)",
                        onTap: { nav.path.append(Route.tags) },
                    )
                }

                LumiCard {
                    MenuRowView(
                        title: "Content",
                        subtitle: "\(model.store.individualEntries.count) song(s), \(model.folders.count) folder(s)",
                        onTap: { nav.path.append(Route.content) },
                    )
                }

                LumiCard {
                    MenuRowView(
                        title: "Advanced options",
                        subtitle: "Tag re-read wait: \(model.nfcRereadSeconds) s",
                        onTap: { nav.path.append(Route.system) },
                    )
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

/// The advanced options sub-screen (the Android app's Advanced options
/// page, reduced to what this platform allows): the tag re-read wait is
/// shown (it has no settings UI on the Android app either - the value is
/// persisted and read by the proximity monitor), the remembered-positions
/// stepper (the Android app's own section, ported), and the audio level
/// row opening its own sub-screen (the Android app's own page row, the
/// page's last setting row); the platform-forced differences (screen
/// lock / auto-lock / keep-screen-on / themes) are documented rather than
/// offered.
struct AdvancedPane: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var nav: NavState

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                SectionCard(title: "NFC") {
                    LumiCard {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Tag re-read wait").font(.headline).foregroundStyle(LumiPalette.onSurface)
                            Text("\(model.nfcRereadSeconds) seconds - how long a removed tag may be out of range before proximity mode pauses its list. (No in-app stepper on the Android app either; the value is persisted.)")
                                .font(.footnote).foregroundStyle(LumiPalette.onSurfaceVariant)
                        }
                    }
                }

                // The remembered-positions section (the Android app's own
                // section, ported one-for-one): the count of tags whose
                // last played position the LRU remembers (per tag, newest
                // first). The stepper changes the count one at a time;
                // each change persists through the store and takes effect
                // at the next stored position (a lower count evicts the
                // oldest remembered tags first, a higher one keeps the
                // rows already remembered). While a step's write is in
                // flight the buttons are disabled, so the next step always
                // computes from the value the write landed.
                SectionCard(title: "Remembered playback positions") {
                    LumiCard {
                        HStack(spacing: 12) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Songs to remember").font(.headline).foregroundStyle(LumiPalette.onSurface)
                                Text("How many songs' last played position lumi remembers (per tag, newest first).")
                                    .font(.footnote).foregroundStyle(LumiPalette.onSurfaceVariant)
                            }
                            Spacer()
                            HStack(spacing: 4) {
                                Button {
                                    model.setRememberedPositions(model.rememberedPositions - 1)
                                } label: {
                                    Image(systemName: "minus")
                                        .font(.body.bold())
                                        .foregroundStyle(LumiPalette.onPrimaryContainer)
                                        .frame(width: 32, height: 32)
                                        .background(Circle().fill(LumiPalette.primaryContainer))
                                }
                                .buttonStyle(.plain)
                                .disabled(model.rememberedPositionsWriteInFlight)
                                .accessibilityLabel("Decrease remembered positions")

                                Text("\(model.rememberedPositions)")
                                    .font(.title3).fontWeight(.bold)
                                    .foregroundStyle(LumiPalette.onSurface)
                                    .frame(minWidth: 24)
                                    .accessibilityLabel("Remembered positions")

                                Button {
                                    model.setRememberedPositions(model.rememberedPositions + 1)
                                } label: {
                                    Image(systemName: "plus")
                                        .font(.body.bold())
                                        .foregroundStyle(LumiPalette.onPrimaryContainer)
                                        .frame(width: 32, height: 32)
                                        .background(Circle().fill(LumiPalette.primaryContainer))
                                }
                                .buttonStyle(.plain)
                                .disabled(model.rememberedPositionsWriteInFlight)
                                .accessibilityLabel("Increase remembered positions")
                            }
                        }
                    }
                }

                // The audio level row (the Android app's own page row,
                // opening the audio level's own sub-screen from the page,
                // not the menu):
                // the settled subtitle (Off / Normalize to the target).
                LumiCard {
                    MenuRowView(
                        title: "Audio level",
                        subtitle: model.audioLevelMode == .off
                            ? "Off"
                            : "Normalize to \(Int(model.audioLevelTargetLufs)) LUFS",
                        onTap: { nav.path.append(Route.audioLevel) },
                    )
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
            .padding(16)
        }
        .background(LumiPalette.background)
        .navigationTitle("Advanced options")
    }
}

// The audio level sub-screen (the Android app's AudioLevelPane, ported
// one-for-one): the playback volume setting (Off - play every song at the
// level it was recorded at; Normalize - bring each measured song up to
// the target level, the very loud ones staying a little above it so they
// never clip), the target-level picker (-18 / -16 / -14 LUFS), and the
// measurement status with the explicit "Normalize now" action.

import SwiftUI
import LumiCore

struct AudioLevelPane: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                SectionCard(title: "Audio level") {
                    LumiCard {
                        modeRow("Off", "Play songs at the level they were recorded at.", selected: model.audioLevelMode == .off) {
                            model.setAudioLevelMode(.off)
                        }
                        Divider()
                        modeRow("Normalize", "Bring quiet songs up and loud songs down to the target level.", selected: model.audioLevelMode == .normalize) {
                            model.setAudioLevelMode(.normalize)
                        }
                    }
                }

                SectionCard(title: "Target level") {
                    LumiCard {
                        targetRow(-18.0, "Quieter.")
                        Divider()
                        targetRow(-16.0, "Balanced - the default.")
                        Divider()
                        targetRow(-14.0, "Louder.")
                    }
                }

                SectionCard(title: "Measured songs") {
                    LumiCard {
                        VStack(alignment: .leading, spacing: 6) {
                            HStack(spacing: 12) {
                                Image(systemName: "music.note")
                                    .foregroundStyle(LumiPalette.primary)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(model.loudnessStatus.text)
                                        .font(.headline).foregroundStyle(LumiPalette.onSurface)
                                    Text(statusDetail)
                                        .font(.footnote).foregroundStyle(LumiPalette.onSurfaceVariant)
                                }
                            }
                            // The explicit measurement pass (the pane's
                            // action): the settled peach fill, like the
                            // NFC tags pane's scan button (the Android
                            // app's own note).
                            Button {
                                model.normalizeNow()
                            } label: {
                                Text(model.normalizing ? "Measuring…" : "Normalize now")
                                    .font(.headline)
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 14)
                            }
                            .buttonStyle(.plain)
                            .background(LumiPalette.primaryContainer)
                            .clipShape(RoundedRectangle(cornerRadius: 28))
                            .disabled(model.normalizing)
                            .accessibilityLabel(model.normalizing ? "Measuring" : "Normalize now")
                        }
                        .padding(.vertical, 6)
                    }
                }
            }
            .padding(16)
        }
        .background(LumiPalette.background)
        .navigationTitle("Audio level")
        .overlay(alignment: .bottom) {
            if let event = model.uiEvent { EventBanner(message: event) }
        }
    }

    /// The status row's detail (the Android app's mode branches): OFF
    /// notes the measurements stay ready for when Normalize is turned on;
    /// Normalize notes the measured songs play at the target level.
    private var statusDetail: String {
        model.audioLevelMode == .off
            ? "Measurements stay ready for when you turn Normalize on."
            : "Songs that are measured play at the target level."
    }

    private func modeRow(_ title: String, _ detail: String, selected: Bool, select: @escaping () -> Void) -> some View {
        Button {
            select()
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.headline).foregroundStyle(LumiPalette.onSurface)
                    Text(detail).font(.footnote).foregroundStyle(LumiPalette.onSurfaceVariant)
                }
                Spacer()
                if selected {
                    Image(systemName: "checkmark")
                        .font(.body.bold())
                        .foregroundStyle(LumiPalette.primary)
                }
            }
            .contentShape(Rectangle())
            .padding(.vertical, 6)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
    }

    private func targetRow(_ targetLufs: Double, _ detail: String) -> some View {
        modeRow(
            "\(Int(targetLufs)) LUFS",
            detail,
            selected: model.audioLevelTargetLufs == targetLufs,
        ) {
            model.setAudioLevelTargetLufs(targetLufs)
        }
    }
}

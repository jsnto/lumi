// Shared view building blocks (the iOS counterparts of the Android app's
// Compose components: the MenuRow card, the SectionCard with its title-row
// add action, the setting rows). The settled "Sunlit amber" palette (the
// Android app's light-theme tokens) drives the colors.

import SwiftUI

/// The app palette (the Android app's settled "Sunlit amber" B token set,
/// ported 1:1 from ui/theme/Theme.kt's light scheme).
enum LumiPalette {
    static let primary = Color(hex: 0xF5761E)
    static let primaryContainer = Color(hex: 0xFFE3C8)
    static let onPrimaryContainer = Color(hex: 0x381502)
    static let onPrimary = Color(hex: 0x3A1502)
    static let secondary = Color(hex: 0x00897B)
    static let secondaryContainer = Color(hex: 0xC9EBE1)
    static let tertiary = Color(hex: 0x7C4DFF)
    static let tertiaryContainer = Color(hex: 0xE9E1FB)
    static let background = Color(hex: 0xFFFBF2)
    static let surface = Color(hex: 0xFFFBF2)
    static let surfaceContainerHigh = Color(hex: 0xEDE2CF)
    static let onSurface = Color(hex: 0x2B1708)
    static let onSurfaceVariant = Color(hex: 0x5B4B3F)
    static let error = Color(hex: 0xBA1A1A)
    static let errorContainer = Color(hex: 0xFFDAD6)
}

extension Color {
    init(hex: UInt32) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
        )
    }
}

/// A card panel (the Android app's section card / menu row chrome).
struct LumiCard<Content: View>: View {
    var fill: Color = LumiPalette.background
    var corner: CGFloat = 20
    @ViewBuilder var content: Content

    var body: some View {
        content
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(fill)
            .clipShape(RoundedRectangle(cornerRadius: corner))
            .padding(.horizontal, 12)
    }
}

/// A tappable card row (the Android app's MenuRow): a title, an optional
/// subtitle, and a trailing affordance.
struct MenuRowView: View {
    let title: String
    var subtitle: String? = nil
    var trailing: String? = nil
    var systemImage: String = "chevron.right"
    var onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.headline).foregroundStyle(LumiPalette.onSurface)
                    if let subtitle {
                        Text(subtitle).font(.subheadline).foregroundStyle(LumiPalette.onSurfaceVariant)
                    }
                }
                Spacer()
                if let trailing {
                    Text(trailing).font(.subheadline).foregroundStyle(LumiPalette.onSurfaceVariant)
                }
                Image(systemName: systemImage)
                    .foregroundStyle(LumiPalette.primary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .padding(.horizontal, 4)
        .padding(.vertical, 4)
    }
}

/// A section with a card-wrapped title row and an optional title-row add
/// action (the Android app's SectionCard: the "+" sits right-aligned on
/// the section title row, never pinned to the card bottom) plus an
/// optional trailing title-row action (the tag detail page's "Edit tag").
struct SectionCard<Content: View>: View {
    let title: String
    var addLabel: String? = nil
    var onAdd: (() -> Void)? = nil
    var trailingLabel: String? = nil
    var onTrailing: (() -> Void)? = nil
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Text(title).font(.title3).fontWeight(.bold).foregroundStyle(LumiPalette.onSurface)
                Spacer()
                if let trailingLabel, let onTrailing {
                    Button(action: onTrailing) {
                        Text(trailingLabel)
                            .font(.subheadline).fontWeight(.semibold)
                            .foregroundStyle(LumiPalette.onPrimaryContainer)
                            .padding(.horizontal, 12).padding(.vertical, 6)
                            .background(Capsule().fill(LumiPalette.primaryContainer))
                    }
                    .buttonStyle(.plain)
                }
                if let addLabel, let onAdd {
                    Button(action: onAdd) {
                        Image(systemName: "plus")
                            .font(.subheadline).fontWeight(.bold)
                            .foregroundStyle(LumiPalette.onPrimaryContainer)
                            .frame(width: 30, height: 30)
                            .background(Circle().fill(LumiPalette.primaryContainer))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(addLabel)
                }
            }
            .padding(.horizontal, 4)
            content
        }
        .padding(.top, 12)
    }
}

/// The transient event banner (the Android app's Toast).
struct EventBanner: View {
    let message: String

    var body: some View {
        Text(message)
            .font(.subheadline)
            .foregroundStyle(LumiPalette.onSurface)
            .padding(.horizontal, 16).padding(.vertical, 10)
            .background(LumiPalette.surfaceContainerHigh)
            .clipShape(RoundedRectangle(cornerRadius: 14))
            .padding(.horizontal, 24)
    }
}

/// A song row's play/pause button, live off the shared playback state
/// (the Android app's song-row play/pause: the current song TOGGLES, a
/// non-current song starts).
struct SongPlayButton: View {
    @EnvironmentObject private var model: AppModel
    let entry: Entry
    let isCurrent: Bool

    var body: some View {
        let label = entry.label
        let playingHere = isCurrent && model.engine.status == .playing
        Button {
            if isCurrent {
                model.toggleCurrentPlayback()
            } else {
                model.playEntry(entry)
            }
        } label: {
            Image(systemName: playingHere ? "pause.fill" : "play.fill")
                .font(.title3)
                .foregroundStyle(LumiPalette.onPrimaryContainer)
                .frame(width: 36, height: 36)
                .background(Circle().fill(LumiPalette.primaryContainer))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(playingHere ? "Pause \(label)" : "Play \(label)")
    }
}

/// The shared navigation state (the settings stack's path): the panes
/// push value-based routes, and the detail pages' removed-while-open
/// fallback pops programmatically through it.
final class NavState: ObservableObject {
    @Published var path: [Route] = []
}

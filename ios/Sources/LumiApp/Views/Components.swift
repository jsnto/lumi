// Shared view building blocks (the iOS counterparts of the Android app's
// Compose components: the MenuRow card, the SectionCard with its title-row
// add action, the setting rows). The settled "Sunlit amber" palette (the
// Android app's light-theme tokens) drives the colors.

import SwiftUI
import LumiCore

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
    /// The loaded-entry accent (the Android app's EntryPresentation AUDIO
    /// token): the front screen's visual tints to it while a song is
    /// loaded, and falls back to the theme's primary while idle.
    static let audioAccent = Color(hex: 0xFF8A3D)
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
    /// The folder tree's subfolder collapse keys, per folder id - held
    /// HERE (the screen level, the Android folder page's top-level
    /// collapse state) so the show/hide state SURVIVES navigation: a
    /// per-pane @State would reset on every round trip. A subfolder's
    /// key is its PATH from the picked root (sibling names are distinct,
    /// so the path names one node).
    @Published var collapsedFolders: [Int: Set<String>] = [:]
}

/// A section header for List-based panes (the pane equivalent of the
/// SectionCard's title row): the title left, an optional trailing action
/// (the section's "Edit" pill or "+" circle) right.
public struct ListSectionHeader: View {
    let title: String
    var addLabel: String? = nil
    var onAdd: (() -> Void)? = nil
    var trailingLabel: String? = nil
    var onTrailing: (() -> Void)? = nil

    public init(
        _ title: String,
        addLabel: String? = nil,
        onAdd: (() -> Void)? = nil,
        trailingLabel: String? = nil,
        onTrailing: (() -> Void)? = nil,
    ) {
        self.title = title
        self.addLabel = addLabel
        self.onAdd = onAdd
        self.trailingLabel = trailingLabel
        self.onTrailing = onTrailing
    }

    public var body: some View {
        HStack(spacing: 8) {
            Text(title)
                .font(.title3)
                .fontWeight(.bold)
                .foregroundStyle(LumiPalette.onSurface)
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
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// The content sort control (the Android app's ContentSortRow): the
/// "Sort by title" label + the A-Z / Z-A chips. The pick persists
/// through the shared content sort setting.
public struct ContentSortRow: View {
    @EnvironmentObject private var model: AppModel
    /// An optional caption under the chips (the folder page's own copy).
    var caption: String? = nil

    public init(caption: String? = nil) {
        self.caption = caption
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                Text("Sort by title")
                    .font(.subheadline).foregroundStyle(LumiPalette.onSurfaceVariant)
                Spacer()
                sortChip("A-Z", ascending: true)
                sortChip("Z-A", ascending: false)
            }
            if let caption {
                Text(caption)
                    .font(.footnote).foregroundStyle(LumiPalette.onSurfaceVariant)
            }
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

/// One tree row with the tree's left connector lines - the port of the
/// Android app's TreeRowFrame: a 1 pt vertical rail in each of the
/// levels' rail columns above the row (every rail spans the row's full
/// frame height, so the rails run continuously down a column from the
/// first nested row to the last), plus - for a row nested at least one
/// level - a short horizontal stub from the innermost rail out to the
/// row. A folder row with displayed children also carries its children's
/// rail from its own vertical center down (its first child's row picks
/// the line up at its top), so each branch's line starts where its
/// folder row sits. The row's content indents one 16 pt rail step per
/// level (the rails occupy the indent), and the row's 12 pt bottom
/// spacing (the tree's rows sit in a single non-spaced stack, so the
/// spacing is the rows' only inter-row spacing) lives INSIDE the frame;
/// the tree's last row carries none, so no rail dangles past the tree.
public struct TreeRowFrame<Content: View>: View {
    private let depth: Int
    private let hasVisibleBranch: Bool
    private let isLast: Bool
    private let content: Content

    private static let railStep: CGFloat = 16
    private static let railWidth: CGFloat = 1
    private static let stubWidth: CGFloat = 8
    private static let bottomSpacing: CGFloat = 12

    public init(
        depth: Int,
        hasVisibleBranch: Bool,
        isLast: Bool,
        @ViewBuilder content: () -> Content,
    ) {
        self.depth = depth
        self.hasVisibleBranch = hasVisibleBranch
        self.isLast = isLast
        self.content = content()
    }

    public var body: some View {
        let spacing: CGFloat = isLast ? 0 : Self.bottomSpacing
        content
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.leading, CGFloat(depth) * Self.railStep)
            .padding(.bottom, spacing)
            .background(
                TreeRails(
                    depth: depth,
                    hasVisibleBranch: hasVisibleBranch,
                    bottomSpacing: spacing,
                    railStep: Self.railStep,
                    stubWidth: Self.stubWidth,
                )
                    .stroke(style: StrokeStyle(lineWidth: Self.railWidth))
                    .foregroundStyle(LumiPalette.onSurfaceVariant.opacity(0.5)),
            )
    }
}

/// The tree row's connector lines (the Android TreeRowFrame's
/// drawBehind, ported to a Shape over the row's full frame).
private struct TreeRails: Shape {
    var depth: Int
    var hasVisibleBranch: Bool
    var bottomSpacing: CGFloat
    var railStep: CGFloat
    var stubWidth: CGFloat

    func path(in rect: CGRect) -> Path {
        var path = Path()
        // The row's content centers in the frame minus its bottom
        // spacing (the spacing is below the content, inside the frame).
        let contentCenterY = (rect.height - bottomSpacing) / 2
        // The vertical rails of the levels above this row: each spans
        // the frame's full height, so the line is continuous through the
        // row and into the next row's frame.
        for level in 0..<depth {
            let x = CGFloat(level) * railStep
            path.move(to: CGPoint(x: x, y: 0))
            path.addLine(to: CGPoint(x: x, y: rect.height))
        }
        // The row's own branch rail (a folder row's children's rail):
        // from the row's vertical center to the frame's bottom - the
        // first child's row continues it from its top.
        if hasVisibleBranch {
            let x = CGFloat(depth) * railStep
            path.move(to: CGPoint(x: x, y: contentCenterY))
            path.addLine(to: CGPoint(x: x, y: rect.height))
        }
        // The horizontal stub from the innermost rail to the row itself.
        if depth > 0 {
            let x = CGFloat(depth - 1) * railStep
            path.move(to: CGPoint(x: x, y: contentCenterY))
            path.addLine(to: CGPoint(x: x + stubWidth, y: contentCenterY))
        }
        return path
    }
}

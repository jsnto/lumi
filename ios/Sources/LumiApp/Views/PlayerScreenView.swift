// The front screen (the Android app's PlayerScreen): the animated blob
// visual, the wordmark, the current-song line, the big play/pause and
// stop controls, and the settings entry. The screen's overlay never
// touches playback state directly - every action goes through the app
// model (the Android app's soft-lock overlay keeps the same split).

import SwiftUI

struct PlayerScreenView: View {
    @EnvironmentObject private var model: AppModel

    /// The visual's accent: the loaded entry's color (the audio accent -
    /// the Android app's EntryPresentation AUDIO), or the theme's primary
    /// while nothing is loaded (the idle visual follows the theme's own
    /// brand color, not a hard-coded accent).
    private var visualColor: Color {
        model.engine.currentLabel == nil ? LumiPalette.primary : LumiPalette.audioAccent
    }

    var body: some View {
        ZStack {
            LumiPalette.background.ignoresSafeArea()

            VStack(spacing: 0) {
                HStack {
                    Spacer()
                    NavigationLink(value: Route.menu) {
                        Image(systemName: "gearshape")
                            .font(.title2)
                            .foregroundStyle(LumiPalette.onSurfaceVariant)
                            .frame(width: 44, height: 44)
                    }
                    .accessibilityLabel("Settings")
                    .padding(.trailing, 8)
                }

                Spacer()

                // The front screen's living visual (the Android app's
                // PlayingVisual): a soft halo, a faint ring, and the core
                // with a sun-like sheen and the app's own logo - it pulses
                // and spins while something plays, and sits still (the
                // theme's primary accent) while idle.
                PlayingVisual(active: model.engine.status == .playing, color: visualColor) {
                    // The app's lumi logo (the "sunny record face") - the
                    // face sits in the blob's own tint, so the logo
                    // follows the visual's accent.
                    LumiLogoView(accent: visualColor, size: 124)
                }

                Spacer()

                VStack(spacing: 6) {
                    Text("lumi")
                        .font(.system(size: 34, weight: .extraBold, design: .rounded))
                        .foregroundStyle(LumiPalette.onSurface)
                    Text(statusLine)
                        .font(.subheadline)
                        .foregroundStyle(LumiPalette.onSurfaceVariant)
                        .lineLimit(2)
                        .multilineTextAlignment(.center)
                }

                Spacer()

                HStack(spacing: 40) {
                    // Stop: ends the session (the Android app's stop).
                    Button {
                        model.stopPlayback()
                    } label: {
                        Image(systemName: "stop.circle")
                            .font(.system(size: 44))
                            .foregroundStyle(LumiPalette.onSurfaceVariant)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Stop")

                    // The big play/pause: a paused current song RESUMES
                    // the same song (no fresh start), a playing one
                    // pauses in place.
                    Button {
                        model.toggleCurrentPlayback()
                    } label: {
                        Image(systemName: model.engine.status == .playing ? "pause.circle.fill" : "play.circle.fill")
                            .font(.system(size: 84))
                            .foregroundStyle(LumiPalette.primary)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(model.engine.status == .playing ? "Pause" : "Play")

                    // Next: the next song of the current list (looping).
                    Button {
                        model.engine.next()
                    } label: {
                        Image(systemName: "forward.circle")
                            .font(.system(size: 44))
                            .foregroundStyle(LumiPalette.onSurfaceVariant)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Next")
                }
                .padding(.bottom, 24)
            }
        }
        .overlay(alignment: .bottom) {
            if let event = model.uiEvent {
                EventBanner(message: event)
                    .padding(.bottom, 8)
                    .transition(.opacity)
            }
        }
        .overlay(alignment: .bottom) {
            if model.nfcUnavailable {
                // The NFC prompt (the Android app's refreshNfcState
                // radio-missing case).
                Text("This iPhone has no NFC reader, so tag play is off. Add songs manually from the settings.")
                    .font(.footnote)
                    .foregroundStyle(LumiPalette.onSurfaceVariant)
                    .padding(.horizontal, 20).padding(.vertical, 8)
                    .background(LumiPalette.secondaryContainer)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .padding(.bottom, 8)
            }
        }
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var statusLine: String {
        switch model.engine.status {
        case .idle:
            return "Hold an NFC tag on the back of the iPhone, or add songs from the settings."
        case .playing:
            return model.engine.currentLabel ?? "Playing."
        case .paused:
            return (model.engine.currentLabel ?? "Paused") + " - paused"
        }
    }
}

/// The big animated blob that "dances" while something is playing (the
/// Android app's PlayingVisual, ported one-for-one): a soft halo (radial
/// shading), a faint ring, and the core with a sun-like highlight. The
/// pulse (0.94 -> 1.06 over 900 ms) is kept exactly; while [active] the
/// core slowly turns (the off-center sheen carries the spin) and its
/// content bobs gently with the music; on pause or stop both settle back
/// to rest. Every shape is a CIRCLE, so no square or box shows around the
/// blob (the Android app's square-free fix - a gradient rectangle leaves
/// the brush's mirrored fill in the corners).
struct PlayingVisual<Content: View>: View {
    var active: Bool
    var color: Color
    @ViewBuilder var content: () -> Content

    @State private var pulse: CGFloat = 0.94
    @State private var spin: Double = 0
    @State private var bob: CGFloat = 0

    var body: some View {
        ZStack {
            // Soft halo: the blob's tint fading out (0.24 -> 0.10 ->
            // clear), drawn as a circle (296 pt, the Android app's own
            // size).
            Circle()
                .fill(
                    RadialGradient(
                        colors: [color.opacity(0.24), color.opacity(0.10), .clear],
                        center: .center,
                        startRadius: 0,
                        endRadius: 148,
                    ),
                )
                .frame(width: 296, height: 296)
            // Faint ring just outside the core.
            Circle()
                .fill(color.opacity(0.16))
                .frame(width: 224, height: 224)
            // The turning core: the accent color, the sun-like sheen
            // (drawn as a circle, for the same square-free reason), and
            // the logo. The whole core rotates while playing - the
            // off-center sheen makes the spin readable on the otherwise
            // symmetric face.
            ZStack {
                Circle().fill(color)
                // Sun-like highlight toward the upper left (the Android
                // app's own off-center: 0.38/0.32 of the core, a 0.8 core
                // radius).
                Circle()
                    .fill(
                        RadialGradient(
                            colors: [.white.opacity(0.38), .clear],
                            center: UnitPoint(x: 0.38, y: 0.32),
                            startRadius: 0,
                            endRadius: 160,
                        ),
                    )
                    .frame(width: 200, height: 200)
                // The logo's gentle up-and-down while the music runs (it
                // settles to rest when the playback pauses or stops).
                content()
                    .offset(y: active ? bob : 0)
            }
            .frame(width: 200, height: 200)
            .rotationEffect(.degrees(active ? spin : 0))
            .shadow(color: .black.opacity(0.3), radius: 18)
        }
        .frame(width: 260, height: 260)
        .scaleEffect(active ? pulse : 1)
        .onChange(of: active) { isActive in
            // The motion is driven by the state transition itself (the
            // app always launches idle, so an onAppear-time arming would
            // never run): idle -> playing arms the three repeats, any
            // stop settles everything back with the default animation.
            if isActive {
                withAnimation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true)) {
                    pulse = 1.06
                }
                withAnimation(.linear(duration: 8).repeatForever(autoreverses: false)) {
                    spin = 360
                }
                withAnimation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true)) {
                    bob = -6
                }
            } else {
                withAnimation(.default) {
                    pulse = 0.94
                    spin = 0
                    bob = 0
                }
            }
        }
    }
}

/// The app's lumi logo: the "sunny record face" - the launcher icon's own
/// concentric design (the cream face, the accent spindle), drawn flat as
/// circles so no square or box shows around it. [accent] is the color the
/// face sits in (the launcher's orange, the front screen's visual's
/// accent), so the same design reads in both places (the Android app's
/// LumiLogo: the face is ~0.61 of the full size, the spindle ~0.29 of the
/// face).
struct LumiLogoView: View {
    /// The record face's cream (the launcher icon's own #FFF3E0).
    static let face = Color(hex: 0xFFF3E0)

    var accent: Color
    var size: CGFloat = 124

    var body: some View {
        ZStack {
            Circle()
                .fill(Self.face)
                .frame(width: size * 0.61, height: size * 0.61)
            Circle()
                .fill(accent)
                .frame(width: size * 0.61 * 0.29, height: size * 0.61 * 0.29)
        }
        .frame(width: size, height: size)
    }
}

// The front screen (the Android app's PlayerScreen): the animated blob
// visual, the wordmark, the current-song line, the big play/pause and
// stop controls, and the settings entry. The screen's overlay never
// touches playback state directly - every action goes through the app
// model (the Android app's soft-lock overlay keeps the same split).

import SwiftUI

struct PlayerScreenView: View {
    @EnvironmentObject private var model: AppModel
    @State private var blobScale: CGFloat = 0.9

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

                // The blob: the front screen's living visual (the Android
                // app's animated idle blob). A soft radial glow that
                // breathes while playback is active.
                ZStack {
                    Circle()
                        .fill(
                            RadialGradient(
                                colors: [LumiPalette.primary.opacity(0.85), LumiPalette.primary.opacity(0.15)],
                                center: .center,
                                startRadius: 8,
                                endRadius: 120,
                            ),
                        )
                    Image(systemName: "music.note")
                        .font(.system(size: 44, weight: .bold))
                        .foregroundStyle(LumiPalette.onPrimary)
                }
                .frame(width: 160, height: 160)
                .scaleEffect(model.engine.status == .playing ? blobScale : 0.9)
                .animation(
                    model.engine.status == .playing
                        ? .easeInOut(duration: 1.4).repeatForever(autoreverses: true)
                        : .default,
                    value: model.engine.status,
                )
                .onAppear {
                    guard model.engine.status == .playing else { return }
                    withAnimation(.easeInOut(duration: 1.4).repeatForever(autoreverses: true)) {
                        blobScale = 1.06
                    }
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

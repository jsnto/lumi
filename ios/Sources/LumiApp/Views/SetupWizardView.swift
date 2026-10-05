// The first-time setup wizard (the Android app's SetupWizard, ported with
// one platform-forced omission): the flow a fresh install (cleared app
// data) lands on before the player. Importing a music folder is the one
// required step - nothing works without a library - and the one optional
// item this platform supports (playing from an NFC tag) is offered with a
// skip. The Android app's screen-lock step has no iOS route (iOS apps
// cannot register a device admin), so the flow is Welcome -> Music ->
// NFC -> AllSet. The wizard ends into the app (the player's front screen,
// or the settings menu's NFC tags pane when the tag setup was picked).
//
// The completion is the app model's [AppModel.setupComplete] (the
// `setup_complete` setting): the host shows the wizard while it is false,
// so a fresh install always lands here and a returned user never does.
//
// The flow state (the current step) is local to this view - a fresh
// install's process is short-lived, and the step is always Welcome when
// the app starts (the setup gate, not the step, is what is persisted).

import SwiftUI
import LumiCore

struct SetupWizardView: View {
    @EnvironmentObject private var model: AppModel

    /// The wizard's own step (local: the persisted gate, not the step, is
    /// what survives a launch).
    @State private var step: WizardStep = .welcome
    @State private var folderPickInFlight = false
    @State private var showingFolderPicker = false

    var body: some View {
        ZStack {
            // The wizard's own gradient (the Android app's: the
            // background fading into the primary container).
            LinearGradient(
                colors: [
                    LumiPalette.background,
                    LumiPalette.primaryContainer.opacity(0.65),
                    LumiPalette.primaryContainer,
                ],
                startPoint: .top,
                endPoint: .bottom,
            )
            .ignoresSafeArea()

            VStack(spacing: 0) {
                Spacer()
                // The app's own logo heads the wizard (square-free, like
                // the launcher's icon).
                LumiLogoView(accent: LumiPalette.primary, size: 96)
                Spacer().frame(height: 20)

                switch step {
                case .welcome:
                    welcome
                case .music:
                    music
                case .nfc:
                    nfc
                case .allSet:
                    allSet
                }

                Spacer()
            }
            .padding(24)
        }
        .sheet(isPresented: $showingFolderPicker) {
            // The music folder's picker: the app's existing folder
            // import (the same picker the content pane's "Add folder"
            // uses), so the imported folder and its songs land in the
            // library the same way.
            DocumentPickerSheet(
                directory: true,
                onPick: { urls in
                    folderPickInFlight = false
                    if !urls.isEmpty {
                        model.addFolderEntries(urls: urls)
                    }
                },
            )
        }
        .overlay(alignment: .bottom) {
            if let event = model.uiEvent { EventBanner(message: event) }
        }
    }

    // MARK: - the steps

    private var welcome: some View {
        VStack(spacing: 16) {
            WizardHeading(
                title: "Welcome to lumi",
                body: "lumi plays the music on your phone, with or without a tag. It takes a minute to set up.",
            )
            WizardPrimaryButton(label: "Let's set up") {
                step = .music
            }
        }
    }

    /// The one required step: a music folder to play from. The pick goes
    /// through the app's existing folder import, so the imported folder
    /// and its songs land in the library the same way. The step stays
    /// until a folder is in.
    private var music: some View {
        VStack(spacing: 16) {
            if model.folders.isEmpty {
                WizardHeading(
                    title: "Add your music",
                    body: "lumi plays from a folder on your phone. Choose the folder that holds your songs.",
                )
                WizardPrimaryButton(label: folderPickInFlight ? "Scanning your folder..." : "Choose a music folder") {
                    folderPickInFlight = true
                    showingFolderPicker = true
                }
            } else {
                WizardHeading(
                    title: "Add your music",
                    body: "Your music is in - you can add more folders any time.",
                )
                folderCard
                WizardPrimaryButton(label: "Next") {
                    step = .nfc
                }
            }
        }
    }

    private var folderCard: some View {
        HStack(spacing: 12) {
            Image(systemName: "folder.fill")
                .foregroundStyle(LumiPalette.primary)
            VStack(alignment: .leading, spacing: 2) {
                Text(model.folders.first?.label ?? "Your music folder")
                    .font(.headline)
                Text(model.entries.isEmpty
                    ? "No songs found yet - you can add more any time."
                    : "\(model.entries.count) \(model.entries.count == 1 ? "song" : "songs") imported.")
                    .font(.footnote)
                    .foregroundStyle(LumiPalette.onSurfaceVariant)
            }
            Spacer()
        }
        .padding(14)
        .background(LumiPalette.surface)
        .clipShape(RoundedRectangle(cornerRadius: 20))
    }

    /// The optional NFC tag step: set it up now (which completes the
    /// setup and lands on the settings menu's NFC tags pane - the
    /// Android app's own openNfcTags route), or skip it.
    private var nfc: some View {
        VStack(spacing: 16) {
            WizardHeading(
                title: "Play with a tag",
                body: "Hold an NFC tag to the back of your phone to start and pause music - no tapping needed.",
            )
            WizardPrimaryButton(label: "Set up now") {
                finish(openNfcTags: true)
            }
            Button("Skip") {
                step = .allSet
            }
            .buttonStyle(.plain)
            .font(.headline)
            .foregroundStyle(LumiPalette.onPrimaryContainer)
            .padding(.horizontal, 20).padding(.vertical, 10)
            .background(Capsule().fill(LumiPalette.primaryContainer))
            .accessibilityLabel("Skip")
        }
    }

    private var allSet: some View {
        VStack(spacing: 16) {
            WizardHeading(
                title: "You're all set!",
                body: "Your music is in. You can change everything later in the settings menu.",
            )
            WizardPrimaryButton(label: "Open lumi") {
                finish(openNfcTags: false)
            }
        }
    }

    /// Finishing: record the completion (it never un-does itself) - the
    /// gate flips and the host takes over with the player's front screen.
    /// (The openNfcTags route - pushing the settings menu's NFC tags pane
    /// directly from the wizard - stays available as the app's own
    /// navigation once the player is up.)
    private func finish(openNfcTags: Bool) {
        model.completeSetup()
        if openNfcTags {
            model.wizardOpenNfcTags = true
        }
    }
}

/// The wizard's screens, one state each (the Android app's SetupStep,
/// minus the platform-forced screen-lock step).
private enum WizardStep {
    case welcome
    case music
    case nfc
    case allSet
}

/// The wizard's heading + body copy (the Android app's WizardHeading).
private struct WizardHeading: View {
    var title: String
    var body: String

    var body: some View {
        VStack(spacing: 10) {
            Text(title)
                .font(.system(size: 28, weight: .extraBold))
                .foregroundStyle(LumiPalette.onSurface)
                .multilineTextAlignment(.center)
            Text(body)
                .font(.body)
                .foregroundStyle(LumiPalette.onSurfaceVariant)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 8)
        }
    }
}

/// The wizard's primary action: the app's own filled primary pill (the
/// player's big control language, sized for the wizard - the Android
/// app's WizardPrimaryButton).
private struct WizardPrimaryButton: View {
    var label: String
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(.headline)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 14)
                .background(LumiPalette.primary)
                .foregroundStyle(LumiPalette.onPrimary)
                .clipShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

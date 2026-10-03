package com.lumi.player.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The first-time setup wizard: the flow a fresh install (cleared app data)
 * lands on before the player. Importing a music folder is the one required
 * step - nothing works without a library - and the two optional items the
 * app supports (playing from an NFC tag, the screen lock) are offered with
 * a skip. The wizard ends into the app (the player's front screen, or the
 * settings screen's NFC tags pane when the tag setup was picked).
 *
 * The completion is the view model's [LumiViewModel.setupComplete] (the
 * `setup_complete` setting): the host shows the wizard while it is false,
 * so a fresh install always lands here and a returned user never does.
 *
 * The flow state (the current step, the folder pick in flight) is the
 * view model's [LumiViewModel.wizardStep] / [LumiViewModel.wizardFolderPickInFlight]:
 * the view model outlives an activity recreation (a rotation, a foldable
 * unfolding, a dark-mode, font-scale or locale change), so a recreation
 * mid-wizard keeps the step instead of restarting the flow at the welcome.
 */
@Composable
fun SetupWizard(
    viewModel: LumiViewModel,
    onFinish: (openNfcTags: Boolean) -> Unit,
) {
    val step by viewModel.wizardStep.collectAsState(viewModel.wizardStep.value)
    val folders by viewModel.observeFolders().collectAsState(emptyList())
    val entries by viewModel.observeEntries().collectAsState(emptyList())
    val adminActive by viewModel.deviceAdminActive.collectAsState(false)
    val folderPickInFlight by viewModel.wizardFolderPickInFlight.collectAsState(
        viewModel.wizardFolderPickInFlight.value,
    )

    // The music folder's picker: the same OpenDocumentTree contract the
    // content pane's "Add folder" uses. The content pane is never composed
    // while the wizard is up (the wizard stands IN for the app's screens),
    // so the two launchers never share the activity's result registry at
    // the same time.
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        viewModel.setWizardFolderPickInFlight(false)
        if (uri != null) viewModel.addFolderEntry(uri)
    }

    // The screen lock's consent: the platform's own device-admin screen
    // (the same consent the Advanced options page's "Enable screen lock"
    // launches); the result is fed back to the view model, which re-checks
    // the admin's real state with the platform.
    val deviceAdminLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        viewModel.onDeviceAdminResult(result.resultCode)
    }

    // The phone can grant or revoke the screen-lock permission while the
    // app is closed, so the screen-lock step re-checks it when it opens.
    LaunchedEffect(step) {
        if (step == SetupStep.ScreenLock) viewModel.refreshDeviceAdminState()
    }

    val hasLibrary = folders.isNotEmpty()
    val songCount = entries.size

    // Finishing: record the completion (it never un-does itself), then let
    // the host take over (the player's front screen, or the settings
    // screen's NFC tags pane when the tag setup was picked).
    fun finish(openNfcTags: Boolean) {
        viewModel.completeSetup()
        onFinish(openNfcTags)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                            MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                )
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            // The app's own logo heads the wizard (square-free, like the
            // launcher's adaptive icon).
            LumiLogo(
                accent = MaterialTheme.colorScheme.primary,
                size = 96.dp,
                modifier = Modifier.testTag("lumi-logo"),
            )
            Spacer(Modifier.size(20.dp))
            when (step) {
                SetupStep.Welcome -> WelcomeStep(
                    onNext = { viewModel.setWizardStep(SetupStep.Music) },
                )
                SetupStep.Music -> MusicStep(
                    hasLibrary = hasLibrary,
                    folderLabel = folders.firstOrNull()?.label,
                    songCount = songCount,
                    pickInFlight = folderPickInFlight,
                    onPickFolder = {
                        viewModel.setWizardFolderPickInFlight(true)
                        folderPicker.launch(null)
                    },
                    onNext = { viewModel.setWizardStep(SetupStep.Nfc) },
                )
                SetupStep.Nfc -> NfcStep(
                    onSetUpNow = { finish(true) },
                    onSkip = { viewModel.setWizardStep(SetupStep.ScreenLock) },
                )
                SetupStep.ScreenLock -> ScreenLockStep(
                    adminActive = adminActive,
                    onEnable = { deviceAdminLauncher.launch(viewModel.deviceAdminIntent()) },
                    onSkip = { viewModel.setWizardStep(SetupStep.AllSet) },
                    onNext = { viewModel.setWizardStep(SetupStep.AllSet) },
                )
                SetupStep.AllSet -> AllSetStep(
                    onOpen = { finish(false) },
                )
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

/** The wizard's screens, one state each. */
enum class SetupStep {
    Welcome,
    Music,
    Nfc,
    ScreenLock,
    AllSet,
}

@Composable
private fun WizardHeading(title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(10.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.size(24.dp))
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    WizardHeading(
        title = "Welcome to lumi",
        body = "lumi plays the music on your phone, with or without a tag. " +
            "It takes a minute to set up.",
    )
    WizardPrimaryButton("Let's set up", onClick = onNext)
}

/**
 * The one required step: a music folder to play from. The pick goes
 * through the app's existing folder import (the same path the content
 * pane's "Add folder" uses), so the imported folder and its songs land in
 * the library the same way. The step stays until a folder is in.
 */
@Composable
private fun MusicStep(
    hasLibrary: Boolean,
    folderLabel: String?,
    songCount: Int,
    pickInFlight: Boolean,
    onPickFolder: () -> Unit,
    onNext: () -> Unit,
) {
    if (!hasLibrary) {
        WizardHeading(
            title = "Add your music",
            body = "lumi plays from a folder on your phone. Choose the folder " +
                "that holds your songs.",
        )
        WizardPrimaryButton(
            label = if (pickInFlight) "Scanning your folder..." else "Choose a music folder",
            onClick = onPickFolder,
        )
    } else {
        WizardHeading(
            title = "Add your music",
            body = "Your music is in - you can add more folders any time.",
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = folderLabel ?: "Your music folder",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = if (songCount == 0) {
                            "No songs found yet - you can add more any time."
                        } else {
                            "$songCount ${if (songCount == 1) "song" else "songs"} imported."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.size(16.dp))
        WizardPrimaryButton("Next", onClick = onNext)
    }
}

/** The optional NFC tag step: set it up now, or skip it. */
@Composable
private fun NfcStep(onSetUpNow: () -> Unit, onSkip: () -> Unit) {
    WizardHeading(
        title = "Play with a tag",
        body = "Hold an NFC tag to the back of your phone to start and pause " +
            "music - no tapping needed.",
    )
    WizardPrimaryButton("Set up now", onClick = onSetUpNow)
    Spacer(Modifier.size(10.dp))
    TonalPillButton("Skip", onClick = onSkip)
}

/**
 * The optional screen-lock step: the platform's own consent is launched
 * right here (the wizard stays put; on the way back the step shows the
 * permission's real state), or the step is skipped.
 */
@Composable
private fun ScreenLockStep(
    adminActive: Boolean,
    onEnable: () -> Unit,
    onSkip: () -> Unit,
    onNext: () -> Unit,
) {
    if (adminActive) {
        WizardHeading(
            title = "Screen lock",
            body = "lumi can lock the screen when the music stops and the phone " +
                "sits idle.",
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Screen lock enabled.",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        Spacer(Modifier.size(16.dp))
        WizardPrimaryButton("Next", onClick = onNext)
    } else {
        WizardHeading(
            title = "Screen lock",
            body = "lumi can lock the screen when the music stops and the phone " +
                "sits idle, so a phone left open does not drain its battery.",
        )
        WizardPrimaryButton("Enable screen lock", onClick = onEnable)
        Spacer(Modifier.size(10.dp))
        TonalPillButton("Skip", onClick = onSkip)
    }
}

@Composable
private fun AllSetStep(onOpen: () -> Unit) {
    WizardHeading(
        title = "You're all set!",
        body = "Your music is in. You can change everything later in the " +
            "settings menu.",
    )
    WizardPrimaryButton("Open lumi", onClick = onOpen)
}

/**
 * The wizard's primary action: the app's own filled primary pill (the
 * player's big control language, sized for the wizard).
 */
@Composable
private fun WizardPrimaryButton(label: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = scheme.primary,
            contentColor = scheme.onPrimary,
        ),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

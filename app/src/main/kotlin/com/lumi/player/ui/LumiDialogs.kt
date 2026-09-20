package com.lumi.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The shared pieces of the settled dialog system (D-01..D-08): dialogs sit
 * on a surface plate with 28 dp corners and a 24 sp bold title; the primary
 * action is a pill button — tonal peach normally, primary fill when
 * destructive — and the dismiss stays a quiet text button.
 */

/**
 * The 56 dp peach NFC wave anchor that headlines the NFC dialogs (the
 * scanning, registration, and NFC-off prompts): the app's tag-reading job,
 * made unmistakable before the words.
 */
@Composable
fun NfcAnchor() {
    val scheme = MaterialTheme.colorScheme
    Row(modifier = Modifier.padding(bottom = 12.dp)) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(scheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Nfc,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = scheme.onPrimaryContainer,
            )
        }
    }
}

/** A tonal peach pill button: the settled shape for a dialog's primary action. */
@Composable
fun TonalPillButton(label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, shape = RoundedCornerShape(500.dp)) {
        Text(label, fontWeight = FontWeight.Bold)
    }
}

/**
 * A primary-fill pill button: the settled shape for a dialog's destructive
 * action (removing a tag or entry), so it reads as the one that bites.
 */
@Composable
fun DestructivePillButton(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, shape = RoundedCornerShape(500.dp)) {
        Text(label, fontWeight = FontWeight.Bold)
    }
}

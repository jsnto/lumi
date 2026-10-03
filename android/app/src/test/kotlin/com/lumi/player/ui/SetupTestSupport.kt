package com.lumi.player.ui

import androidx.test.core.app.ApplicationProvider
import com.lumi.player.LumiApp
import kotlinx.coroutines.runBlocking

/**
 * Marks the first-time setup as complete BEFORE the activity launches, so
 * the first-time setup wizard does not gate the screen under test: every
 * UI test except the wizard's own coverage ([SetupWizardUiTest]) drives
 * the app as a device that was already set up. Robolectric hands each
 * test method a fresh application (and a fresh Room store), so this runs
 * once per test, from the test's own @Before - always before the
 * activity's onCreate reads the store.
 */
fun markSetupCompleteForTests() {
    val app = ApplicationProvider.getApplicationContext<LumiApp>()
    var wrote = false
    Thread {
        runBlocking { app.repository.setSetupComplete(true) }
        wrote = true
    }
        .also { it.start() }
        .apply { join() }
    check(wrote) { "the setup-complete pre-seed must land before the UI test starts" }
}

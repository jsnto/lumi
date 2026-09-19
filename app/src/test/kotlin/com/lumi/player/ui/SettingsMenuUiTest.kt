package com.lumi.player.ui

import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.lumi.player.LumiApp
import com.lumi.player.data.EntryType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * Behavioral tests for the settings menu (polex-style): the settings screen
 * is a list of buttons, one per setting (play mode, tag re-read wait, NFC
 * tags, content, tag-to-content linking); clicking a row navigates into that
 * setting's own configuration screen, and the back arrow / system back
 * unwind it back to the menu (from the menu, to the player screen). The link
 * configuration lays out every tag chip and every add-content button (no
 * clipped lists).
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsMenuUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
    }

    private fun openSettings(): MainActivity {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        return activity
    }

    private fun systemBack(activity: MainActivity) {
        activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
    }

    private fun viewModelOf(activity: MainActivity): LumiViewModel {
        val field = MainActivity::class.java
            .getDeclaredField("viewModel")
            .also { it.isAccessible = true }
        return field.get(activity) as LumiViewModel
    }

    /**
     * Pumps the main looper until a node with [text] is laid out (the
     * settings values arrive through an async Room round trip) or fails.
     */
    private fun waitForText(text: String, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            compose.waitForIdle()
            if (compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()) return
            Thread.sleep(20)
        }
        compose.onNodeWithText(text).assertExists()
    }

    private fun seedData(
        tagNames: List<String> = listOf(
            "Bunny", "Fox", "Owl", "Bear", "Whale", "Panda", "Koala", "Otter",
        ),
        entryLabels: List<String> = listOf(
            "Twinkle", "Rain Song", "Moo Moo", "Star Trail", "Gentle Waves",
        ),
    ) {
        val app = ApplicationProvider.getApplicationContext<LumiApp>()
        val thread = Thread {
            runBlocking {
                tagNames.forEachIndexed { i, name ->
                    app.repository.addTag("02:01:02:03:04:05:10:$i", name)
                }
                entryLabels.forEach { label ->
                    app.repository.addEntry(EntryType.AUDIO, label, "content://media/audio/$label")
                }
            }
        }
        thread.start()
        thread.join()
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun settingsMenuListsEachSettingAsButton() {
        openSettings()

        // Every setting is a button on the settings screen itself.
        compose.onNodeWithText("Play mode").assertIsDisplayed()
        compose.onNodeWithText("Tag re-read wait").assertExists()
        compose.onNodeWithText("15 s until a removed tag pauses").assertExists()
        compose.onNodeWithText("NFC tags").assertExists()
        compose.onNodeWithText("Content").assertExists()
        compose.onNodeWithText("Tag to content").assertExists()

        // The menu only lists the settings - none of the configuration
        // content is on it (that lives in the sub-screens).
        compose.onNodeWithText("Keeps playing until you tap a different tag.")
            .assertDoesNotExist()
        compose.onNodeWithText("In proximity mode, how many seconds a tag may go ", substring = true)
            .assertDoesNotExist()
        compose.onNodeWithText("Register a tag").assertDoesNotExist()
        compose.onNodeWithText("Add content").assertDoesNotExist()
        compose.onNodeWithText("Scan a tag to change its song list").assertDoesNotExist()
        compose.onNodeWithText("Nothing here yet. Add a song.").assertDoesNotExist()
    }

    @Test
    fun eachMenuButtonOpensItsConfigurationScreen() {
        val activity = openSettings()

        // Play mode: the sub-screen offers the mode selection.
        compose.onNodeWithText("Play mode").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Keeps playing until you tap a different tag.")
            .assertIsDisplayed()
        compose.onNodeWithText("Plays while the tag stays near; pauses when it's removed and resumes when you read it again.")
            .assertExists()
        systemBack(activity)
        compose.onNodeWithText("Play mode").assertExists()
        compose.onNodeWithText("Keeps playing until you tap a different tag.")
            .assertDoesNotExist()

        // Tag re-read wait: the sub-screen shows the configured value with
        // steppers (the default is 15 s).
        compose.onNodeWithText("Tag re-read wait").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("In proximity mode, how many seconds a tag may go ", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText("15 s").assertIsDisplayed()
        systemBack(activity)
        compose.onNodeWithText("Tag re-read wait").assertExists()
        compose.onNodeWithText("In proximity mode, how many seconds a tag may go ", substring = true)
            .assertDoesNotExist()

        // NFC tags: the sub-screen offers registration and the (empty) list.
        compose.onNodeWithText("NFC tags").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Register a tag").assertIsDisplayed()
        compose.onNodeWithText("No tags yet. Tap a tag on the back of the phone to register it.")
            .assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("NFC tags").assertExists()
        compose.onNodeWithText("Register a tag").assertDoesNotExist()

        // Content: the sub-screen offers content management.
        compose.onNodeWithText("Content").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Add content").assertIsDisplayed()
        compose.onNodeWithText("Nothing here yet. Add a song.").assertExists()
        systemBack(activity)
        compose.onNodeWithText("Content").assertExists()
        compose.onNodeWithText("Add content").assertDoesNotExist()

        // Tag to content: the sub-screen offers the link editor.
        compose.onNodeWithText("Tag to content").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Scan a tag to change its song list").assertIsDisplayed()
        compose.onNodeWithText("Register a tag first, then link content to it.").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Tag to content").assertExists()
        compose.onNodeWithText("Scan a tag to change its song list").assertDoesNotExist()
    }

    @Test
    fun tagRereadWaitStepper_updatesTheValueAndPersistsIt() {
        val activity = openSettings()
        compose.onNodeWithText("Tag re-read wait").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("15 s").assertIsDisplayed()
        // Stepping up one second: the UI and the persisted value follow the
        // async Room round trip, so pump until the stepped value is laid
        // out.
        compose.onNodeWithText("+").performClick()
        waitForText("16 s")
        compose.onNodeWithText("16 s").assertIsDisplayed()
        // The view model's flow (same StateFlow the UI collects) settled on
        // the new value: it is persisted.
        assertEquals(16L, viewModelOf(activity).nfcRereadSeconds.value)
        // Stepping down one second works the other way.
        compose.onNodeWithText("\u2212").performClick()
        waitForText("15 s")
        compose.onNodeWithText("15 s").assertIsDisplayed()
    }

    @Test
    fun systemBackFromMenuReturnsToThePlayerScreen() {
        val activity = openSettings()
        compose.onNodeWithText("Play mode").assertIsDisplayed()
        systemBack(activity)
        // The player screen's top bar is back; the settings menu is gone.
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
        compose.onNodeWithText("Play mode").assertDoesNotExist()
        compose.onNodeWithText("NFC tags").assertDoesNotExist()
    }

    @Test
    fun linkScreenLaysOutEveryTagAndEveryEntry() {
        val tags = listOf(
            "Bunny", "Fox", "Owl", "Bear", "Whale", "Panda", "Koala", "Otter",
        )
        val entries = listOf(
            "Twinkle", "Rain Song", "Moo Moo", "Star Trail", "Gentle Waves",
        )
        seedData(tagNames = tags, entryLabels = entries)
        openSettings()

        // Open the link configuration from its menu button.
        compose.onNodeWithText("Tag to content").performClick()
        compose.waitForIdle()
        // (The pane's top bar and section card both say "Tag to content"; the
        // scan button proves the configuration screen is open.)
        compose.onNodeWithText("Scan a tag to change its song list").assertIsDisplayed()

        // Every tag chip and every content button is *laid out* in the
        // (scrollable) row / editor - none are clipped away by the layout.
        // The link screen's items may sit outside the viewport on the small
        // test screen, so existence is the assertion for those. Tag names
        // appear once as chips, so at-least-one is the multiplicity
        // assertion.
        tags.forEach { tag -> check(compose.onAllNodesWithText(tag).fetchSemanticsNodes().isNotEmpty()) { "tag '$tag' not laid out" } }
        compose.onNodeWithText("Add to this tag:").assertExists()
        entries.forEach { label -> compose.onNodeWithText("+ $label").assertExists() }
        compose.waitForIdle()
        // Sanity: first and last tag chip and the last content button
        // coexist on the link screen.
        check(compose.onAllNodesWithText("Bunny").fetchSemanticsNodes().isNotEmpty())
        check(compose.onAllNodesWithText("Otter").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("+ Twinkle").assertExists()
        compose.onNodeWithText("+ Gentle Waves").assertExists()
    }
}

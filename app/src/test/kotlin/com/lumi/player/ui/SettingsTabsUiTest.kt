package com.lumi.player.ui

import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.lumi.player.LumiApp
import com.lumi.player.data.EntryType
import kotlinx.coroutines.runBlocking
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
 * Behavioral tests for the tabbed parent settings screen: the four tabs are
 * rendered, switching tabs shows only that tab's section, and the link tab
 * lays out every tag chip and every add-content button (no clipped lists).
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsTabsUiTest {

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
    fun settingsShowFourTabsAndSwitchContent() {
        openSettings()

        // All four tabs are present.
        compose.onNodeWithText("Play mode").assertExists()
        compose.onNodeWithText("Tags").assertExists()
        compose.onNodeWithText("Content").assertExists()
        compose.onNodeWithText("Link").assertExists()

        // The Link tab is selected by default and shows only its section.
        compose.onNodeWithText("Tag to content").assertIsDisplayed()
        compose.onNodeWithText("Register a tag first, then link content to it.")
            .assertIsDisplayed()
        compose.onNodeWithText("Register a tag").assertDoesNotExist()

        // Tags tab.
        compose.onNodeWithText("Tags").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Register a tag").assertIsDisplayed()
        compose.onNodeWithText("No tags yet. Tap a tag on the back of the phone to register it.")
            .assertIsDisplayed()
        compose.onNodeWithText("Tag to content").assertDoesNotExist()

        // Content tab.
        compose.onNodeWithText("Content").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Add content").assertIsDisplayed()
        compose.onNodeWithText("Nothing here yet. Add a song, or a video or link to use later.")
            .assertIsDisplayed()
        compose.onNodeWithText("Register a tag").assertDoesNotExist()

        // Play mode tab.
        compose.onNodeWithText("Play mode").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Continuous").assertIsDisplayed()
        compose.onNodeWithText("Proximity").assertIsDisplayed()
        compose.onNodeWithText("Tag to content").assertDoesNotExist()
    }

    @Test
    fun linkTabLaysOutEveryTagAndEveryEntry() {
        val tags = listOf(
            "Bunny", "Fox", "Owl", "Bear", "Whale", "Panda", "Koala", "Otter",
        )
        val entries = listOf(
            "Twinkle", "Rain Song", "Moo Moo", "Star Trail", "Gentle Waves",
        )
        seedData(tagNames = tags, entryLabels = entries)
        openSettings()

        // Every tag chip and every content button is *laid out* in the
        // (scrollable) row / editor - none are clipped away by the layout.
        // On the small test screen later items sit outside the viewport, so
        // existence is the assertion; the leading items must also be visible.
        compose.onNodeWithText("Bunny").assertIsDisplayed()
        tags.forEach { tag -> compose.onNodeWithText(tag).assertExists() }
        compose.onNodeWithText("Add to this tag:").assertIsDisplayed()
        entries.forEach { label -> compose.onNodeWithText("+ $label").assertExists() }
        compose.waitForIdle()
        // Sanity: first and last tag chip and the last content button coexist
        // on the Link tab.
        compose.onNodeWithText("Bunny").assertExists()
        compose.onNodeWithText("Otter").assertExists()
        compose.onNodeWithText("+ Twinkle").assertExists()
        compose.onNodeWithText("+ Gentle Waves").assertExists()
    }
}

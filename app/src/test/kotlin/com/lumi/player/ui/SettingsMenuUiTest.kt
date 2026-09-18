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
 * Behavioral tests for the parent settings menu: all four settings sections
 * (play mode, NFC tags, content, tag-to-content linking) live on one
 * scrollable screen with no tab navigation, and the link section lays out
 * every tag chip and every add-content button (no clipped lists).
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
    fun settingsShowAllSectionsOnOneScrollableMenu() {
        openSettings()

        // The leading section is fully visible without any tab switching.
        compose.onNodeWithText("Play mode").assertIsDisplayed()
        compose.onNodeWithText("Continuous").assertIsDisplayed()
        compose.onNodeWithText("Proximity").assertIsDisplayed()

        // The other sections sit below it on the same screen: everything is
        // laid out at once. On the small test screen later sections may sit
        // outside the viewport, so existence is the assertion for those.
        compose.onNodeWithText("NFC tags").assertExists()
        compose.onNodeWithText("Register a tag").assertExists()
        compose.onNodeWithText("No tags yet. Tap a tag on the back of the phone to register it.")
            .assertExists()
        compose.onNodeWithText("Content").assertExists()
        compose.onNodeWithText("Add content").assertExists()
        compose.onNodeWithText("Nothing here yet. Add a song, or a video or link to use later.")
            .assertExists()
        compose.onNodeWithText("Tag to content").assertExists()
        compose.onNodeWithText("Register a tag first, then link content to it.").assertExists()
    }

    @Test
    fun linkSectionLaysOutEveryTagAndEveryEntry() {
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
        // The link section sits at the bottom of the settings menu, so on
        // the small test screen its items may be outside the viewport:
        // existence is the assertion, and the leading section must still be
        // visible. Tag names appear in both the tags and the link section,
        // so at-least-one is the multiplicity assertion.
        compose.onNodeWithText("Play mode").assertIsDisplayed()
        tags.forEach { tag -> check(compose.onAllNodesWithText(tag).fetchSemanticsNodes().isNotEmpty()) { "tag '$tag' not laid out" } }
        compose.onNodeWithText("Add to this tag:").assertExists()
        entries.forEach { label -> compose.onNodeWithText("+ $label").assertExists() }
        compose.waitForIdle()
        // Sanity: first and last tag chip and the last content button coexist
        // on the settings menu.
        check(compose.onAllNodesWithText("Bunny").fetchSemanticsNodes().isNotEmpty())
        check(compose.onAllNodesWithText("Otter").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("+ Twinkle").assertExists()
        compose.onNodeWithText("+ Gentle Waves").assertExists()
    }
}

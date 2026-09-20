package com.lumi.player.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.assertHasClickAction
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import kotlin.math.abs

/**
 * Behavioral coverage for the play-mode item styling change:
 *
 *  - The play-mode items (the Continuous / Proximity chips) render without a
 *    visible border: the chip's border is a transparent stroke
 *    ([playModeItemBorder]).
 *  - Where the settings sections used the small outlined "+ ..." pill
 *    buttons (register a tag, add content, add a song to the tag), the
 *    action is now a plain "+" icon button on the section's title row,
 *    right-aligned next to the section title; the per-entry "+ &lt;song&gt;"
 *    pills in the link editor's "Add to this tag" list keep their inline
 *    "+" because the title-row button could not name the song it would add.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayModeItemUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
    }

    private fun openPane(menuRow: String): MainActivity {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(menuRow).performClick()
        compose.waitForIdle()
        return activity
    }

    /** Runs a suspend block on the app's database and returns its result. */
    private fun <T> dbAndGet(block: suspend () -> T): T {
        var result: T? = null
        Thread { result = runBlocking { block() } }.also { it.start() }.apply { join() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun idleUntil(cond: () -> Boolean, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
    }

    @Test
    fun playModeItemsRenderWithoutABorder() {
        openPane("Play mode")

        // Both items are laid out as before (title + detail inside the chip).
        compose.onNodeWithText("Continuous").assertExists()
        compose
            .onNodeWithText("Keeps playing until you tap a different tag.")
            .assertExists()
        compose
            .onNodeWithText(
                "Plays while the tag stays near; pauses when it's removed and resumes when you read it again."
            )
            .assertExists()

        // The chip's border is a transparent stroke, so no outline is drawn.
        assertEquals(
            "the play-mode chips must render borderless",
            Color.Transparent,
            playModeItemBorderColor,
        )
    }

    @Test
    fun registerTagAndAddContentButtonsSitOnTheirSectionTitleRows() {
        val activity = openPane("NFC tags")
        val density = activity.resources.displayMetrics.density
        val screenRight = activity.resources.displayMetrics.widthPixels

        // NFC tags: the "+ Register a tag" pill is a + button now, on the
        // section's title row (16dp column + 20dp card padding + the 4dp
        // inset of the icon within its button, so the same flush-right edge
        // as before the move).
        compose.onNodeWithContentDescription("Register a tag").assertHasClickAction()
        val plus = compose.onNodeWithContentDescription("Register a tag").fetchSemanticsNode()
        val hint = compose
            .onNodeWithText("No tags yet. Tap a tag on the back of the phone to register it.")
            .fetchSemanticsNode()
        assertFlushRight(plus, screenRight, density, 36 + 4)
        assertOnTitleRow(plus, "NFC tags", hint)

        // Back to the menu, then the Content pane.
        activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        compose.onNodeWithText("Content").performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Add content").assertHasClickAction()
        val addContent = compose.onNodeWithContentDescription("Add content").fetchSemanticsNode()
        assertFlushRight(addContent, screenRight, density, 36 + 4)
        assertOnTitleRow(
            addContent,
            "Content",
            compose.onNodeWithText("Nothing here yet. Add a song.").fetchSemanticsNode(),
        )

        // "Add folder" moved to the Folders section's title row (the settled
        // design), so it is now a + button found by its content description,
        // flush right on that section's title row just like "Add content".
        compose.onNodeWithContentDescription("Add folder").assertHasClickAction()
        val addFolder = compose.onNodeWithContentDescription("Add folder").fetchSemanticsNode()
        assertFlushRight(addFolder, screenRight, density, 36 + 4)
        assertOnTitleRow(
            addFolder,
            "Folders",
            compose.onNodeWithText("No folders added yet", substring = true).fetchSemanticsNode(),
        )
    }

    @Test
    fun addSongButtonSitsOnTheSectionTitleRowAndPerEntryPillsKeepTheirPlus() {
        val tagId = dbAndGet {
            ApplicationProvider.getApplicationContext<LumiApp>()
                .repository.addTag("04CAFE00", "Porch")?.id ?: 0L
        }
        dbAndGet {
            ApplicationProvider.getApplicationContext<LumiApp>()
                .repository.addEntry(EntryType.AUDIO, "First song", "content://first")
        }

        val activity = openPane("Tag to content")
        val field = MainActivity::class.java.getDeclaredField("viewModel")
        field.isAccessible = true
        val viewModel = field.get(activity) as LumiViewModel
        idleUntil({ viewModel.effectiveSelectedTagId.value == tagId })

        // The "+ Add song to this tag" pill is now the + button on the
        // section's title row; the header sits at the top of the card, so it
        // is within the small test screen's viewport (the list below it may
        // not be).
        val plus = compose
            .onNodeWithContentDescription("Add song to this tag")
            .assertHasClickAction()
            .fetchSemanticsNode()
        val scan = compose
            .onNodeWithText("Scan a tag to view its song list")
            .fetchSemanticsNode()
        assertOnTitleRow(plus, "Tag to content", scan)

        // The per-entry pills keep their inline "+ <song>" label: each names
        // the song it adds, which the single title-row + button could not.
        compose.onNodeWithText("+ First song").assertExists()
    }

    /**
     * [plus].right must be [expectedInsetDp] dp inside [screenRight] (± a few
     * px of layout rounding), i.e. flush with the section card's right edge.
     */
    private fun assertFlushRight(
        plus: SemanticsNode,
        screenRight: Int,
        density: Float,
        expectedInsetDp: Int,
    ) {
        val expectedRight = screenRight - expectedInsetDp * density
        assertTrue(
            "the + button must be flush with the section's right edge " +
                "(got right=${plus.boundsInRoot.right}, expected ~$expectedRight)",
            abs(plus.boundsInRoot.right - expectedRight) <= 6f,
        )
    }

    /**
     * [plus] must sit on the section's title row: its vertical center matches
     * the section title's (the top bar carries the same title, so the LOWER
     * of the [title] nodes is the section's) and the button is above the
     * section's first content [content].
     */
    private fun assertOnTitleRow(plus: SemanticsNode, title: String, content: SemanticsNode) {
        val sectionTitle = compose
            .onAllNodesWithText(title)
            .fetchSemanticsNodes()
            .maxBy { it.boundsInRoot.top }
        val plusCenter = (plus.boundsInRoot.top + plus.boundsInRoot.bottom) / 2f
        val titleCenter = (sectionTitle.boundsInRoot.top + sectionTitle.boundsInRoot.bottom) / 2f
        assertTrue(
            "the + button must share the section title's row " +
                "(got center=$plusCenter, title center=$titleCenter)",
            abs(plusCenter - titleCenter) <= 4f,
        )
        assertTrue(
            "the + button must sit above the section's content",
            plus.boundsInRoot.bottom < content.boundsInRoot.top,
        )
    }
}

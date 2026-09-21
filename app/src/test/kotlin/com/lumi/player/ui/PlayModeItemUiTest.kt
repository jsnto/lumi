package com.lumi.player.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lumi.player.LumiApp
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
import kotlin.math.abs

/**
 * Behavioral coverage for the play-mode item styling change:
 *
 *  - The play-mode items (the Continuous / Proximity chips) render without a
 *    visible border: the chip's border is a transparent stroke
 *    ([playModeItemBorder]).
 *  - Where the settings sections used the small outlined "+ ..." pill
 *    buttons (add content, add a folder), the action is now a plain "+"
 *    icon button on the section's title row, right-aligned next to the
 *    section title. (The NFC tags section has no + button at all: its
 *    entry point is the "Scan NFC" action button above the tag list.)
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
    fun addContentAndAddFolderButtonsSitOnTheirSectionTitleRows() {
        val activity = openPane("Content")
        val density = activity.resources.displayMetrics.density
        val screenRight = activity.resources.displayMetrics.widthPixels

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

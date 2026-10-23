package com.lumi.player.ui.testutil

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.lumi.player.LumiApp
import com.lumi.player.ui.MainActivity
import com.lumi.player.ui.LumiViewModel
import kotlinx.coroutines.runBlocking
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController

/**
 * The shared NFC test kit for the Robolectric UI tests: the synthetic
 * [Tag] handle builder, the foreground-dispatch intent delivery
 * ([sendTag]), and the settings-pane navigation helpers - the click
 * primitives ([clickSettings], [clickMenuRow], [clickTagRow],
 * [clickFolderRow], [clickSongRow]) and the from-a-fresh-activity
 * composites ([openSettings], [openPane], [openContentPane],
 * [openTagPage], [openFolderPage], [openSongProfile], [openAdvancedPage]).
 *
 * These helpers used to be copy-pasted, nearly verbatim, into the ~40 UI
 * test classes (the per-class `tagWithUid` / `forgeTag` / `syntheticTag`
 * reflection builders, the `sendTag` EXTRA_TAG intents, the `open*`
 * panes, the `viewModelOf` / `dbAndGet` / `app` / `startMainActivity`
 * fixtures). The kit is the single source for the shared shapes; a test
 * class keeps a thin local wrapper only where its precondition or its
 * extra assertion differs (e.g. a pre-seeded store, a controller return).
 */

// ---------------------------------------------------------------------------
// The synthetic tag handles
// ---------------------------------------------------------------------------

// The framework's tag-tech codes in a [Tag]'s tech list (from the get
// factories' bytecode, NOT guesses - the stub SDK omits the tech list).
const val TECH_NFCA = 1
const val TECH_IODEP = 3
const val TECH_MIFARE_ULTRALIGHT = 9
const val TECH_NDEF = 6

/**
 * Forges a presented-tag handle the way the framework's foreground
 * dispatch delivers one: the uid, the [techs] list, the per-tech [techExtras]
 * (the extras the framework fills on presentation - the Mifare Ultralight
 * extras need `sak = 0` and a UID starting `0x04`, the NDEF tech the
 * capacity / card state / NDEF type), and - when [occupiedBy] is a tech
 * code - the tag's single connection slot pre-occupied by that tech (the
 * state the device was in when a tag arrives with the NFC stack's own
 * connection still holding the slot).
 *
 * The stub SDK omits [Tag]'s test hooks; the real framework (loaded by
 * Robolectric at runtime) has the public six-arg constructor, so the tag
 * is constructed reflectively. [tagService] defaults to a no-op binder
 * proxy that answers [isTagUpToDate] with `true` (the real
 * [Tag.getTagService] re-checks it on the binder before every operation,
 * and a null answer NPEs the connect/release path).
 */
fun syntheticTag(
    uid: String,
    techs: IntArray = intArrayOf(),
    techExtras: Array<Bundle> = arrayOf(),
    tagService: Any? = null,
    occupiedBy: Int = -1,
): Tag {
    val nfcTagCls = Class.forName("android.nfc.INfcTag")
    val ctor = Tag::class.java.getDeclaredConstructor(
        ByteArray::class.java,
        IntArray::class.java,
        Array<Bundle>::class.java,
        Int::class.javaPrimitiveType!!,
        Long::class.javaPrimitiveType!!,
        nfcTagCls,
    )
    ctor.isAccessible = true
    val service =
        tagService
            ?: java.lang.reflect.Proxy.newProxyInstance(
                nfcTagCls.classLoader,
                arrayOf(nfcTagCls),
            ) { _, method, _ ->
                // 0 = the success codes the read path needs; isTagUpToDate
                // MUST answer true (see the class KDoc).
                when (method.name) {
                    "connect", "reconnect" -> 0
                    "isTagUpToDate" -> true
                    "isPresent" -> true
                    else -> null
                }
            } as Any
    val tag = ctor.newInstance(
        ByteArray(uid.length / 2) {
            uid.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        },
        techs,
        techExtras,
        0,
        0L,
        service,
    ) as Tag
    if (occupiedBy >= 0) {
        // The real framework (loaded by Robolectric at runtime) exposes
        // the tag's single connection slot; the stub SDK does not, so
        // occupy it reflectively.
        tag.javaClass.getDeclaredMethod(
            "setConnectedTechnology",
            Int::class.javaPrimitiveType!!,
        ).invoke(tag, occupiedBy)
    }
    return tag
}

/**
 * The tag's single connection slot (the framework's
 * `getConnectedTechnology`): -1 when nothing holds it.
 */
fun connectedTechnologySlotOf(tag: Tag): Int =
    tag.javaClass.getDeclaredMethod("getConnectedTechnology").invoke(tag) as Int

// ---------------------------------------------------------------------------
// The foreground-dispatch delivery
// ---------------------------------------------------------------------------

/**
 * Delivers [tag] to the resumed activity the way the NFC stack's
 * foreground dispatch would: an intent carrying EXTRA_TAG to the activity
 * itself, arriving as an onNewIntent (the real [MainActivity] consumes it
 * through [MainActivity.onNewIntent]).
 */
fun ComposeTestRule.sendTag(controller: ActivityController<MainActivity>, tag: Tag) {
    controller.newIntent(Intent(Intent.ACTION_MAIN).putExtra(NfcAdapter.EXTRA_TAG, tag))
    waitForIdle()
}

/**
 * Delivers a synthetic tag with [uid] (a plain NfcA-less handle, the
 * shape the always-listening and the presence tests drive) the way
 * foreground dispatch would.
 */
fun ComposeTestRule.sendTagWithUid(controller: ActivityController<MainActivity>, uid: String) {
    sendTag(controller, syntheticTag(uid))
}

// ---------------------------------------------------------------------------
// The activity / view model / store fixtures
// ---------------------------------------------------------------------------

/** The app under test (the fresh per-test application Robolectric hands each test). */
fun app(): LumiApp = ApplicationProvider.getApplicationContext()

/**
 * Builds the app's real [MainActivity] in the resumed (SETUP) state and
 * idles the compose tree once it is up.
 */
fun ComposeTestRule.setupMainActivity(): ActivityController<MainActivity> {
    val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
    waitForIdle()
    return controller
}

/**
 * The activity's view model (the same reflection seam every settings test
 * uses: the field the activity constructs in onCreate).
 */
fun viewModelOf(activity: MainActivity): LumiViewModel {
    val field = MainActivity::class.java.getDeclaredField("viewModel")
    field.isAccessible = true
    return field.get(activity) as LumiViewModel
}

/**
 * Runs a suspend block on the app's database (its Room store's own
 * executor) and returns its result: the test thread blocks on the write's
 * commit, so a later UI assertion reads the settled store.
 */
fun <T> dbAndGet(block: suspend () -> T): T {
    var result: T? = null
    Thread { result = runBlocking { block() } }.also { it.start() }.apply { join() }
    @Suppress("UNCHECKED_CAST")
    return result as T
}

// ---------------------------------------------------------------------------
// The settings-pane navigation
// ---------------------------------------------------------------------------

/**
 * Clicks the player screen's Settings chip. The top bar settles through an
 * async Room round trip, so the kit waits for the merged tree to have the
 * node before clicking it (a click racing a mid-recomposition top bar
 * misses the node - the merged tree can lag a pending recomposition by a
 * frame).
 */
fun ComposeTestRule.clickSettings() {
    waitForIdle()
    waitForDisplayedContent("Settings")
    onNodeWithContentDescription("Settings").performClick()
    waitForIdle()
}

/**
 * Clicks the settings menu's [menuRow] row (the menu is open). The menu's
 * rows arrive through the same async round trip, so the kit waits for the
 * row before clicking it. The row scrolls into view defensively (the
 * Advanced options row sits below the fold on the short test screen; the
 * scroll is a no-op for a visible row).
 */
fun ComposeTestRule.clickMenuRow(menuRow: String) {
    waitForText(menuRow)
    onNodeWithText(menuRow).performScrollTo()
    waitForIdle()
    onNodeWithText(menuRow).performClick()
    waitForIdle()
}

/**
 * Clicks the registered tag [name]'s row in the NFC tags pane (the tags
 * pane is open). The tag row arrives through an async Room round trip, so
 * the kit waits for it before clicking it.
 */
fun ComposeTestRule.clickTagRow(name: String) {
    waitForText(name)
    onNodeWithText(name).performScrollTo()
    waitForIdle()
    onNodeWithText(name).performClick()
    waitForIdle()
}

/**
 * Clicks the [label] folder's row in the content pane (the content pane is
 * open). The folder row arrives through an async Room round trip, so the
 * kit waits for it before clicking it.
 */
fun ComposeTestRule.clickFolderRow(label: String) {
    waitForText(label)
    onNodeWithText(label).performScrollTo()
    waitForIdle()
    onNodeWithText(label).performClick()
    waitForIdle()
}

/**
 * Clicks the [label] song's row in the content pane (the content pane is
 * open). The song row arrives through an async Room round trip, so the kit
 * waits for it before clicking it.
 */
fun ComposeTestRule.clickSongRow(label: String) {
    waitForText(label)
    onNodeWithText(label).performScrollTo()
    waitForIdle()
    onNodeWithText(label).performClick()
    waitForIdle()
}

/**
 * Opens the settings menu from a fresh activity (the player screen's
 * Settings chip). Returns the activity.
 */
fun ComposeTestRule.openSettings(): MainActivity =
    setupMainActivity().get().also { waitForIdle(); clickSettings() }

/** Opens the [menuRow] setting's configuration sub-screen from a fresh activity. */
fun ComposeTestRule.openPane(menuRow: String): MainActivity {
    val activity = openSettings()
    clickMenuRow(menuRow)
    return activity
}

/** Opens the content pane (the menu's "Content" sub-screen) from a fresh activity. */
fun ComposeTestRule.openContentPane(): MainActivity = openPane("Content")

/** Opens the Advanced options page from a fresh activity. */
fun ComposeTestRule.openAdvancedPage(): MainActivity = openPane("Advanced options")

/** Opens the registered tag [name]'s detail page from a fresh activity. */
fun ComposeTestRule.openTagPage(name: String): MainActivity {
    val activity = openSettings()
    clickMenuRow("NFC tags")
    clickTagRow(name)
    return activity
}

/**
 * Opens the [label] folder's detail page from a fresh activity (the
 * content pane's folder row).
 */
fun ComposeTestRule.openFolderPage(label: String): MainActivity {
    val activity = openContentPane()
    clickFolderRow(label)
    return activity
}

/** Opens the [label] song's profile page from a fresh activity (the content pane's song row). */
fun ComposeTestRule.openSongProfile(label: String): MainActivity {
    val activity = openContentPane()
    clickSongRow(label)
    return activity
}

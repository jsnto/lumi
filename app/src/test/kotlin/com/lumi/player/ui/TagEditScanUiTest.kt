package com.lumi.player.ui

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.lumi.player.LumiApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast

/**
 * The NFC tags pane's "Scan NFC" row: tapping it arms an edit scan that
 * reuses the app's existing NFC read plumbing - the foreground-dispatch
 * read the tag registration already uses (a tag intent delivered to
 * [MainActivity] as EXTRA_TAG, the same shape the system's foreground
 * dispatch delivers). When a tag is scanned, its UID is looked up among
 * the registered tags: a registered one opens its edit dialog (the name
 * and editable details, the UID read-only), and an unregistered one shows
 * a concise not-registered message and leaves the registration flow
 * untouched. The scan result is driven the same way the NFC tests drive
 * tag reads: [ActivityController.newIntent] -> [MainActivity.onNewIntent].
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TagEditScanUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
    }

    private fun app(): LumiApp = ApplicationProvider.getApplicationContext()

    private fun startMainActivity(): ActivityController<MainActivity> {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        compose.waitForIdle()
        return controller
    }

    private fun viewModelOf(activity: MainActivity): LumiViewModel {
        val field = MainActivity::class.java.getDeclaredField("viewModel")
        field.isAccessible = true
        return field.get(activity) as LumiViewModel
    }

    private fun seedTag(uid: String, name: String) {
        val thread = Thread { runBlocking { app().repository.addTag(uid, name) } }
        thread.start()
        thread.join()
        ShadowLooper.idleMainLooper()
    }

    private fun tagWithUid(uid: String): Tag {
        // The stub SDK omits Tag's test hooks; the real framework (loaded by
        // Robolectric at runtime) has the public six-arg constructor, so
        // construct the synthetic tag reflectively.
        val nfcTagCls = Class.forName("android.nfc.INfcTag")
        val ctor = Tag::class.java.getDeclaredConstructor(
            ByteArray::class.java,
            IntArray::class.java,
            Array<android.os.Bundle>::class.java,
            Int::class.javaPrimitiveType!!,
            Long::class.javaPrimitiveType!!,
            nfcTagCls,
        )
        ctor.isAccessible = true
        val tagService = java.lang.reflect.Proxy.newProxyInstance(
            nfcTagCls.classLoader,
            arrayOf(nfcTagCls),
        ) { _, _, _ -> null } as Any
        return ctor.newInstance(
            ByteArray(uid.length / 2) {
                uid.substring(it * 2, it * 2 + 2).toInt(16).toByte()
            },
            intArrayOf(),
            arrayOfNulls<android.os.Bundle>(0),
            0,
            0L,
            tagService,
        ) as Tag
    }

    /**
     * Delivers a tag to the resumed activity the way the NFC stack's
     * foreground dispatch would: an intent carrying EXTRA_TAG to the
     * activity itself, arriving as an onNewIntent.
     */
    private fun sendTag(controller: ActivityController<MainActivity>, uid: String) {
        controller.newIntent(Intent(Intent.ACTION_MAIN).putExtra(NfcAdapter.EXTRA_TAG, tagWithUid(uid)))
        compose.waitForIdle()
    }

    private fun openNfcTagsPane() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("NFC tags").performClick()
        compose.waitForIdle()
    }

    /** Idles the main looper + compose until [cond] holds or fails. */
    private fun idleUntil(cond: () -> Boolean, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            compose.waitForIdle()
            Thread.sleep(10)
        }
        check(cond()) { "timed out waiting for the condition to hold" }
    }

    /** Waits until a node with [text] exists (pumping the looper across Room hops). */
    private fun waitForText(text: String, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: AssertionError? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                compose.onNodeWithText(text).assertExists()
                return
            } catch (e: AssertionError) {
                last = e
                ShadowLooper.idleMainLooper()
                compose.waitForIdle()
                Thread.sleep(10)
            }
        }
        throw last ?: AssertionError("timed out waiting for text '$text'")
    }

    @Test
    fun scanNfcRow_isPresentInTheNfcTagsPane_andOpensTheScan() {
        seedTag(REGISTERED_UID, "Porch")
        startMainActivity()
        openNfcTagsPane()

        // The row matches the pane's pattern: an Nfc-chipped setting row
        // with a title and a detail line.
        compose.onNodeWithText("Scan NFC").assertIsDisplayed()
        compose.onNodeWithText("Scan a registered tag to open its details").assertExists()

        // Tapping it arms the scan: the scan dialog (the registration scan
        // dialog's shape) appears.
        compose.onNodeWithText("Scan NFC").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Scan a tag").assertIsDisplayed()
    }

    @Test
    fun scanOfRegisteredTag_opensTheEditFlow() {
        seedTag(REGISTERED_UID, "Porch")
        val controller = startMainActivity()
        val viewModel = viewModelOf(controller.get())
        openNfcTagsPane()

        compose.onNodeWithText("Scan NFC").performClick()
        compose.waitForIdle()
        assertTrue("tapping the row must arm the edit scan", viewModel.editScanActive.value)
        compose.onNodeWithText("Scan a tag").assertIsDisplayed()

        // A registered tag is presented: the read arrives through the
        // foreground-dispatch path, exactly like a registration scan read.
        sendTag(controller, REGISTERED_UID)
        idleUntil({ !viewModel.editScanActive.value })
        waitForText("Edit tag")

        // The scan dialog closed and the tag's edit dialog opened: title,
        // the read-only UID as context, Save / Cancel.
        compose.onNodeWithText("Scan a tag").assertDoesNotExist()
        compose.onNodeWithText("Edit tag").assertIsDisplayed()
        compose.onNodeWithText("ID: $REGISTERED_UID").assertExists()
        compose.onNodeWithText("Save").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun scanOfUnregisteredTag_showsNotRegisteredMessage_andLeavesRegistrationUntouched() {
        seedTag(REGISTERED_UID, "Porch")
        val controller = startMainActivity()
        val viewModel = viewModelOf(controller.get())
        openNfcTagsPane()

        compose.onNodeWithText("Scan NFC").performClick()
        compose.waitForIdle()
        assertTrue("tapping the row must arm the edit scan", viewModel.editScanActive.value)

        // An unknown tag is presented: the scan settles with the message
        // and no edit dialog.
        sendTag(controller, UNREGISTERED_UID)
        idleUntil({ !viewModel.editScanActive.value })
        compose.waitForIdle()
        ShadowLooper.idleMainLooper()

        assertEquals(
            "That tag isn't registered yet.",
            ShadowToast.getTextOfLatestToast(),
        )
        compose.onNodeWithText("Edit tag").assertDoesNotExist()
        compose.onNodeWithText("Scan a tag").assertDoesNotExist()
        // The registration flow is untouched: no registration session
        // started, no tag captured.
        assertFalse("an unknown scan must not start a registration", viewModel.registering.value)
        assertNull("an unknown scan must not capture the tag", viewModel.capturedUid.value)
    }

    @Test
    fun scansDoNotStack_armingOneScanCancelsTheOther() {
        seedTag(REGISTERED_UID, "Porch")
        val controller = startMainActivity()
        val viewModel = viewModelOf(controller.get())
        openNfcTagsPane()

        // Arm the edit scan, then arm a registration: only one scan may be
        // armed - the registration wins and the edit scan is dropped.
        compose.onNodeWithText("Scan NFC").performClick()
        compose.waitForIdle()
        assertTrue("tapping the row must arm the edit scan", viewModel.editScanActive.value)
        viewModel.startTagRegistration()
        compose.waitForIdle()
        assertFalse("arming a registration must cancel the edit scan", viewModel.editScanActive.value)
        assertTrue("the registration must be armed", viewModel.registering.value)

        // A read that now lands settles the REGISTRATION (it is captured),
        // not a stale edit scan (which would open the edit dialog instead).
        sendTag(controller, REGISTERED_UID)
        idleUntil({ viewModel.capturedUid.value == REGISTERED_UID })
        assertNull("a stale edit scan must not have resolved a tag", viewModel.pendingEditTag.value)
        compose.onNodeWithText("Edit tag").assertDoesNotExist()

        // The reverse: arming an edit scan while a registration is armed
        // cancels the registration.
        viewModel.startTagRegistration()
        compose.waitForIdle()
        assertTrue("the registration must be armed again", viewModel.registering.value)
        viewModel.startTagEditScan()
        compose.waitForIdle()
        assertFalse("arming an edit scan must cancel the registration", viewModel.registering.value)
        assertTrue("the edit scan must be armed", viewModel.editScanActive.value)
    }

    companion object {
        const val REGISTERED_UID = "04A1B2C3"
        const val UNREGISTERED_UID = "04DEADBEEF"
    }
}

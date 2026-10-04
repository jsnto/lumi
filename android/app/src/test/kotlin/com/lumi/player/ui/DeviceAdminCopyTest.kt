package com.lumi.player.ui

import android.app.admin.DevicePolicyManager
import android.content.Context
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import com.lumi.player.LumiApp
import com.lumi.player.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The user-visible device-admin consent copy behind the Advanced options
 * page's "Enable screen lock" action (the auto-lock after idle setting's
 * permission prompt): the copy the PLATFORM renders must name the page the
 * idle-time ladder now lives on - the Advanced options page - not the
 * removed "System" settings page.
 *
 * Two surfaces the platform renders from the app's own data: the
 * [DevicePolicyManager.EXTRA_ADD_EXPLANATION] extra of the consent intent
 * the "Enable screen lock" action builds ([LumiViewModel.deviceAdminIntent],
 * driven here through the real view model), and the
 * `device_admin_description` string resource the platform's device-admin
 * UI shows (referenced by the admin receiver's `android:description` in the
 * manifest, resolved here through the app's own resource consumer).
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = LumiApp::class,
    shadows = [ShadowNfcAdapterForTest::class],
)
class DeviceAdminCopyTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        markSetupCompleteForTests()
    }


    private fun viewModelOf(activity: MainActivity): LumiViewModel {
        val field = MainActivity::class.java
            .getDeclaredField("viewModel")
            .also { it.isAccessible = true }
        return field.get(activity) as LumiViewModel
    }

    @Test
    fun theConsentIntentExplanationNamesTheAdvancedOptionsPage() {
        NfcTestState.reset()
        NfcTestState.radioEnabled = true
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val vm = viewModelOf(controller.get())

        val explanation =
            vm.deviceAdminIntent().getStringExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION)
        assertNotNull("the consent intent must carry the platform explanation", explanation)
        val text = explanation.orEmpty()
        assertEquals(
            "the consent explanation must name the page the idle-time ladder lives on",
            "lumi is asking for permission to lock the phone's screen after " +
                "music has stopped playing for the time you choose in the Advanced options page.",
            text,
        )
        assertFalse(
            "the removed System page must not be named",
            text.contains("System setting"),
        )
    }

    @Test
    fun theDeviceAdminDescriptionResourceNamesTheAdvancedOptionsPage() {
        val description =
            ApplicationProvider.getApplicationContext<Context>()
                .getString(R.string.device_admin_description)
        assertEquals(
            "the platform device-admin UI copy must name the Advanced options page",
            "When enabled, lumi can lock the phone's screen after music has stopped " +
                "playing for the time you choose in the Advanced options page.",
            description,
        )
        assertFalse(
            "the removed System page must not be named",
            description.contains("System setting"),
        )
    }
}

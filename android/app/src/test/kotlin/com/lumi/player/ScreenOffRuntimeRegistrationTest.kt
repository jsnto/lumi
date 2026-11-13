package com.lumi.player

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.lumi.player.device.ScreenOffReceiver
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The runtime screen-off registration: recent platform builds send the
 * system's SCREEN_OFF broadcast with FLAG_RECEIVER_REGISTERED_ONLY
 * (device-verified on the API 37 Pixel 7 Pro), which excludes the static
 * manifest receiver from resolution - only runtime-registered receivers
 * get the broadcast. The app process is guaranteed alive while a song
 * plays (the foreground PlayerService), so [LumiApp] registers
 * [ScreenOffReceiver] at startup for as long as the process lives,
 * alongside the manifest entry (the two surfaces funnel into the same
 * idempotent decision logic).
 *
 * The platform's actual broadcast delivery to a runtime-registered receiver
 * is not simulable in the pruned Robolectric mirror (the sendBroadcast
 * shadow is absent), so the delivery itself is pinned by the on-device
 * validation (the API 37 Pixel 7 Pro's registered-only SCREEN_OFF reached
 * the runtime registration and paused a playing song); the receiver's own
 * decision + pause wiring is pinned by [com.lumi.player.device.ScreenOffReceiverTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LumiApp::class)
class ScreenOffRuntimeRegistrationTest {

    @Test
    fun theAppRegistersTheScreenOffReceiverAtStartup() {
        val app = ApplicationProvider.getApplicationContext<LumiApp>()
        val registration = shadowOf(app).registeredReceivers.firstOrNull {
            it.intentFilter.hasAction(Intent.ACTION_SCREEN_OFF)
        }
        assertTrue(
            "the app must register a runtime SCREEN_OFF receiver at startup",
            registration != null,
        )
        assertTrue(
            "the runtime registration must be the app's own ScreenOffReceiver",
            registration!!.broadcastReceiver is ScreenOffReceiver,
        )
    }
}

package com.lumi.player.ui;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.IntentFilter;
import android.nfc.NfcAdapter;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * Local test shadow that makes [NfcAdapter.getDefaultAdapter] return a controllable adapter
 * instance and routes [NfcAdapter.isEnabled] to [NfcTestState], so UI tests can drive the
 * "NFC hardware present / radio on or off" states under Robolectric.
 */
@Implements(NfcAdapter.class)
public class ShadowNfcAdapterForTest {

  @Implementation
  protected static NfcAdapter getDefaultAdapter(Context context) {
    return NfcTestState.adapterFor(context);
  }

  @Implementation
  protected boolean isEnabled() {
    return NfcTestState.radioEnabled;
  }

  // Local shadows replace the framework shadow, so the foreground-dispatch
  // API [MainActivity] uses needs an explicit implementation. It records the
  // wiring state in NfcTestState so UI tests can assert the always-listening
  // receive path is active.
  @Implementation
  protected void enableForegroundDispatch(
      Activity activity, PendingIntent intent, IntentFilter[] filters, String[][] techLists) {
    NfcTestState.foregroundDispatchEnabled = true;
  }

  @Implementation
  protected void disableForegroundDispatch(Activity activity) {
    NfcTestState.foregroundDispatchEnabled = false;
  }
}

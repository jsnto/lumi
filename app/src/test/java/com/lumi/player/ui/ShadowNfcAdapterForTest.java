package com.lumi.player.ui;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.IntentFilter;
import android.nfc.NfcAdapter;
import android.os.Bundle;
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

  // Local shadows replace the framework shadow, so the foreground-dispatch /
  // reader-mode APIs [MainActivity] uses need explicit implementations. They
  // record the wiring state in NfcTestState so UI tests can assert which NFC
  // receive path the activity is on and fire the registered reader callback.
  @Implementation
  protected void enableForegroundDispatch(
      Activity activity, PendingIntent intent, IntentFilter[] filters, String[][] techLists) {
    NfcTestState.foregroundDispatchEnabled = true;
  }

  @Implementation
  protected void disableForegroundDispatch(Activity activity) {
    NfcTestState.foregroundDispatchEnabled = false;
  }

  @Implementation
  protected void enableReaderMode(
      Activity activity, NfcAdapter.ReaderCallback callback, int flags, Bundle extras) {
    NfcTestState.readerCallback = callback;
    NfcTestState.readerFlags = flags;
    NfcTestState.readerModeEnabled = true;
  }

  @Implementation
  protected void disableReaderMode(Activity activity) {
    NfcTestState.readerModeEnabled = false;
  }
}

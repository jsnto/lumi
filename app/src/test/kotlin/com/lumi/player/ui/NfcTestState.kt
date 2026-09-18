package com.lumi.player.ui

import android.content.Context
import android.nfc.NfcAdapter

/** Controllable NFC radio state for the [ShadowNfcAdapterForTest] test shadow. */
object NfcTestState {
    @Volatile @JvmField var adapterExists = true
    @Volatile @JvmField var radioEnabled = false
    @Volatile @JvmField var adapter: NfcAdapter? = null

    // Reader-mode / foreground-dispatch wiring state, recorded by the shadow
    // so UI tests can assert which NFC receive path the activity is on and
    // fire the registered reader callback with a synthetic tag.
    @Volatile @JvmField var readerModeEnabled = false
    @Volatile @JvmField var readerCallback: NfcAdapter.ReaderCallback? = null
    @Volatile @JvmField var readerFlags = 0
    @Volatile @JvmField var foregroundDispatchEnabled = false

    @JvmStatic
    fun reset() {
        adapterExists = true
        radioEnabled = false
        adapter = null
        readerModeEnabled = false
        readerCallback = null
        readerFlags = 0
        foregroundDispatchEnabled = false
    }

    @JvmStatic
    fun adapterFor(context: Context): NfcAdapter? {
        if (!adapterExists) return null
        return adapter ?: run {
            val ctor = NfcAdapter::class.java.getDeclaredConstructor(Context::class.java)
            ctor.isAccessible = true
            val created = ctor.newInstance(context.applicationContext)
            adapter = created
            created
        }
    }
}

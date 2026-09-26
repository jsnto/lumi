package com.lumi.player.ui

import android.content.Context
import android.nfc.NfcAdapter

/** Controllable NFC radio state for the [ShadowNfcAdapterForTest] test shadow. */
object NfcTestState {
    @Volatile @JvmField var adapterExists = true
    @Volatile @JvmField var radioEnabled = false
    @Volatile @JvmField var adapter: NfcAdapter? = null

    // Foreground-dispatch wiring state, recorded by the shadow so UI tests
    // can assert the activity is on the always-listening receive path.
    @Volatile @JvmField var foregroundDispatchEnabled = false

    @JvmStatic
    fun reset() {
        adapterExists = true
        radioEnabled = false
        adapter = null
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

package com.lumi.player.ui

import android.content.Context
import android.nfc.NfcAdapter

/** Controllable NFC radio state for the [ShadowNfcAdapterForTest] test shadow. */
object NfcTestState {
    @Volatile @JvmField var adapterExists = true
    @Volatile @JvmField var radioEnabled = false
    @Volatile @JvmField var adapter: NfcAdapter? = null

    @JvmStatic
    fun reset() {
        adapterExists = true
        radioEnabled = false
        adapter = null
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

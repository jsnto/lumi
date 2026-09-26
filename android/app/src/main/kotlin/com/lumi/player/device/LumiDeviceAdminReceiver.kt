package com.lumi.player.device

import android.app.admin.DeviceAdminReceiver

/**
 * The app's device-admin receiver: the consent and activation surface for
 * the System setting's "Auto-lock after idle" screen lock. A normal app
 * cannot lock the screen on its own (DEVICE_POWER is a signature|privileged
 * permission); the supported path is a device admin plus
 * [android.app.admin.DevicePolicyManager.lockNow]. The platform manages the
 * admin's lifecycle against the manifest entry (label, description, the
 * BIND_DEVICE_ADMIN permission) and the policy description in
 * `res/xml/device_admin.xml` (the force-lock policy - the one thing this
 * admin may do). The receiver holds no logic of its own.
 */
class LumiDeviceAdminReceiver : DeviceAdminReceiver()

package dev.node01.afterlife

import android.app.admin.DeviceAdminReceiver

/** Exists only so `dpm set-device-owner` has something to name; see NodeActivity.kiosk. */
class NodeAdmin : DeviceAdminReceiver()

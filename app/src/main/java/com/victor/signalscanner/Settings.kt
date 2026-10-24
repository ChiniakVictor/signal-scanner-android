package com.victor.signalscanner

import android.content.Context

class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var wifiIntervalSec: Int
        get() = p.getInt("wifi_interval", 30); set(v) = p.edit().putInt("wifi_interval", v).apply()
    var btIntervalSec: Int
        get() = p.getInt("bt_interval", 5); set(v) = p.edit().putInt("bt_interval", v).apply()
    var classicDiscovery: Boolean
        get() = p.getBoolean("classic", true); set(v) = p.edit().putBoolean("classic", v).apply()
    var nfcReader: Boolean
        get() = p.getBoolean("nfc_reader", true); set(v) = p.edit().putBoolean("nfc_reader", v).apply()
    var keepScreenOn: Boolean
        get() = p.getBoolean("keep_screen_on", false); set(v) = p.edit().putBoolean("keep_screen_on", v).apply()
    var forgetMinutes: Int
        get() = p.getInt("forget_min", 10); set(v) = p.edit().putInt("forget_min", v).apply()
    var bannerDismissed: Boolean
        get() = p.getBoolean("banner", false); set(v) = p.edit().putBoolean("banner", v).apply()
}

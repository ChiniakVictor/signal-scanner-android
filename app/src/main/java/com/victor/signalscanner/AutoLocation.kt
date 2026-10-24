package com.victor.signalscanner

import android.app.Application
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.provider.Settings as SysSettings
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Auto Location: turns Location services on while Signal Scanner is in front and back off when it leaves,
 * but only if the app was the one that turned it on. Needs WRITE_SECURE_SETTINGS, granted once via
 *   adb shell pm grant com.victor.signalscanner android.permission.WRITE_SECURE_SETTINGS
 */
object AutoLocation {
    private const val TAG = "SSAutoLoc"
    private const val OFF_DEBOUNCE_MS = 2000L
    const val ADB_GRANT = "adb shell pm grant com.victor.signalscanner android.permission.WRITE_SECURE_SETTINGS"

    /** UI status line, e.g. "turned Location on (was off)". */
    val status = MutableStateFlow<String?>(null)
    val permitted = MutableStateFlow(false)

    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private val prefs by lazy { app.getSharedPreferences("autoloc", Context.MODE_PRIVATE) }

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(v) { prefs.edit().putBoolean("enabled", v).apply(); if (!v) weTurnedOn = false; status.value = if (v) "Auto Location on" else "Auto Location off" }
    /** Persisted so a killed process can still undo its change on the next start. */
    private var weTurnedOn: Boolean
        get() = prefs.getBoolean("we_turned_on", false)
        set(v) { prefs.edit().putBoolean("we_turned_on", v).commit() }

    fun canWrite(ctx: Context = app) = ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
    fun isOn(ctx: Context = app) = ctx.getSystemService(LocationManager::class.java)?.isLocationEnabled == true

    private val offRunnable = Runnable { turnOffIfOurs("left app") }

    fun install(a: Application) {
        app = a
        permitted.value = canWrite()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { main.removeCallbacks(offRunnable); onForeground() }
            override fun onStop(owner: LifecycleOwner) { main.removeCallbacks(offRunnable); main.postDelayed(offRunnable, OFF_DEBOUNCE_MS) }
        })
        // Safety net: a previous run turned Location on and was killed before it could undo it.
        if (weTurnedOn) main.postDelayed({
            if (!ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) turnOffIfOurs("cleanup after the app was killed")
        }, 3000)
    }

    private fun onForeground() {
        permitted.value = canWrite()
        if (!enabled) return
        if (!permitted.value) { status.value = "Auto Location needs a one-time adb grant"; return }
        runCatching { app.startService(Intent(app, TaskWatcher::class.java)) }
        if (isOn()) {
            status.value = if (weTurnedOn) "Location on (turned on by Signal Scanner; off when you leave)" else "Location was already on – it'll stay on"
            return
        }
        val how = set(true)
        if (how != null) { weTurnedOn = true; status.value = "Turned Location on ($how) – off when you leave"; Log.i(TAG, "location ON via $how") }
        else status.value = "Couldn't turn Location on automatically"
    }

    fun turnOffIfOurs(why: String) {
        if (!::app.isInitialized || !weTurnedOn) return
        if (!canWrite()) return
        if (!enabled) { weTurnedOn = false; return }
        if (!isOn()) { weTurnedOn = false; return }
        if (trailLinkSharing()) {
            // Trail Link needs Location for position sharing: hand Location over to it and never switch it off.
            weTurnedOn = false
            status.value = "Location left on – Trail Link is sharing your position"; Log.i(TAG, "leaving Location on: Trail Link is sharing"); return
        }
                if (TrackerWatch.backgroundWatch) { status.value = "Location left on – Tracker watch in background needs it"; Log.i(TAG, "leaving Location on for tracker watch"); return }
        val how = set(false)
        Log.i(TAG, "location OFF ($why) via $how")
        if (how != null) { weTurnedOn = false; status.value = "Turned Location back off ($why)" }
    }

    /** Asks Trail Link (com.victor.traillink) whether it is sharing positions; false if it isn't installed or doesn't answer. */
    fun trailLinkSharing(): Boolean = runCatching {
        app.contentResolver.query(android.net.Uri.parse("content://com.victor.traillink.status/sharing"), null, null, null, null)?.use { c ->
            c.moveToFirst() && c.getInt(c.getColumnIndexOrThrow("sharing")) == 1
        } ?: false
    }.onFailure { Log.w(TAG, "Trail Link status query failed: $it") }.getOrDefault(false)

    /** Returns the method that worked, or null. Tries the LocationManager API first, then the settings value. */
    private fun set(on: Boolean): String? {
        val lm = app.getSystemService(LocationManager::class.java)
        runCatching {
            val m = LocationManager::class.java.getMethod("setLocationEnabledForUser", Boolean::class.javaPrimitiveType, UserHandle::class.java)
            m.invoke(lm, on, Process.myUserHandle())
            if (isOn() == on) return "LocationManager"
        }.onFailure { Log.w(TAG, "setLocationEnabledForUser failed: $it") }
        runCatching {
            @Suppress("DEPRECATION")
            SysSettings.Secure.putInt(app.contentResolver, SysSettings.Secure.LOCATION_MODE,
                if (on) SysSettings.Secure.LOCATION_MODE_HIGH_ACCURACY else SysSettings.Secure.LOCATION_MODE_OFF)
            repeat(10) { if (isOn() == on) return "secure setting"; Thread.sleep(50) }
        }.onFailure { Log.w(TAG, "putInt(LOCATION_MODE) failed: $it") }
        return null
    }
}

/** Plain (non-foreground) service whose only job is to notice the task being swiped away from Recents. */
class TaskWatcher : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onTaskRemoved(rootIntent: Intent?) { AutoLocation.turnOffIfOurs("app swiped away"); stopSelf() }
}

class SignalApp : Application() {
    override fun onCreate() { super.onCreate(); AutoLocation.install(this); TrackerWatch.init(this) }
}

package com.victor.signalscanner

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*

/**
 * Optional (default OFF) background tracker watch: every 3 minutes, a 12-second filtered BLE scan for trackers only,
 * plus an approximate location. Runs as a foreground service so Android allows Bluetooth + location while the app is closed.
 * Skips its own scan while the app is open (the main scanner already feeds the tracker log).
 */
class TrackerWatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TrackerWatch.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("tracker_watch", "Tracker watch (background)", NotificationManager.IMPORTANCE_MIN))
        val pi = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).putExtra("tab", 2), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, "tracker_watch").setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Tracker watch is on").setContentText("Short Bluetooth check for unknown trackers every 3 minutes")
            .setOngoing(true).setContentIntent(pi).setPriority(NotificationCompat.PRIORITY_MIN).build()
        val ok = runCatching {
            ServiceCompat.startForeground(this, 77, n, if (Build.VERSION.SDK_INT >= 29)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
        }.onFailure { Log.w("SSTrackers", "watch can't start: $it") }.isSuccess
        if (!ok || !TrackerWatch.backgroundWatch) { stopSelf(); return START_NOT_STICKY }
        if (scope.coroutineContext[Job]?.children?.any() != true) scope.launch { loop() }
        return START_STICKY
    }

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            if (!Repo.running.value) scanOnce()
            delay(3 * 60_000L)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun scanOnce() {
        val sc = runCatching { getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner }.getOrNull() ?: return
        val cb = object : ScanCallback() { override fun onScanResult(t: Int, r: ScanResult) { TrackerWatch.onScan(r) } }
        TrackerWatch.startLocation()
        val started = runCatching { sc.startScan(TrackerWatch.scanFilters(), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(), cb) }.isSuccess
        delay(12_000)
        if (started) runCatching { sc.stopScan(cb) }
        if (!Repo.running.value) TrackerWatch.stopLocation()
    }

    override fun onDestroy() { scope.cancel(); if (!Repo.running.value) TrackerWatch.stopLocation(); super.onDestroy() }
}

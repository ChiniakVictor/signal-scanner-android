package com.victor.signalscanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var scanner: Scanner
    private lateinit var settings: Settings
    private var missing by mutableStateOf(listOf<String>())
    private var paused by mutableStateOf(false)
    private var started = false

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        missing = missingPerms()
        if (started && !paused) scanner.restart()
    }

    private fun wanted(): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT) }
        add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun missingPerms(): List<String> = wanted().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.mapNotNull {
        when (it) {
            Manifest.permission.ACCESS_FINE_LOCATION -> "Precise location"
            Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.BLUETOOTH_SCAN -> "Nearby devices"
            Manifest.permission.READ_PHONE_STATE -> "Phone (cell info)"
            Manifest.permission.POST_NOTIFICATIONS -> "Notifications (tracker alerts)"
            else -> null
        }
    }.distinct()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        scanner = Scanner(this)
        settings = Settings(this)
        missing = missingPerms()
        if (missing.isNotEmpty() && savedInstanceState == null) permLauncher.launch(wanted().toTypedArray())
        handleTestIntent(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF4DD0E1), onPrimary = Color(0xFF00252B),
                secondary = Color(0xFFFFA94D), onSecondary = Color(0xFF2B1600),
                background = Color(0xFF0E1116), onBackground = Color(0xFFE6EDF3),
                surface = Color(0xFF0E1116), onSurface = Color(0xFFE6EDF3),
                surfaceVariant = Color(0xFF1B222C), onSurfaceVariant = Color(0xFFA9B4C0),
                surfaceContainer = Color(0xFF161B22), surfaceContainerHigh = Color(0xFF1B222C),
                surfaceContainerHighest = Color(0xFF222A35), surfaceContainerLow = Color(0xFF131820),
                secondaryContainer = Color(0xFF1E3440), onSecondaryContainer = Color(0xFFE6EDF3),
                error = Color(0xFFFF6B6B), outline = Color(0xFF3A4554),
            )) {
                MainScreen(scanner, missing, onGrant = ::grant, onExport = ::export, onSettingsChanged = ::applySettings,
                    onPauseToggle = { paused = !paused; if (paused) scanner.stop() else scanner.start(); updateReader() }, paused = paused)
            }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleTestIntent(intent) }

    /** adb test hooks: --ei tab N, --es select TEXT, --es export wifi|bt|nfc|json, --ei wifi_interval S, --ez channels true|false */
    private fun handleTestIntent(i: Intent?) {
        i ?: return
        if (i.hasExtra("tab")) TestHooks.tab.value = i.getIntExtra("tab", 0)
        if (i.hasExtra("wifi_interval")) { Settings(this).wifiIntervalSec = i.getIntExtra("wifi_interval", 30).coerceIn(5, 120); if (::scanner.isInitialized && started && !paused) scanner.restart() }
        if (i.hasExtra("channels")) TestHooks.channels.value = i.getBooleanExtra("channels", false)
        if (i.hasExtra("ultra")) TestHooks.ultra.value = i.getBooleanExtra("ultra", false)
        if (i.getBooleanExtra("calibrate", false)) TestHooks.calibrate.value = true
        i.getStringExtra("select")?.let { TestHooks.select.value = it }
        i.getStringExtra("export")?.let { e -> lifecycleScope.launch { kotlinx.coroutines.delay(1500); export(e) } }
    }

    private fun grant() {
        val denied = wanted().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (denied.any { !shouldShowRequestPermissionRationale(it) } && missing.isNotEmpty() && started) {
            // permanently denied → open app settings
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null))) }
        }
        permLauncher.launch(wanted().toTypedArray())
    }

    private fun applySettings() {
        if (!paused) scanner.restart()
        updateReader(); updateScreenOn()
    }

    override fun onStart() {
        super.onStart()
        started = true
        missing = missingPerms()
        if (!paused) scanner.start()
        updateScreenOn()
        if (TrackerWatch.backgroundWatch) TrackerWatch.syncService(this)
    }

    override fun onStop() {
        scanner.stop()
        started = false
        super.onStop()
    }

    override fun onResume() { super.onResume(); updateReader() }
    override fun onPause() {
        runCatching { NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this) }
        scanner.refreshNfcState(false)
        super.onPause()
    }

    private fun updateScreenOn() {
        if (settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun updateReader() {
        val a = NfcAdapter.getDefaultAdapter(this)
        if (a == null) { scanner.refreshNfcState(false); return }
        if (settings.nfcReader && a.isEnabled && !paused) {
            val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_NFC_BARCODE
            val ok = runCatching {
                a.enableReaderMode(this, { tag ->
                    val t = runCatching { NfcParse.read(tag) }.getOrNull()
                    if (t != null) Repo.addTap(t)
                }, flags, Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 500) })
            }.isSuccess
            scanner.refreshNfcState(ok)
        } else {
            runCatching { a.disableReaderMode(this) }
            scanner.refreshNfcState(false)
        }
    }

    private fun export(kind: String) {
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    when (kind) {
                        "wifi" -> Export.save(this@MainActivity, "wifi", "csv", "text/csv", Export.wifiCsv(Repo.wifiAps.value))
                        "bt" -> Export.save(this@MainActivity, "bluetooth", "csv", "text/csv", Export.btCsv(Repo.bt.value))
                        "cell" -> Export.save(this@MainActivity, "cells", "csv", "text/csv", Export.cellCsv(CellRepo.cells.value))
                        "nfc" -> Export.save(this@MainActivity, "nfc_taps", "csv", "text/csv", Export.nfcCsv(Repo.taps.value))
                        else -> Export.save(this@MainActivity, "signals", "json", "application/json", Export.json())
                    }
                }
            }
            Toast.makeText(this@MainActivity, r.fold({ "Saved to $it" }, { "Export failed: ${it.message}" }), Toast.LENGTH_LONG).show()
        }
    }
}

object TestHooks {
    val tab = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
    val select = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val channels = kotlinx.coroutines.flow.MutableStateFlow<Boolean?>(null)
    val ultra = kotlinx.coroutines.flow.MutableStateFlow<Boolean?>(null)
    val calibrate = kotlinx.coroutines.flow.MutableStateFlow(false)
}

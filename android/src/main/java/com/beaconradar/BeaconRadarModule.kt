package com.beaconradar

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.facebook.react.bridge.*
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.modules.core.DeviceEventManagerModule
import org.altbeacon.beacon.*
import java.util.concurrent.atomic.AtomicBoolean

@ReactModule(name = BeaconRadarModule.NAME)
class BeaconRadarModule(reactContext: ReactApplicationContext) : 
    NativeBeaconRadarSpec(reactContext), 
    MonitorNotifier, 
    RangeNotifier {

    companion object {
        const val NAME = "BeaconRadar"
        const val TAG = "BeaconRadar"
        private const val NOTIFICATION_CHANNEL_ID = "beacon_detector_channel"
        private const val FOREGROUND_NOTIFICATION_CHANNEL_ID = "beacon_foreground_channel"
        private const val FOREGROUND_NOTIFICATION_NAME = "Beacon Scanner Service"
        private const val FOREGROUND_NOTIFICATION_DESCRIPTION = "Required for background beacon scanning"
        private const val FOREGROUND_NOTIFICATION_ID = 456
        private const val PREFS_NAME = "BeaconRadarPrefs"
        private const val BACKGROUND_MODE_KEY = "backgroundModeEnabled"
        private const val MAX_DISTANCE = 10.0

        @JvmStatic
        var instance: BeaconRadarModule? = null
    }

    private val beaconManager: BeaconManager = BeaconManager.getInstanceForApplication(reactApplicationContext)
    private var region: Region = Region("all-beacons", null, null, null)
    
    // Flag atomici per prevenire race conditions e chiamate ridondanti su thread diversi
    private val isScanning = AtomicBoolean(false)
    private val isRanging = AtomicBoolean(false)

    init {
        instance = this
        setupBeaconManager()
        val backgroundMode = loadBackgroundModeSetting()
        setBackgroundModeInternal(backgroundMode)
    }

    override fun getName(): String = NAME

    // ─────────────────────────────────────────────────────────────────────────
    // Setup Core
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupBeaconManager() {
        BeaconManager.setDebug(false)
        
        // Configurazione layout iBeacon standard (evita inserimenti duplicati)
        val iBeaconLayout = "m:0-3=4c000215,i:4-19,i:20-21,i:22-23,p:24-24"
        if (beaconManager.beaconParsers.none { it.layout == iBeaconLayout }) {
            beaconManager.beaconParsers.add(BeaconParser().setBeaconLayout(iBeaconLayout))
        }

        // Frequenze di scansione ottimizzate
        beaconManager.foregroundScanPeriod = 1100L
        beaconManager.foregroundBetweenScanPeriod = 0L
        beaconManager.backgroundScanPeriod = 1100L
        beaconManager.backgroundBetweenScanPeriod = 0L
    }

    private fun loadBackgroundModeSetting(): Boolean {
        val sharedPrefs = reactApplicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sharedPrefs.getBoolean(BACKGROUND_MODE_KEY, false)
    }

    private fun setBackgroundModeInternal(enable: Boolean) {
        try {
            val sharedPrefs = reactApplicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            sharedPrefs.edit().putBoolean(BACKGROUND_MODE_KEY, enable).apply()
            
            beaconManager.setEnableScheduledScanJobs(enable)
            beaconManager.setBackgroundMode(enable)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting background mode", e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TurboModule Public Bridge API (Thread-Safe via UI Queue)
    // ─────────────────────────────────────────────────────────────────────────

    override fun startScanning(uuid: String, options: ReadableMap, promise: Promise) {
        if (isScanning.get()) {
            promise.resolve(true)
            return
        }

        region = Region("all-beacons", Identifier.parse(uuid), null, null)

        reactApplicationContext.runOnUiQueueThread {
            try {
                // Svuota in sicurezza i notifier precedenti per evitare eventi duplicati
                beaconManager.removeAllMonitorNotifiers()
                beaconManager.removeAllRangeNotifiers()
                
                beaconManager.addMonitorNotifier(this)
                beaconManager.addRangeNotifier(this)
                
                beaconManager.startMonitoring(region)
                beaconManager.requestStateForRegion(region)
                
                if (loadBackgroundModeSetting()) {
                    setupForegroundService()
                }

                isScanning.set(true)
                promise.resolve(true)
            } catch (e: Exception) {
                Log.e(TAG, "Error in startScanning", e)
                promise.reject("START_SCANNING_ERROR", e.message, e)
            }
        }
    }

    override fun stopScanning(promise: Promise) {
        reactApplicationContext.runOnUiQueueThread {
            try {
                beaconManager.stopMonitoring(region)
                stopRangingInternal()
                
                beaconManager.removeAllMonitorNotifiers()
                beaconManager.removeAllRangeNotifiers()
                
                isScanning.set(false)
                promise.resolve(true)
            } catch (e: Exception) {
                Log.e(TAG, "Error in stopScanning", e)
                promise.reject("STOP_SCANNING_ERROR", e.message, e)
            }
        }
    }

    override fun startRanging(regionMap: ReadableMap) {
        val targetRegion = regionFromMap(regionMap)
        reactApplicationContext.runOnUiQueueThread {
            startRangingInternal(targetRegion)
        }
    }

    override fun stopRanging(regionMap: ReadableMap) {
        reactApplicationContext.runOnUiQueueThread {
            stopRangingInternal()
        }
    }

    @ReactMethod
    fun enableBackgroundMode(enable: Boolean, promise: Promise) {
        try {
            setBackgroundModeInternal(enable)
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("BACKGROUND_MODE_ERROR", e.message)
        }
    }

    @ReactMethod
    fun getBackgroundMode(promise: Promise) {
        promise.resolve(loadBackgroundModeSetting())
    }

    @ReactMethod
    fun getAuthorizationStatus(promise: Promise) {
        promise.resolve("allowed")
    }

    @ReactMethod
    fun runScan(uuid: String, promise: Promise) {
        promise.resolve(true)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stato Hardware (Bluetooth & Location)
    // ─────────────────────────────────────────────────────────────────────────

    private fun getBluetoothStateInternal(): String {
        return try {
            val bluetoothManager = reactApplicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = bluetoothManager.adapter
            when {
                adapter == null -> "not_supported"
                adapter.isEnabled -> "on"
                else -> "off"
            }
        } catch (e: Exception) {
            "error"
        }
    }

    private fun getLocationStateInternal(): String {
        return try {
            val locationManager = reactApplicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            if (isGpsEnabled || isNetworkEnabled) "on" else "off"
        } catch (e: Exception) {
            "error"
        }
    }

    @ReactMethod
    override fun getBluetoothAndLocationState(promise: Promise) {
        val result = Arguments.createMap().apply {
            putString("bluetooth", getBluetoothStateInternal())
            putString("location", locationStateInternal())
        }
        promise.resolve(result)
    }

    @ReactMethod
    override fun getBluetoothState(promise: Promise) = promise.resolve(getBluetoothStateInternal())

    @ReactMethod
    override fun getLocationState(promise: Promise) = promise.resolve(getLocationStateInternal())

    // ─────────────────────────────────────────────────────────────────────────
    // MonitorNotifier Implementation
    // ─────────────────────────────────────────────────────────────────────────

    override fun didEnterRegion(enteredRegion: Region) {
        reactApplicationContext.runOnUiQueueThread {
            startRangingInternal(enteredRegion)
        }
        emitRegionEvent("didEnterRegion", enteredRegion)
    }

    override fun didExitRegion(exitedRegion: Region) {
        reactApplicationContext.runOnUiQueueThread {
            stopRangingInternal()
        }
        emitRegionEvent("didExitRegion", exitedRegion)
    }

    override fun didDetermineStateForRegion(state: Int, determinedRegion: Region) {
        if (state == MonitorNotifier.INSIDE) {
            reactApplicationContext.runOnUiQueueThread {
                startRangingInternal(determinedRegion)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Ranging Lifecycle Management (Guarded)
    // ─────────────────────────────────────────────────────────────────────────

    private fun startRangingInternal(targetRegion: Region) {
        if (isRanging.get()) return
        try {
            beaconManager.startRangingBeacons(targetRegion)
            isRanging.set(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting ranging", e)
        }
    }

    private fun stopRangingInternal() {
        if (!isRanging.get()) return
        try {
            beaconManager.stopRangingBeacons(region)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping ranging", e)
        } finally {
            isRanging.set(false)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RangeNotifier Implementation & Notifications
    // ─────────────────────────────────────────────────────────────────────────

    override fun didRangeBeaconsInRegion(beacons: Collection<Beacon>, rangedRegion: Region) {
        if (beacons.isEmpty()) return

        // Filtra i beacon visti negli ultimi 10 secondi per evitare dati obsoleti
        val recentBeacons = beacons.filter { 
            (System.currentTimeMillis() - it.lastCycleDetectionTimestamp) < 10000 
        }

        if (recentBeacons.isNotEmpty()) {
            val nearestBeacon = recentBeacons.minByOrNull { it.distance }
            val nearestDistance = nearestBeacon?.let { 
                if (it.distance < 0 && it.rssi != 0) calculateDistance(it.txPower, it.rssi) else it.distance 
            } ?: Double.MAX_VALUE

            // Se siamo fuori dal raggio massimo impostato, scarta il payload
            if (nearestDistance > MAX_DISTANCE) return

            // Se l'app è in background invia la notifica push locale ed esegue il trigger di risveglio
            if (beaconManager.isBackgroundMode) {
                nearestBeacon?.let { sendBeaconNotification(it) }
            }

            reactApplicationContext.runOnUiQueueThread {
                val beaconArray = Arguments.createArray()
                
                recentBeacons.forEach { beacon ->
                    val finalDistance = if (beacon.distance < 0 && beacon.rssi != 0) {
                        calculateDistance(beacon.txPower, beacon.rssi)
                    } else {
                        beacon.distance
                    }

                    val beaconMap = Arguments.createMap().apply {
                        putString("uuid", beacon.id1?.toString() ?: "")
                        putString("major", beacon.id2?.toString() ?: "")
                        putString("minor", beacon.id3?.toString() ?: "")
                        putDouble("distance", finalDistance)
                        putInt("rssi", beacon.rssi)
                        putInt("txPower", beacon.txPower)
                        putString("bluetoothName", beacon.bluetoothName ?: "")
                        putString("bluetoothAddress", beacon.bluetoothAddress ?: "")
                        putInt("manufacturer", beacon.manufacturer)
                        putDouble("timestamp", beacon.lastCycleDetectionTimestamp.toDouble())
                    }
                    beaconArray.pushMap(beaconMap)
                }
                
                val payload = Arguments.createMap().apply {
                    putArray("beacons", beaconArray)
                    putString("uuid", rangedRegion.id1?.toString() ?: "")
                    putString("identifier", rangedRegion.uniqueId)
                }
                
                emitEventMessage("onBeaconsDetected", payload)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Servizi Background & Notifiche Native
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupForegroundService() {
        try {
            val builder = NotificationCompat.Builder(reactApplicationContext, FOREGROUND_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(FOREGROUND_NOTIFICATION_NAME)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

            val intent = reactApplicationContext.packageManager.getLaunchIntentForPackage(reactApplicationContext.packageName)
                ?: Intent().apply { setPackage(reactApplicationContext.packageName) }

            val pendingIntent = PendingIntent.getActivity(
                reactApplicationContext, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setContentIntent(pendingIntent)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    FOREGROUND_NOTIFICATION_CHANNEL_ID, FOREGROUND_NOTIFICATION_NAME, NotificationManager.IMPORTANCE_LOW
                ).apply { description = FOREGROUND_NOTIFICATION_DESCRIPTION }
                
                val manager = reactApplicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(channel)
            }

            beaconManager.enableForegroundServiceScanning(builder.build(), FOREGROUND_NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.e(TAG, "Foreground service restriction", e)
        }
    }

    private fun sendBeaconNotification(beacon: Beacon) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID, "Beacon Detector", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = reactApplicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        launchApp()

        val launchIntent = reactApplicationContext.packageManager.getLaunchIntentForPackage(reactApplicationContext.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            reactApplicationContext, (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
            launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(reactApplicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Beacon Appiattito Rilevato")
            .setContentText("Distanza stimata: ${String.format("%.2f", beacon.distance)}m")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        val manager = reactApplicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(System.currentTimeMillis().toInt(), builder.build())
    }

    fun launchApp() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(reactApplicationContext)) return
            val launchIntent = reactApplicationContext.packageManager.getLaunchIntentForPackage(reactApplicationContext.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            }
            if (launchIntent != null) reactApplicationContext.startActivity(launchIntent)
        } catch (e: Exception) {
            Log.e(TAG, "App wakeup failed", e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // New Architecture (TurboModules) - Requisiti di Conformità Strutturale
    // ─────────────────────────────────────────────────────────────────────────

    @ReactMethod
    override fun addListener(eventName: String) {
        // Obbligatorio per C++ TurboModule Spec. Lasciare vuoto per evitare log-warning.
    }

    @ReactMethod
    override fun removeListeners(count: Double) {
        // Parametro Double richiesto esplicitamente dal codegen C++ di React Native
    }

    override fun removeAllListeners(event: String) {
        // No-op di conformità spec
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utility Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun emitEventMessage(event: String, params: WritableMap) {
        reactApplicationContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(event, params)
    }

    private fun emitRegionEvent(event: String, targetRegion: Region) {
        val params = Arguments.createMap().apply {
            putString("identifier", targetRegion.uniqueId)
            putString("uuid", targetRegion.id1?.toString() ?: "")
            putString("major", targetRegion.id2?.toString() ?: "")
            putString("minor", targetRegion.id3?.toString() ?: "")
        }
        emitEventMessage(event, params)
    }

    private fun calculateDistance(txPower: Int, rssi: Int): Double {
        if (rssi == 0) return -1.0
        return Math.pow(10.0, (txPower - rssi) / 20.0)
    }

    private fun regionFromMap(map: ReadableMap): Region {
        val identifier = if (map.hasKey("identifier")) map.getString("identifier") else "all-beacons"
        val uuid = if (map.hasKey("uuid")) map.getString("uuid")?.let { Identifier.parse(it) } else null
        val major = if (map.hasKey("major")) map.getString("major")?.let { Identifier.parse(it) } else null
        val minor = if (map.hasKey("minor")) map.getString("minor")?.let { Identifier.parse(it) } else null
        return Region(identifier ?: "all-beacons", uuid, major, minor)
    }
}

// Receiver globale per l'ascolto del riavvio del dispositivo Android
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            BeaconRadarModule.instance?.startScanning(
                "YOUR-DEFAULT-UUID-IF-NEEDED", 
                Arguments.createMap(), 
                object : Promise {
                    override fun resolve(value: Any?) {}
                    override fun reject(code: String, message: String?) {}
                    override fun reject(code: String, throwable: Throwable?) {}
                    override fun reject(code: String, message: String?, throwable: Throwable?) {}
                    @Deprecated("Deprecated in Java") override fun reject(message: String?) {}
                    override fun reject(throwable: Throwable?) {}
                    override fun reject(code: String, message: String?, userInfo: WritableMap) {}
                    override fun reject(code: String, throwable: Throwable?, userInfo: WritableMap) {}
                    override fun reject(code: String, message: String?, throwable: Throwable?, userInfo: WritableMap) {}
                }
            )
        }
    }
}
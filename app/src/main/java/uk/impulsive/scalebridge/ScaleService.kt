package uk.impulsive.scalebridge

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * Foreground service that keeps listening for the IF_xxx Telink scale even when
 * the app is closed, and writes each stable weight to Health Connect.
 *
 * Lifecycle: scan (low power) -> connect when the scale wakes -> read stable
 * weight -> write Health Connect -> on disconnect (scale sleeps) go back to scan.
 */
class ScaleService : Service() {

    companion object {
        private const val TAG = "ScaleBridge"
        private const val CHANNEL_ID = "scale_bridge"
        private const val NOTIF_ID = 1
        private const val TARGET_NAME_PREFIX = "IF_"
        const val ACTION_STOP = "uk.impulsive.scalebridge.STOP"

        private val SVC_FFF0: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        private val CHR_FFF1: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    private val btManager by lazy { getSystemService(BluetoothManager::class.java) }
    private val adapter by lazy { btManager?.adapter }
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var connected = false
    private var armed = true

    private var hc: HealthConnectClient? = null
    private val writePerm = HealthPermission.getWritePermission(WeightRecord::class)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        if (HealthConnectClient.getSdkStatus(this) == HealthConnectClient.SDK_AVAILABLE) {
            hc = HealthConnectClient.getOrCreate(this)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification("Aștept cântarul…"))
        startScan()
        return START_STICKY
    }

    override fun onDestroy() {
        stopScan()
        closeGatt()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------- notification

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Scale Bridge", NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, ScaleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Scale Bridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Stop", stopPi)
            .build()
    }

    private fun note(text: String) {
        Log.d(TAG, text)
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        }
    }

    // ------------------------------------------------------------- BLE scan

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (scanning || connected) return
        val a = adapter
        if (a == null || !a.isEnabled) {
            note("Bluetooth oprit — pornește-l.")
            return
        }
        scanner = a.bluetoothLeScanner ?: return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        scanning = true
        runCatching { scanner?.startScan(null, settings, scanCallback) }
        note("Aștept cântarul…")
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { scanner?.stopScan(scanCallback) }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: result.scanRecord?.deviceName ?: return
            if (name.startsWith(TARGET_NAME_PREFIX) && !connected) {
                stopScan()
                note("Conectez la $name…")
                gatt = result.device.connectGatt(
                    this@ScaleService, false, gattCallback, BluetoothDevice.TRANSPORT_LE
                )
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            note("Scanare eșuată ($errorCode). Reîncerc…")
            handler.postDelayed({ startScan() }, 5000)
        }
    }

    // ------------------------------------------------------------- BLE connection

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        gatt?.let { runCatching { it.disconnect() }; runCatching { it.close() } }
        gatt = null
        connected = false
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connected = true
                    armed = true
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connected = false
                    runCatching { g.close() }
                    if (gatt === g) gatt = null
                    // scale went to sleep — resume scanning for the next weigh-in
                    handler.postDelayed({ startScan() }, 1500)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ch = g.getService(SVC_FFF0)?.getCharacteristic(CHR_FFF1)
            if (ch == null) {
                note("0xFFF1 negăsit.")
                return
            }
            g.setCharacteristicNotification(ch, true)
            val desc = ch.getDescriptor(CCCD) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                run {
                    desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(desc)
                }
            }
            note("Conectat. Urcă pe cântar.")
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray
        ) {
            handleFrame(value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handleFrame(ch.value ?: return)
        }
    }

    // ------------------------------------------------------------- frame -> Health Connect

    private fun handleFrame(data: ByteArray) {
        if (data.size < 13) return
        val flag = data[12].toInt() and 0xFF
        if (flag == 0xA0) { armed = true; return }
        if (flag != 0xAA || !armed) return

        val raw = ((data[7].toInt() and 0xFF) shl 8) or (data[8].toInt() and 0xFF)
        val kg = raw / 100.0
        if (kg <= 1.0 || kg > 300.0) return

        armed = false
        note("Greutate: %.1f kg — scriu…".format(kg))
        writeWeight(kg)
    }

    private fun writeWeight(kg: Double) {
        val client = hc ?: run { note("Health Connect indisponibil."); return }
        scope.launch {
            try {
                if (!client.permissionController.getGrantedPermissions().contains(writePerm)) {
                    note("Lipsește permisiunea Health Connect — deschide app-ul și acord-o.")
                    return@launch
                }
                val now = Instant.now()
                val record = WeightRecord(
                    metadata = Metadata.manualEntry(),
                    time = now,
                    zoneOffset = ZoneId.systemDefault().rules.getOffset(now),
                    weight = Mass.kilograms(kg)
                )
                client.insertRecords(listOf(record))
                note("Scris în Health Connect: %.1f kg ✓".format(kg))
            } catch (e: Exception) {
                Log.e(TAG, "Health Connect write failed", e)
                note("Eroare Health Connect: ${e.message}")
            }
        }
    }
}

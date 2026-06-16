package uk.impulsive.scalebridge

import android.Manifest
import android.annotation.SuppressLint
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
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import uk.impulsive.scalebridge.databinding.ActivityMainBinding
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * Scale Bridge — reads weight from the "IF_xxx" Telink BLE scale and writes it
 * to Health Connect, where Zepp (>= 9.10.0) picks it up automatically.
 *
 * Protocol (reverse-engineered from device IF_B6A):
 *   Service  0xFFF0, notify characteristic 0xFFF1.
 *   Frame (14 bytes): 10 00 00 C5 0E 03 80 [W_hi W_lo] [imp_hi imp_lo] 11 [flag] [cksum]
 *     - weight = ((data[7] << 8) | data[8]) / 100.0  kg
 *     - data[12]: 0xA0 = live/unstable, 0xAA = stable
 *   The scale streams on its own; no write to 0xFFF2 is needed.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ScaleBridge"
        private const val TARGET_NAME_PREFIX = "IF_"
        private const val SCAN_TIMEOUT_MS = 30_000L

        private val SVC_FFF0: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        private val CHR_FFF1: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private lateinit var binding: ActivityMainBinding

    private lateinit var hc: HealthConnectClient
    private var hcAvailable = false
    private val hcPermissions = setOf(HealthPermission.getWritePermission(WeightRecord::class))

    private val btManager by lazy { getSystemService(BluetoothManager::class.java) }
    private val adapter by lazy { btManager?.adapter }
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var scanning = false

    // Write once per weighing session: re-arm on live frames, fire on the first stable frame.
    private var armed = true

    private val handler = Handler(Looper.getMainLooper())

    private val requestHcPermissions =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            status(
                if (granted.containsAll(hcPermissions)) "Health Connect: permisiune OK."
                else "Health Connect: permisiune REFUZATĂ."
            )
        }

    private val requestBtPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) startScan()
            else status("Permisiuni Bluetooth refuzate.")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnStart.setOnClickListener { ensureBtThenScan() }
        binding.btnStop.setOnClickListener { stopAll() }

        setupHealthConnect()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAll()
    }

    // ---------------------------------------------------------------- Health Connect

    private fun setupHealthConnect() {
        val sdkStatus = HealthConnectClient.getSdkStatus(this)
        if (sdkStatus != HealthConnectClient.SDK_AVAILABLE) {
            hcAvailable = false
            status("Health Connect indisponibil (status=$sdkStatus). Instalează/activează Health Connect.")
            return
        }
        hc = HealthConnectClient.getOrCreate(this)
        hcAvailable = true
        lifecycleScope.launch {
            val granted = hc.permissionController.getGrantedPermissions()
            if (!granted.containsAll(hcPermissions)) {
                requestHcPermissions.launch(hcPermissions)
            } else {
                status("Health Connect gata. Apasă START și urcă pe cântar.")
            }
        }
    }

    private fun writeWeight(kg: Double) {
        if (!hcAvailable) {
            runOnUiThread { status("Greutate %.1f kg — dar Health Connect indisponibil.".format(kg)) }
            return
        }
        lifecycleScope.launch {
            try {
                val granted = hc.permissionController.getGrantedPermissions()
                if (!granted.containsAll(hcPermissions)) {
                    requestHcPermissions.launch(hcPermissions)
                    return@launch
                }
                val now = Instant.now()
                val record = WeightRecord(
                    metadata = Metadata.manualEntry(),
                    time = now,
                    zoneOffset = ZoneId.systemDefault().rules.getOffset(now),
                    weight = Mass.kilograms(kg)
                )
                hc.insertRecords(listOf(record))
                status("Scris în Health Connect: %.1f kg ✓".format(kg))
            } catch (e: Exception) {
                Log.e(TAG, "Health Connect write failed", e)
                status("Eroare scriere Health Connect: ${e.message}")
            }
        }
    }

    // ---------------------------------------------------------------- BLE scan

    private fun requiredBtPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasBtPermissions(): Boolean = requiredBtPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureBtThenScan() {
        val a = adapter
        if (a == null || !a.isEnabled) {
            status("Pornește Bluetooth-ul și reîncearcă.")
            return
        }
        if (hasBtPermissions()) startScan()
        else requestBtPermissions.launch(requiredBtPermissions())
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (scanning) return
        scanner = adapter?.bluetoothLeScanner ?: run {
            status("Scanner BLE indisponibil.")
            return
        }
        scanning = true
        armed = true
        status("Scanez după $TARGET_NAME_PREFIX… urcă pe cântar.")
        scanner?.startScan(scanCallback)
        handler.postDelayed({
            if (scanning && gatt == null) {
                stopScan()
                status("Cântarul nu a fost găsit. Urcă pe el (să se trezească) și apasă START.")
            }
        }, SCAN_TIMEOUT_MS)
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
            if (name.startsWith(TARGET_NAME_PREFIX)) {
                status("Găsit: $name. Conectez…")
                stopScan()
                connect(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            status("Scanare eșuată (cod $errorCode).")
        }
    }

    // ---------------------------------------------------------------- BLE connection

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    status("Conectat. Descopăr servicii…")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    status("Deconectat.")
                    g.close()
                    if (gatt === g) gatt = null
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) {
            val ch = g.getService(SVC_FFF0)?.getCharacteristic(CHR_FFF1)
            if (ch == null) {
                status("Characteristic 0xFFF1 negăsit pe acest cântar.")
                return
            }
            g.setCharacteristicNotification(ch, true)
            val desc = ch.getDescriptor(CCCD)
            if (desc != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(desc)
                }
            }
            status("Gata. Urcă pe cântar și ține până se stabilizează.")
        }

        // Android 13+ delivers the value directly.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleFrame(value)
        }

        // Legacy path (Android 12 and below).
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handleFrame(ch.value ?: return)
        }
    }

    // ---------------------------------------------------------------- Frame parsing

    private fun handleFrame(data: ByteArray) {
        if (data.size < 13) return
        val flag = data[12].toInt() and 0xFF
        if (flag == 0xA0) {            // live/settling — re-arm for the next stable reading
            armed = true
            return
        }
        if (flag != 0xAA) return       // only act on the stable frame
        if (!armed) return

        val raw = ((data[7].toInt() and 0xFF) shl 8) or (data[8].toInt() and 0xFF)
        val kg = raw / 100.0
        if (kg <= 1.0 || kg > 300.0) return   // sanity guard

        armed = false
        runOnUiThread { status("Greutate stabilă: %.1f kg".format(kg)) }
        writeWeight(kg)
    }

    // ---------------------------------------------------------------- Teardown / UI

    @SuppressLint("MissingPermission")
    private fun stopAll() {
        stopScan()
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
    }

    private fun status(msg: String) {
        Log.d(TAG, msg)
        runOnUiThread { binding.txtStatus.text = msg }
    }
}

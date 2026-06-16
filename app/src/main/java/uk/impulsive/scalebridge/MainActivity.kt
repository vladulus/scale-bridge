package uk.impulsive.scalebridge

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.WeightRecord
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import uk.impulsive.scalebridge.databinding.ActivityMainBinding

/**
 * Thin UI: requests the needed permissions, then starts/stops the background
 * ScaleService. Once started, the phone can close the app — the service keeps
 * listening for the scale and writing to Health Connect.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val hcPermissions = setOf(HealthPermission.getWritePermission(WeightRecord::class))

    private val requestHc =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            status(
                if (granted.containsAll(hcPermissions)) "Health Connect OK. Serviciul rulează — poți închide app-ul."
                else "Health Connect REFUZAT — fără el nu poate scrie greutatea."
            )
        }

    private val requestRuntime =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) proceedStart()
            else status("Permisiuni refuzate. Acordă Bluetooth + Notificări.")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnStart.setOnClickListener { onStartClicked() }
        binding.btnStop.setOnClickListener { stopService() }

        val sdk = HealthConnectClient.getSdkStatus(this)
        if (sdk != HealthConnectClient.SDK_AVAILABLE) {
            status("Health Connect indisponibil (status=$sdk). Instalează/activează-l, apoi START.")
        }
    }

    // ----------------------------------------------------------- start flow

    private fun onStartClicked() {
        val needed = runtimePermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) requestRuntime.launch(needed.toTypedArray())
        else proceedStart()
    }

    private fun runtimePermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun proceedStart() {
        ensureHcPermission()
        startService()
        requestBatteryExemption()
    }

    private fun ensureHcPermission() {
        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) return
        val client = HealthConnectClient.getOrCreate(this)
        lifecycleScope.launch {
            val granted = client.permissionController.getGrantedPermissions()
            if (!granted.containsAll(hcPermissions)) requestHc.launch(hcPermissions)
        }
    }

    private fun startService() {
        ContextCompat.startForegroundService(this, Intent(this, ScaleService::class.java))
        status("Serviciu pornit. Urcă pe cântar — poți închide app-ul.")
    }

    private fun stopService() {
        stopService(Intent(this, ScaleService::class.java))
        status("Serviciu oprit.")
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }
    }

    private fun status(msg: String) {
        binding.txtStatus.text = msg
    }
}

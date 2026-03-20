package com.cubeos.meshsat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.cubeos.meshsat.ui.MeshSatUI
import com.cubeos.meshsat.ui.theme.MeshSatTheme

class MainActivity : ComponentActivity() {

    private val requiredPermissions: Array<String>
        get() {
            val perms = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                perms.add(Manifest.permission.BLUETOOTH_SCAN)
                perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            return perms.toTypedArray()
        }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Permissions resolved (granted or denied) — now safe to start the service
        startGatewayService()
    }

    private var serviceStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            // Request permissions first — service starts in the callback
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            // All permissions already granted — start immediately
            startGatewayService()
        }

        setContent {
            MeshSatTheme {
                MeshSatUI()
            }
        }
    }

    private fun startGatewayService() {
        if (serviceStarted) return
        serviceStarted = true
        try {
            startForegroundService(Intent(this, com.cubeos.meshsat.service.GatewayService::class.java))
        } catch (e: Exception) {
            android.util.Log.e("MeshSat", "Failed to start gateway service: ${e.message}")
        }
    }
}

package net.meshsat.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import net.meshsat.android.crypto.ProvisionImporter
import net.meshsat.android.ui.MeshSatUI
import net.meshsat.android.ui.screens.WelcomeScreen
import net.meshsat.android.ui.components.ProvisionClaimHost
import net.meshsat.android.ui.components.ProvisionLinkDialog
import net.meshsat.android.ui.theme.MeshSatTheme
import net.meshsat.android.ui.theme.NightModeEffect

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
        // The app is always dark, so the system bars always carry light icons over the app's own
        // colours; the default followed the phone's light mode and drew dark icons on dark.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            // Say what each permission is for before Android asks; the service starts in the
            // permission callback (MESHSAT-1249).
            welcome.value = missing
        } else {
            // All permissions already granted — start immediately
            startGatewayService()
        }

        takeProvisionLink(intent)
        takeRoute(intent)
        setContent {
            MeshSatTheme {
                NightModeEffect()
                val ask = welcome.value
                if (ask != null) {
                    WelcomeScreen(onContinue = {
                        welcome.value = null
                        permissionLauncher.launch(ask.toTypedArray())
                    })
                    return@MeshSatTheme
                }
                MeshSatUI(openRoute = openRoute.value, onRouteOpened = { openRoute.value = null })
                provisionLink.value?.let { url ->
                    ProvisionLinkDialog(url = url, onDone = { provisionLink.value = null })
                }
                ProvisionClaimHost()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeProvisionLink(intent)
        takeRoute(intent)
    }

    /** The permissions still to ask for, while the welcome that explains them shows. */
    private val welcome = mutableStateOf<List<String>?>(null)

    /** A screen a notification asks to open, e.g. the SOS result screen (MESHSAT-1249). */
    private val openRoute = mutableStateOf<String?>(null)

    private fun takeRoute(intent: Intent?) {
        val route = intent?.getStringExtra(EXTRA_ROUTE) ?: return
        if (route in OPENABLE_ROUTES) openRoute.value = route
    }

    companion object {
        const val EXTRA_ROUTE = "net.meshsat.android.ROUTE"
        private val OPENABLE_ROUTES = setOf("sos", "messages", "home")
    }

    /** A `meshsat://provision/` link, confirmed by [ProvisionLinkDialog] (MESHSAT-1235). */
    private val provisionLink = mutableStateOf<String?>(null)

    private fun takeProvisionLink(intent: Intent?) {
        val url = intent?.dataString ?: return
        if (ProvisionImporter.isProvisionUrl(url)) provisionLink.value = url
    }

    private fun startGatewayService() {
        if (serviceStarted) return
        serviceStarted = true
        try {
            startForegroundService(Intent(this, net.meshsat.android.service.GatewayService::class.java))
        } catch (e: Exception) {
            android.util.Log.e("MeshSat", "Failed to start gateway service: ${e.message}")
        }
    }
}

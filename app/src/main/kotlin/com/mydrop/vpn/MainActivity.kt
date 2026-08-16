package com.mydrop.vpn

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mydrop.vpn.ui.MainViewModel
import com.mydrop.vpn.ui.MyDropApp
import com.mydrop.vpn.ui.theme.MyDropTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        MainViewModel.Factory((application as MyDropApplication).container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        handleIntent(intent)
        requestNotificationPermissionIfNeeded()

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()

            // The system VPN consent dialog can only be launched from an Activity, so the
            // ViewModel hands the intent up and the answer is routed back to it.
            val vpnPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { result ->
                viewModel.onVpnPermissionResult(result.resultCode == Activity.RESULT_OK)
            }
            LaunchedEffect(Unit) {
                viewModel.permissionRequests.collect { vpnPermissionLauncher.launch(it) }
            }

            MyDropTheme(
                themeMode = state.settings.themeMode,
                dynamicColor = state.settings.dynamicColor,
                amoled = state.settings.amoled,
            ) {
                MyDropApp(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * Accepts everything the manifest advertises: `happ://` deep links, bare proxy share-links
     * opened from a QR scanner, and plain text shared from another app.
     */
    private fun handleIntent(intent: Intent?) {
        val payload = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }
        if (!payload.isNullOrBlank()) viewModel.importText(payload)
    }

    /** Without this the tunnel's foreground notification is silently dropped on Android 13+. */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

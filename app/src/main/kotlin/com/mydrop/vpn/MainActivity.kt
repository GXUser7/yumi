package com.mydrop.vpn

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
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
import com.mydrop.vpn.core.model.AppLanguage
import com.mydrop.vpn.ui.MainViewModel
import com.mydrop.vpn.ui.MyDropApp
import com.mydrop.vpn.ui.theme.MyDropTheme
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        MainViewModel.Factory((application as MyDropApplication).container)
    }

    /**
     * The language this instance was built with, so a change can be told from a redraw.
     *
     * Read once, in [onCreate], rather than on every recomposition: `attachBaseContext` has
     * already resolved the resources against it and they cannot be swapped underneath a running
     * activity, so the only honest response to a change is to start over.
     */
    private var builtWithLanguage: AppLanguage = AppLanguage.System

    /**
     * Resolves resources against the chosen language rather than the phone's.
     *
     * `localeConfig` and `LocaleManager` are deliberately not used. They would put the setting in
     * the system's hands on Android 13+ and in ours below it, which is two owners for one value —
     * and the app already keeps every other preference in its own store. One mechanism, every API
     * level, one place to read it from.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(localized(newBase))
    }

    private fun localized(base: Context): Context {
        val tag = runCatching {
            (base.applicationContext as MyDropApplication).container.settings.value.language.tag
        }.getOrNull() ?: return base

        val configuration = Configuration(base.resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag(tag)))
        }
        return base.createConfigurationContext(configuration)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        builtWithLanguage = (application as MyDropApplication).container.settings.value.language

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

            // Resources are bound to the activity's context, so a new language means a new
            // activity. Recreating is what makes the whole screen — including the pieces drawn
            // before this effect runs — speak the language that was just chosen.
            LaunchedEffect(state.settings.language) {
                if (state.settings.language != builtWithLanguage) recreate()
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
        // Not importText: this arrived from another app, and the difference decides whether the
        // user is asked first. See MainViewModel.importFromExternalLink.
        if (!payload.isNullOrBlank()) viewModel.importFromExternalLink(payload)
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

package com.mydrop.vpn.tv

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mydrop.vpn.MyDropApplication
import com.mydrop.vpn.core.model.AppLanguage
import com.mydrop.vpn.ui.theme.MyDropTheme
import java.util.Locale

class TvActivity : ComponentActivity() {
    private val viewModel: TvViewModel by viewModels {
        val container = (application as MyDropApplication).container
        TvViewModel.Factory(container, applicationContext)
    }
    private var builtWithLanguage = AppLanguage.System

    override fun attachBaseContext(newBase: Context) {
        val tag = runCatching {
            (newBase.applicationContext as MyDropApplication).container.settings.value.language.tag
        }.getOrNull()
        if (tag == null) {
            super.attachBaseContext(newBase)
        } else {
            val config = Configuration(newBase.resources.configuration).apply {
                setLocales(LocaleList(Locale.forLanguageTag(tag)))
            }
            super.attachBaseContext(newBase.createConfigurationContext(config))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        builtWithLanguage = (application as MyDropApplication).container.settings.value.language
        requestNotifications()

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val consent = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { result -> viewModel.onVpnPermissionResult(result.resultCode == Activity.RESULT_OK) }
            LaunchedEffect(Unit) {
                viewModel.permissionRequests.collect { consent.launch(it) }
            }
            LaunchedEffect(state.settings.language) {
                if (state.settings.language != builtWithLanguage) recreate()
            }
            MyDropTheme(
                themeMode = state.settings.themeMode,
                dynamicColor = false,
                amoled = false,
            ) {
                YumiTvApp(viewModel)
            }
        }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

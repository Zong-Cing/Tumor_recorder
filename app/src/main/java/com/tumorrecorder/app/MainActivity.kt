package com.tumorrecorder.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tumorrecorder.app.service.CallRecorderAccessibilityService
import com.tumorrecorder.app.ui.navigation.AppNavHost
import com.tumorrecorder.app.ui.theme.TumorRecorderTheme

class MainActivity : ComponentActivity() {

    private var hasRequiredPermissions by mutableStateOf(false)
    private var accessibilityEnabledInSettings by mutableStateOf(false)
    private var openRecordId by mutableStateOf<Long?>(null)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasRequiredPermissions = checkRequiredPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshSystemStates()
        handleIntent(intent)

        val container = (application as TumorRecorderApp).container

        setContent {
            val liveA11yConnected by CallRecorderAccessibilityService.isConnected.collectAsStateWithLifecycle()
            TumorRecorderTheme {
                AppNavHost(
                    container = container,
                    initialRecordId = openRecordId,
                    hasRequiredPermissions = hasRequiredPermissions,
                    isAccessibilityEnabled = liveA11yConnected || accessibilityEnabledInSettings,
                    onRequestPermissions = { requestMissingPermissions() },
                    onOpenAccessibilitySettings = { openAccessibilitySettings() },
                    onOpenAppDetailsSettings = { openAppDetailsSettings() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSystemStates()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun refreshSystemStates() {
        hasRequiredPermissions = checkRequiredPermissions()
        accessibilityEnabledInSettings = CallRecorderAccessibilityService.isServiceEnabled(this)
    }

    private fun handleIntent(intent: Intent?) {
        val id = intent?.getLongExtra(EXTRA_OPEN_RECORD_ID, -1L) ?: -1L
        if (id > 0L) {
            openRecordId = id
        }
    }

    private fun requiredPermissions(): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.READ_CALL_LOG)
        add(Manifest.permission.CALL_PHONE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun checkRequiredPermissions(): Boolean {
        val critical = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        return critical.all { perm ->
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestMissingPermissions() {
        val missing = requiredPermissions().filter { perm ->
            ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            hasRequiredPermissions = true
        }
    }

    private fun openAccessibilitySettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun openAppDetailsSettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
            )
        }
    }

    companion object {
        const val EXTRA_OPEN_RECORD_ID = "extra_open_record_id"
    }
}

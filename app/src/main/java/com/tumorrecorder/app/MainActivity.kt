package com.tumorrecorder.app

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tumorrecorder.app.service.CallRecorderAccessibilityService
import com.tumorrecorder.app.service.CallRecordingService
import com.tumorrecorder.app.ui.navigation.AppNavHost
import com.tumorrecorder.app.ui.theme.TumorRecorderTheme

class MainActivity : ComponentActivity() {

    private var hasRequiredPermissions by mutableStateOf(false)
    private var accessibilityEnabledInSettings by mutableStateOf(false)
    private var isDefaultDialer by mutableStateOf(false)
    private var openRecordId by mutableStateOf<Long?>(null)
    private var incomingDialNumber by mutableStateOf<String?>(null)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasRequiredPermissions = checkRequiredPermissions()
    }

    private val defaultDialerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        isDefaultDialer = checkIsDefaultDialer()
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
                    initialDialNumber = incomingDialNumber,
                    onConsumeInitialDialNumber = { incomingDialNumber = null },
                    hasRequiredPermissions = hasRequiredPermissions,
                    isAccessibilityEnabled = liveA11yConnected || accessibilityEnabledInSettings,
                    isDefaultDialer = isDefaultDialer,
                    onRequestPermissions = { requestMissingPermissions() },
                    onRequestDefaultDialer = { requestDefaultDialerRole() },
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
        isDefaultDialer = checkIsDefaultDialer()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        val id = intent.getLongExtra(EXTRA_OPEN_RECORD_ID, -1L)
        if (id > 0L) {
            openRecordId = id
        }

        val confirmId = intent.getLongExtra(EXTRA_CONFIRM_RECORD_ID, -1L)
        if (confirmId > 0L) {
            CallRecordingService.requestConfirmForRecord(confirmId)
        }

        if (intent.action == Intent.ACTION_DIAL || intent.action == Intent.ACTION_VIEW) {
            val telData = intent.data
            if (telData?.scheme == "tel") {
                val number = telData.schemeSpecificPart.orEmpty().trim()
                if (number.isNotEmpty()) {
                    incomingDialNumber = number
                }
            }
        }
    }

    private fun checkIsDefaultDialer(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_DIALER) == true
        } else {
            getSystemService(TelecomManager::class.java)?.defaultDialerPackage == packageName
        }
    }

    @Suppress("DEPRECATION")
    private fun requestDefaultDialerRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java) ?: return
            if (roleManager.isRoleAvailable(RoleManager.ROLE_DIALER) &&
                !roleManager.isRoleHeld(RoleManager.ROLE_DIALER)
            ) {
                runCatching {
                    defaultDialerLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER))
                }
            }
        } else {
            val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
            }
            runCatching { defaultDialerLauncher.launch(intent) }
        }
    }

    private fun requiredPermissions(): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.READ_CALL_LOG)
        add(Manifest.permission.CALL_PHONE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            add(Manifest.permission.ANSWER_PHONE_CALLS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun checkRequiredPermissions(): Boolean {
        val critical = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.CALL_PHONE)
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
        const val EXTRA_CONFIRM_RECORD_ID = "extra_confirm_record_id"
    }
}

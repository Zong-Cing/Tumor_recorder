package com.tumorrecorder.app.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Minimal-privilege AccessibilityService explicitly enabled by the user in Android Settings.
 *
 * When active, Android's audio concurrency policy allows this app UID to capture
 * MediaRecorder.AudioSource.VOICE_RECOGNITION concurrently during cellular calls
 * without the OS silencing the microphone stream.
 *
 * Does NOT read screen contents (canRetrieveWindowContent="false") and does NOT inspect UI text.
 */
class CallRecorderAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isConnected.value = true
        Log.i(TAG, "CallRecorderAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally no-op: we do not read or inspect screen content.
        // Call lifecycle is handled by CallStateReceiver + CallRecordingService.
    }

    override fun onInterrupt() {
        Log.i(TAG, "CallRecorderAccessibilityService interrupted")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) {
            instance = null
        }
        _isConnected.value = false
        Log.i(TAG, "CallRecorderAccessibilityService unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        _isConnected.value = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TumorRecorder_A11y"

        @Volatile
        var instance: CallRecorderAccessibilityService? = null
            private set

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        /**
         * Checks both live instance connection and Android's Secure Settings list.
         */
        fun isServiceEnabled(context: Context): Boolean {
            if (_isConnected.value && instance != null) return true
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val expected = ComponentName(context, CallRecorderAccessibilityService::class.java)
                .flattenToString()
            val expectedShort = ComponentName(context, CallRecorderAccessibilityService::class.java)
                .flattenToShortString()
            return enabledServices.split(':').any { entry ->
                entry.equals(expected, ignoreCase = true) ||
                    entry.equals(expectedShort, ignoreCase = true)
            }
        }
    }
}

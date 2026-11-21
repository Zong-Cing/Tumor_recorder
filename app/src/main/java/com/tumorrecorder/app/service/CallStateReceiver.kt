package com.tumorrecorder.app.service

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tumorrecorder.app.R
import com.tumorrecorder.app.TumorRecorderApp
import com.tumorrecorder.app.domain.model.CallDirection

/**
 * Monitors cellular call state transitions (RINGING -> OFFHOOK -> IDLE)
 * and automatically triggers CallRecordingService when enabled in settings.
 */
class CallStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val app = context.applicationContext as? TumorRecorderApp ?: return
        val settings = app.container.settingsRepository.currentSettings()

        // When TumorInCallService (Default Dialer mode) is managing the active call,
        // let CallManager exclusively control recording so private calls are never recorded.
        if (CallManager.isManagedByInCallService) return

        val hasAudioPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasAudioPermission) {
            Log.w(TAG, "RECORD_AUDIO permission not granted; skipping auto-record")
            return
        }

        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        @Suppress("DEPRECATION")
        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        val prefs = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        val lastState = prefs.getString(KEY_LAST_STATE, TelephonyManager.EXTRA_STATE_IDLE)
        var savedNumber = prefs.getString(KEY_SAVED_NUMBER, "") ?: ""
        var isIncoming = prefs.getBoolean(KEY_IS_INCOMING, false)

        if (!incomingNumber.isNullOrBlank()) {
            savedNumber = incomingNumber
            prefs.edit().putString(KEY_SAVED_NUMBER, savedNumber).apply()
            // If recording already started on the first duplicate broadcast without number, update it
            if (CallRecordingService.recordingState.value.isRecording) {
                CallRecordingService.updateActivePhoneNumber(context, savedNumber)
            }
        }

        if (stateStr == lastState) {
            return
        }

        when (stateStr) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                isIncoming = true
                prefs.edit()
                    .putString(KEY_LAST_STATE, stateStr)
                    .putBoolean(KEY_IS_INCOMING, true)
                    .apply()
            }

            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                val direction = if (lastState == TelephonyManager.EXTRA_STATE_RINGING || isIncoming) {
                    CallDirection.INCOMING
                } else {
                    CallDirection.OUTGOING
                }
                prefs.edit()
                    .putString(KEY_LAST_STATE, stateStr)
                    .apply()

                val launchedFromThisApp = direction == CallDirection.OUTGOING &&
                    CallManager.consumePendingAppOutgoingRecord()
                val shouldAutoRecord = launchedFromThisApp ||
                    (!settings.onlyAnalyzeAppCalls && settings.autoRecordCalls)

                if (!shouldAutoRecord) return

                if (!CallRecordingService.recordingState.value.isRecording) {
                    val launchContext = CallRecorderAccessibilityService.instance ?: context
                    try {
                        CallRecordingService.startRecording(
                            context = launchContext,
                            phoneNumber = savedNumber,
                            direction = direction,
                            fromPhoneCall = true
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Background FGS start restricted; posting fallback notification", e)
                        showManualStartNotification(context, savedNumber, direction)
                    }
                }
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                prefs.edit()
                    .putString(KEY_LAST_STATE, stateStr)
                    .putString(KEY_SAVED_NUMBER, "")
                    .putBoolean(KEY_IS_INCOMING, false)
                    .apply()

                if (CallRecordingService.recordingState.value.isRecording) {
                    CallRecordingService.stopRecording(context)
                }
            }
        }
    }

    private fun showManualStartNotification(
        context: Context,
        phoneNumber: String,
        direction: CallDirection
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val startIntent = Intent(context, CallRecordingService::class.java).apply {
            action = CallRecordingService.ACTION_START
            putExtra(CallRecordingService.EXTRA_PHONE_NUMBER, phoneNumber)
            putExtra(CallRecordingService.EXTRA_DIRECTION, direction.name)
            putExtra(CallRecordingService.EXTRA_FROM_PHONE_CALL, true)
        }
        val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                context,
                NOTIFICATION_ID_MANUAL_PROMPT,
                startIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                context,
                NOTIFICATION_ID_MANUAL_PROMPT,
                startIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val label = phoneNumber.ifBlank { direction.label }
        val notification = NotificationCompat.Builder(context, TumorRecorderApp.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("偵測到通話進行中（$label）")
            .setContentText("受 Android 背景權限限制，請點此一鍵開始腫瘤個管通話錄音")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(0, "開始錄音", pendingIntent)
            .build()

        context.getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID_MANUAL_PROMPT, notification)
    }

    companion object {
        private const val TAG = "CallStateReceiver"
        private const val NOTIFICATION_ID_MANUAL_PROMPT = 1099
        private const val STATE_PREFS = "call_state_tracker"
        private const val KEY_LAST_STATE = "last_state"
        private const val KEY_SAVED_NUMBER = "saved_number"
        private const val KEY_IS_INCOMING = "is_incoming"
    }
}

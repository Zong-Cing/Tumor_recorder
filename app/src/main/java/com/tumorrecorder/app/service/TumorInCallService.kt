package com.tumorrecorder.app.service

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.tumorrecorder.app.R
import com.tumorrecorder.app.TumorRecorderApp
import com.tumorrecorder.app.ui.incall.InCallActivity

/**
 * Android Telecom InCallService allowing TumorRecorder to serve as the
 * device's Default Phone App (ROLE_DIALER), presenting native CallStyle
 * incoming/ongoing notifications plus our full-screen InCallActivity UI.
 *
 * Ringtone and vibration are handled natively by Android Telecom because
 * IN_CALL_SERVICE_RINGING is intentionally omitted from AndroidManifest.xml.
 */
class TumorInCallService : InCallService() {

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "TumorInCallService onCreate")
        CallManager.attachService(this)
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        CallManager.onCallAdded(this, call)

        val uiState = CallManager.callState.value
        val number = uiState?.phoneNumber.orEmpty().ifBlank { "未知來電" }
        val isRinging = uiState?.state == Call.STATE_RINGING
        val isIncoming = uiState?.isIncoming == true
        Log.i(TAG, "onCallAdded: number=$number state=${uiState?.state} isIncoming=$isIncoming")

        if (isRinging || isIncoming) {
            onCallRinging(number)
        } else {
            showOngoingCallNotification(number)
            launchInCallScreen()
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        Log.i(TAG, "onCallRemoved")
        cancelCallNotifications()
        CallManager.onCallRemoved(this, call)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        CallManager.onAudioStateChanged(audioState)
    }

    override fun onDestroy() {
        Log.i(TAG, "TumorInCallService onDestroy")
        cancelCallNotifications()
        CallManager.detachService(this)
        super.onDestroy()
    }

    fun onCallRinging(number: String) {
        showIncomingCallNotification(number)
        launchInCallScreen()
    }

    fun onCallActive(number: String) {
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID_INCOMING)
        showOngoingCallNotification(number)
    }

    fun cancelCallNotifications() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(NOTIFICATION_ID_INCOMING)
        nm.cancel(NOTIFICATION_ID_ONGOING)
    }

    private fun launchInCallScreen() {
        val intent = Intent(this, InCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        runCatching { startActivity(intent) }
            .onFailure { e -> Log.w(TAG, "Direct startActivity(InCallActivity) blocked/failed: ${e.message}") }
    }

    private fun showIncomingCallNotification(number: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val canNotify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val canFsi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            nm.canUseFullScreenIntent()
        } else {
            true
        }
        Log.i(TAG, "showIncomingCallNotification: number=$number canNotify=$canNotify canFsi=$canFsi")
        if (!canNotify) return

        val fullScreenPending = buildInCallPendingIntent(REQ_FULL_SCREEN)
        val answerRecordPending = buildActionPendingIntent(
            action = IncomingCallActionReceiver.ACTION_ANSWER_RECORD,
            requestCode = REQ_ANSWER_RECORD
        )
        val answerPrivatePending = buildActionPendingIntent(
            action = IncomingCallActionReceiver.ACTION_ANSWER_PRIVATE,
            requestCode = REQ_ANSWER_PRIVATE
        )
        val declinePending = buildActionPendingIntent(
            action = IncomingCallActionReceiver.ACTION_DECLINE,
            requestCode = REQ_DECLINE
        )

        val caller = Person.Builder()
            .setName(number.ifBlank { "未知來電" })
            .setImportant(true)
            .build()

        val notification = NotificationCompat.Builder(this, TumorRecorderApp.CHANNEL_INCOMING_CALL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("腫瘤個管來電：$number")
            .setContentText("點擊「接聽」自動錄音，或點通知選擇私人不錄音")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    caller,
                    declinePending,
                    answerRecordPending
                )
            )
            .addAction(
                0,
                "私人接聽(不錄音)",
                answerPrivatePending
            )
            .setFullScreenIntent(fullScreenPending, true)
            .setContentIntent(fullScreenPending)
            .build()

        nm.notify(NOTIFICATION_ID_INCOMING, notification)
    }

    private fun showOngoingCallNotification(number: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val contentPending = buildInCallPendingIntent(REQ_ONGOING_CONTENT)
        val hangupPending = buildActionPendingIntent(
            action = IncomingCallActionReceiver.ACTION_HANGUP,
            requestCode = REQ_HANGUP
        )

        val caller = Person.Builder()
            .setName(number.ifBlank { "通話中" })
            .setImportant(true)
            .build()

        val notification = NotificationCompat.Builder(this, TumorRecorderApp.CHANNEL_ONGOING_CALL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setSilent(true)
            .setUsesChronometer(true)
            .setStyle(
                NotificationCompat.CallStyle.forOngoingCall(
                    caller,
                    hangupPending
                )
            )
            .setContentIntent(contentPending)
            .build()

        nm.notify(NOTIFICATION_ID_ONGOING, notification)
    }

    private fun buildInCallPendingIntent(requestCode: Int): PendingIntent {
        val intent = Intent(this, InCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildActionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, IncomingCallActionReceiver::class.java).apply {
            this.action = action
        }
        return PendingIntent.getBroadcast(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val TAG = "TumorRecorder_InCall"
        private const val NOTIFICATION_ID_INCOMING = 1100
        private const val NOTIFICATION_ID_ONGOING = 1101
        private const val REQ_FULL_SCREEN = 2001
        private const val REQ_ANSWER_RECORD = 2002
        private const val REQ_ANSWER_PRIVATE = 2003
        private const val REQ_DECLINE = 2004
        private const val REQ_ONGOING_CONTENT = 2005
        private const val REQ_HANGUP = 2006
    }
}

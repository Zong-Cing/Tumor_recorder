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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tumorrecorder.app.R
import com.tumorrecorder.app.TumorRecorderApp
import com.tumorrecorder.app.ui.incall.InCallActivity

/**
 * Android Telecom InCallService allowing TumorRecorder to serve as the
 * device's Default Phone App (ROLE_DIALER), presenting our dedicated call screen
 * where the case manager chooses whether to record & analyze each call.
 */
class TumorInCallService : InCallService() {

    override fun onCreate() {
        super.onCreate()
        CallManager.attachService(this)
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        CallManager.onCallAdded(this, call)

        val number = call.details?.handle?.schemeSpecificPart.orEmpty().ifBlank { "未知來電" }
        val isRinging = CallManager.callState.value?.state == Call.STATE_RINGING

        if (isRinging) {
            showIncomingCallNotification(number)
        }
        launchInCallScreen()
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        cancelIncomingNotification()
        CallManager.onCallRemoved(this, call)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        CallManager.onAudioStateChanged(audioState)
    }

    override fun onDestroy() {
        cancelIncomingNotification()
        CallManager.detachService(this)
        super.onDestroy()
    }

    private fun launchInCallScreen() {
        val intent = Intent(this, InCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        runCatching { startActivity(intent) }
    }

    private fun showIncomingCallNotification(number: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val fullScreenIntent = Intent(this, InCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val fullScreenPending = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID_INCOMING,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, TumorRecorderApp.CHANNEL_INCOMING_CALL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("腫瘤個管來電：$number")
            .setContentText("點擊進入接聽畫面（可選擇錄音分析或一般接聽）")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreenPending, true)
            .setContentIntent(fullScreenPending)
            .build()

        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID_INCOMING, notification)
    }

    fun cancelIncomingNotification() {
        getSystemService(NotificationManager::class.java)
            ?.cancel(NOTIFICATION_ID_INCOMING)
    }

    companion object {
        private const val NOTIFICATION_ID_INCOMING = 1100
    }
}

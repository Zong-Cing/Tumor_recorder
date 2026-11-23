package com.tumorrecorder.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.tumorrecorder.app.data.local.AppDatabase
import com.tumorrecorder.app.data.local.SettingsRepository
import com.tumorrecorder.app.data.remote.EmailService
import com.tumorrecorder.app.data.remote.GeminiService
import com.tumorrecorder.app.data.repository.CallRecordRepository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TumorRecorderApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        createNotificationChannels()
        appScope.launch {
            container.promptTemplateRepository.ensureSeeded()
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return

            val recordingChannel = NotificationChannel(
                CHANNEL_RECORDING,
                "通話錄音進行中",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "顯示目前通話錄音狀態與快速操作按鈕"
                setShowBadge(false)
            }

            val statusChannel = NotificationChannel(
                CHANNEL_STATUS,
                "摘要與寄信通知",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "通話 AI 摘要完成、寄信成功或失敗通知"
            }

            // Remove legacy incoming call channel that used default notification beep
            runCatching { nm.deleteNotificationChannel("tumor_recorder_incoming_call") }

            val incomingCallChannel = NotificationChannel(
                CHANNEL_INCOMING_CALL,
                "來電通話通知",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "作為預設電話 App 時顯示全螢幕來電與橫幅接聽畫面（鈴聲與震動由系統電話自動播放）"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setSound(null, null)
                enableVibration(false)
            }

            val ongoingCallChannel = NotificationChannel(
                CHANNEL_ONGOING_CALL,
                "通話中通知",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "通話進行中時提供快速返回通話畫面與掛斷按鈕"
                setShowBadge(false)
            }

            nm.createNotificationChannel(recordingChannel)
            nm.createNotificationChannel(statusChannel)
            nm.createNotificationChannel(incomingCallChannel)
            nm.createNotificationChannel(ongoingCallChannel)
        }
    }

    companion object {
        const val CHANNEL_RECORDING = "tumor_recorder_recording"
        const val CHANNEL_STATUS = "tumor_recorder_status"
        const val CHANNEL_INCOMING_CALL = "tumor_recorder_incoming_call_v2"
        const val CHANNEL_ONGOING_CALL = "tumor_recorder_ongoing_call"
    }
}

class AppContainer(application: Application) {
    val database: AppDatabase = AppDatabase.getInstance(application)
    val settingsRepository: SettingsRepository = SettingsRepository(application)
    val deviceCallLogRepository: com.tumorrecorder.app.data.local.DeviceCallLogRepository =
        com.tumorrecorder.app.data.local.DeviceCallLogRepository(application)
    val geminiService: GeminiService = GeminiService()
    val emailService: EmailService = EmailService()
    val promptTemplateRepository: com.tumorrecorder.app.data.repository.PromptTemplateRepository =
        com.tumorrecorder.app.data.repository.PromptTemplateRepository(database.promptTemplateDao())
    val callRecordRepository: CallRecordRepository = CallRecordRepository(
        context = application,
        dao = database.callRecordDao(),
        settingsRepository = settingsRepository,
        emailService = emailService
    )
}

package com.tumorrecorder.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tumorrecorder.app.MainActivity
import com.tumorrecorder.app.R
import com.tumorrecorder.app.TumorRecorderApp
import com.tumorrecorder.app.data.local.CallRecordEntity
import com.tumorrecorder.app.data.remote.EmailFormatter
import com.tumorrecorder.app.domain.model.CallDirection
import com.tumorrecorder.app.domain.model.ProcessingStatus
import com.tumorrecorder.app.domain.model.RecordingAudioSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class RecordingState(
    val isRecording: Boolean = false,
    val elapsedSeconds: Long = 0L,
    val phoneNumber: String = "",
    val direction: CallDirection = CallDirection.MANUAL,
    val speakerEnabled: Boolean = false,
    val activeAudioSourceLabel: String = "",
    val currentRecordId: Long? = null,
    val errorMessage: String? = null
)

class CallRecordingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAtMillis: Long = 0L
    @Volatile
    private var activeRecordId: Long? = null
    private var insertRecordJob: Job? = null
    private var activePhoneNumber: String = ""
    private var activeDirection: CallDirection = CallDirection.MANUAL
    private var activeAudioSourceLabel: String = ""
    private var timerJob: Job? = null
    private var speakerToggledByService: Boolean = false
    @Volatile
    private var isActivelyRecording: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val number = intent.getStringExtra(EXTRA_PHONE_NUMBER).orEmpty()
                val direction = CallDirection.fromName(
                    intent.getStringExtra(EXTRA_DIRECTION) ?: CallDirection.MANUAL.name
                )
                val fromPhoneCall = intent.getBooleanExtra(EXTRA_FROM_PHONE_CALL, false)
                beginRecording(number, direction, fromPhoneCall)
            }

            ACTION_STOP -> {
                finishRecording()
            }

            ACTION_TOGGLE_SPEAKER -> {
                toggleSpeakerphone()
            }

            ACTION_UPDATE_NUMBER -> {
                val number = intent.getStringExtra(EXTRA_PHONE_NUMBER).orEmpty()
                if (number.isNotBlank() && _recordingState.value.isRecording) {
                    activePhoneNumber = number
                    _recordingState.value = _recordingState.value.copy(phoneNumber = number)
                    updateNotification()
                    val recordId = activeRecordId
                    if (recordId != null) {
                        serviceScope.launch(Dispatchers.IO) {
                            val app = application as TumorRecorderApp
                            val existing = app.container.callRecordRepository.getRecord(recordId)
                            if (existing != null && existing.phoneNumber.isBlank()) {
                                app.container.callRecordRepository.updateRecord(
                                    existing.copy(phoneNumber = number)
                                )
                            }
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun beginRecording(
        phoneNumber: String,
        direction: CallDirection,
        fromPhoneCall: Boolean
    ) {
        if (isActivelyRecording || _recordingState.value.isRecording) return

        val app = application as TumorRecorderApp
        val settings = app.container.settingsRepository.currentSettings()
        val recordingsDir = app.container.callRecordRepository.ensureRecordingsDir()

        startedAtMillis = System.currentTimeMillis()
        activePhoneNumber = phoneNumber
        activeDirection = direction
        activeRecordId = null
        val file = File(recordingsDir, "call_${startedAtMillis}.m4a")
        outputFile = file

        if (!startForegroundSafely(buildRecordingNotification(0L, false))) {
            outputFile = null
            val msg = "受 Android 背景錄音限制無法啟動錄音服務，請先點開來電畫面或確認無障礙服務已開啟。"
            _recordingState.value = RecordingState(isRecording = false, errorMessage = msg)
            showRecordingErrorNotification(msg)
            stopSelfSafely()
            return
        }

        if (fromPhoneCall && settings.autoEnableSpeakerOnCall) {
            setSpeakerphone(true)
        }

        var usedSource = startMediaRecorder(
            file = file,
            preferredSource = settings.audioSource,
            fromPhoneCall = fromPhoneCall
        )
        if (usedSource != null) {
            onRecorderStarted(app, file, phoneNumber, direction, usedSource)
        } else if (fromPhoneCall) {
            // Right after answering an incoming call, Samsung/Android Telecom may still be
            // releasing the ringtone audio stream for ~300-400ms. Retry once cleanly.
            Log.w(TAG, "First MediaRecorder start failed on phone call; retrying in 450ms...")
            isActivelyRecording = true
            serviceScope.launch {
                delay(450L)
                if (!isActivelyRecording || outputFile != file) return@launch
                val retried = startMediaRecorder(
                    file = file,
                    preferredSource = settings.audioSource,
                    fromPhoneCall = true
                )
                if (retried != null) {
                    onRecorderStarted(app, file, phoneNumber, direction, retried)
                } else {
                    isActivelyRecording = false
                    outputFile = null
                    val msg = "無法啟動通話錄音，請確認已開啟「無障礙錄音服務」與麥克風權限。"
                    _recordingState.value = RecordingState(isRecording = false, errorMessage = msg)
                    showRecordingErrorNotification(msg)
                    stopSelfSafely()
                }
            }
        } else {
            outputFile = null
            val msg = "無法啟動錄音，請確認麥克風權限是否已授予。"
            _recordingState.value = RecordingState(isRecording = false, errorMessage = msg)
            showRecordingErrorNotification(msg)
            stopSelfSafely()
        }
    }

    private fun onRecorderStarted(
        app: TumorRecorderApp,
        file: File,
        phoneNumber: String,
        direction: CallDirection,
        usedSource: RecordingAudioSource
    ) {
        isActivelyRecording = true
        val a11yTag = if (CallRecorderAccessibilityService.isServiceEnabled(this)) "無障礙已啟用" else "無障礙未啟用"
        activeAudioSourceLabel = "${usedSource.name} ($a11yTag)"
        val currentSpeaker = isSpeakerphoneCurrentlyOn()
        _recordingState.value = RecordingState(
            isRecording = true,
            elapsedSeconds = 0L,
            phoneNumber = phoneNumber,
            direction = direction,
            speakerEnabled = currentSpeaker,
            activeAudioSourceLabel = activeAudioSourceLabel,
            errorMessage = null
        )

        insertRecordJob = serviceScope.launch(Dispatchers.IO) {
            val id = app.container.callRecordRepository.insertRecord(
                CallRecordEntity(
                    phoneNumber = phoneNumber,
                    direction = direction.name,
                    startedAtMillis = startedAtMillis,
                    durationSeconds = 0L,
                    audioFilePath = file.absolutePath,
                    audioMimeType = "audio/mp4",
                    status = ProcessingStatus.RECORDING.name
                )
            )
            activeRecordId = id
            _recordingState.value = _recordingState.value.copy(currentRecordId = id)
            Log.i(TAG, "Inserted placeholder record id=$id path=${file.absolutePath}")
        }

        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (true) {
                delay(1000L)
                val elapsed = ((System.currentTimeMillis() - startedAtMillis) / 1000L).coerceAtLeast(0L)
                _recordingState.value = _recordingState.value.copy(elapsedSeconds = elapsed)
                updateNotification()
            }
        }
    }

    private fun startMediaRecorder(
        file: File,
        preferredSource: RecordingAudioSource,
        fromPhoneCall: Boolean
    ): RecordingAudioSource? {
        val orderedCandidates = if (fromPhoneCall) {
            listOf(
                preferredSource,
                RecordingAudioSource.VOICE_RECOGNITION,
                RecordingAudioSource.VOICE_COMMUNICATION,
                RecordingAudioSource.MIC
            ).distinct()
        } else {
            listOf(
                preferredSource,
                RecordingAudioSource.MIC,
                RecordingAudioSource.VOICE_RECOGNITION
            ).distinct()
        }

        for (candidate in orderedCandidates) {
            val androidSource = candidate.toAndroidAudioSource()
            val recorder = createMediaRecorder()
            try {
                recorder.setAudioSource(androidSource)
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioSamplingRate(44_100)
                recorder.setAudioEncodingBitRate(96_000)
                recorder.setAudioChannels(1)
                recorder.setOutputFile(file.absolutePath)
                recorder.prepare()
                recorder.start()
                mediaRecorder = recorder
                Log.i(TAG, "Started native MediaRecorder using source=${candidate.name}")
                return candidate
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start MediaRecorder with source=${candidate.name}", e)
                runCatching { recorder.reset() }
                runCatching { recorder.release() }
            }
        }
        return null
    }

    private fun RecordingAudioSource.toAndroidAudioSource(): Int = when (this) {
        RecordingAudioSource.VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        RecordingAudioSource.CAMCORDER -> MediaRecorder.AudioSource.CAMCORDER
        RecordingAudioSource.VOICE_COMMUNICATION -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
        RecordingAudioSource.MIC -> MediaRecorder.AudioSource.MIC
        RecordingAudioSource.UNPROCESSED -> MediaRecorder.AudioSource.UNPROCESSED
    }

    @Suppress("DEPRECATION")
    private fun createMediaRecorder(): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            MediaRecorder()
        }
    }

    private fun finishRecording() {
        if (!isActivelyRecording) {
            Log.d(TAG, "Ignoring duplicate ACTION_STOP while not recording")
            return
        }
        isActivelyRecording = false

        timerJob?.cancel()
        timerJob = null

        val elapsed = ((System.currentTimeMillis() - startedAtMillis) / 1000L).coerceAtLeast(1L)
        val file = outputFile
        val pendingInsertJob = insertRecordJob
        val initialRecordId = activeRecordId
        val phoneNumber = activePhoneNumber
        val direction = activeDirection
        val startMillis = startedAtMillis
        outputFile = null
        insertRecordJob = null
        activeRecordId = null

        val activeMediaRecorder = mediaRecorder
        mediaRecorder = null

        if (speakerToggledByService) {
            setSpeakerphone(false)
            speakerToggledByService = false
        }

        _recordingState.value = RecordingState(isRecording = false)

        val app = application as TumorRecorderApp
        val settings = app.container.settingsRepository.currentSettings()
        val (quickPatient, quickId, quickCm) = CallManager.snapshotQuickNotes()

        _isFinalizingRecording.value = true
        serviceScope.launch(Dispatchers.IO) {
            try {
                runCatching { pendingInsertJob?.join() }
                val recordId = initialRecordId ?: activeRecordId

                var stopSucceeded = false
                var failureReason: String? = null
                if (activeMediaRecorder != null) {
                    stopSucceeded = runCatching {
                        activeMediaRecorder.stop()
                        true
                    }.getOrElse { e ->
                        failureReason = e.message ?: e::class.java.simpleName
                        Log.w(TAG, "MediaRecorder stop error", e)
                        false
                    }
                    runCatching { activeMediaRecorder.reset() }
                    runCatching { activeMediaRecorder.release() }
                }

                val repo = app.container.callRecordRepository
                val fileBytes = if (file != null && file.exists()) file.length() else 0L
                Log.i(
                    TAG,
                    "finishRecording: stopSucceeded=$stopSucceeded recordId=$recordId bytes=$fileBytes elapsed=${elapsed}s reason=$failureReason"
                )

                if (stopSucceeded && file != null && file.exists() && fileBytes > 1024L) {
                    val finalId = if (recordId != null) {
                        val existing = repo.getRecord(recordId)
                        if (existing != null) {
                            repo.updateRecord(
                                existing.copy(
                                    phoneNumber = existing.phoneNumber.ifBlank { phoneNumber },
                                    durationSeconds = elapsed,
                                    audioFilePath = file.absolutePath,
                                    audioMimeType = "audio/mp4",
                                    patientName = existing.patientName.ifBlank { quickPatient },
                                    patientIdentifier = existing.patientIdentifier.ifBlank { quickId },
                                    caseManagerName = existing.caseManagerName.ifBlank { quickCm },
                                    status = ProcessingStatus.PENDING.name,
                                    errorMessage = null
                                )
                            )
                            recordId
                        } else {
                            repo.insertRecord(
                                CallRecordEntity(
                                    phoneNumber = phoneNumber,
                                    direction = direction.name,
                                    startedAtMillis = startMillis,
                                    durationSeconds = elapsed,
                                    audioFilePath = file.absolutePath,
                                    audioMimeType = "audio/mp4",
                                    patientName = quickPatient,
                                    patientIdentifier = quickId,
                                    caseManagerName = quickCm,
                                    status = ProcessingStatus.PENDING.name
                                )
                            )
                        }
                    } else {
                        repo.insertRecord(
                            CallRecordEntity(
                                phoneNumber = phoneNumber,
                                direction = direction.name,
                                startedAtMillis = startMillis,
                                durationSeconds = elapsed,
                                audioFilePath = file.absolutePath,
                                audioMimeType = "audio/mp4",
                                patientName = quickPatient,
                                patientIdentifier = quickId,
                                caseManagerName = quickCm,
                                status = ProcessingStatus.PENDING.name
                            )
                        )
                    }

                    Log.i(TAG, "Saved call record id=$finalId bytes=$fileBytes autoAnalyze=${settings.autoAnalyzeAfterCall}")

                    if (settings.autoAnalyzeAfterCall) {
                        repo.enqueueProcessing(finalId)
                    } else {
                        _pendingConfirmRecordId.value = finalId
                        showPostCallConfirmNotification(finalId, phoneNumber, elapsed)
                    }
                } else {
                    if (recordId != null) {
                        repo.getRecord(recordId)?.let { repo.deleteRecord(it) }
                    }
                    file?.let { runCatching { it.delete() } }
                    val msg = "錄音存檔未完成（長度 ${elapsed}s / ${fileBytes} bytes${if (!failureReason.isNullOrBlank()) "：$failureReason" else ""}）"
                    Log.w(TAG, msg)
                    _recordingState.value = RecordingState(isRecording = false, errorMessage = msg)
                    showRecordingErrorNotification(msg)
                }
            } finally {
                _isFinalizingRecording.value = false
                stopSelfSafely()
            }
        }
    }

    private fun showPostCallConfirmNotification(recordId: Long, phoneNumber: String, durationSeconds: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_CONFIRM_RECORD_ID, recordId)
        }
        val pending = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID_CONFIRM,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val callerLabel = phoneNumber.ifBlank { "本次通話" }
        val notification = NotificationCompat.Builder(this, TumorRecorderApp.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("通話已結束（$callerLabel・${EmailFormatter.formatDuration(durationSeconds)}）")
            .setContentText("可錄製口述補充摘要，並選擇 Prompt 開始 AI 分析與寄信。")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID_CONFIRM, notification)
    }

    private fun showRecordingErrorNotification(message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val openAppIntent = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID_ERROR,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, TumorRecorderApp.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("通話錄音未啟動或未存檔")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent)
            .build()

        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID_ERROR, notification)
    }

    private fun toggleSpeakerphone() {
        val next = !isSpeakerphoneCurrentlyOn()
        setSpeakerphone(next)
        _recordingState.value = _recordingState.value.copy(speakerEnabled = next)
        updateNotification()
    }

    @Suppress("DEPRECATION")
    private fun setSpeakerphone(enabled: Boolean) {
        if (CallManager.setSpeakerEnabled(enabled)) {
            if (enabled) speakerToggledByService = true
            return
        }
        val audioManager = getSystemService(AudioManager::class.java) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (enabled) {
                    val speakerDevice = audioManager.availableCommunicationDevices
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (speakerDevice != null) {
                        audioManager.setCommunicationDevice(speakerDevice)
                        speakerToggledByService = true
                    } else {
                        audioManager.isSpeakerphoneOn = true
                        speakerToggledByService = true
                    }
                } else {
                    audioManager.clearCommunicationDevice()
                    audioManager.isSpeakerphoneOn = false
                }
            } else {
                audioManager.isSpeakerphoneOn = enabled
                if (enabled) speakerToggledByService = true
            }
        }.onFailure {
            Log.w(TAG, "Unable to toggle speakerphone", it)
        }
    }

    @Suppress("DEPRECATION")
    private fun isSpeakerphoneCurrentlyOn(): Boolean {
        CallManager.callState.value?.let { return it.isSpeakerOn }
        val audioManager = getSystemService(AudioManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                audioManager.isSpeakerphoneOn
        } else {
            audioManager.isSpeakerphoneOn
        }
    }

    private fun startForegroundSafely(notification: Notification): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        }.getOrElse { e ->
            Log.e(TAG, "startForegroundSafely failed", e)
            false
        }
    }

    private fun updateNotification() {
        val state = _recordingState.value
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, buildRecordingNotification(state.elapsedSeconds, state.speakerEnabled))
    }

    private fun buildRecordingNotification(elapsedSeconds: Long, speakerOn: Boolean): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, CallRecordingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val speakerPendingIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, CallRecordingService::class.java).apply { action = ACTION_TOGGLE_SPEAKER },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val numberDisplay = activePhoneNumber.ifBlank { activeDirection.label }
        val speakerLabel = if (speakerOn) "關閉擴音" else "開啟擴音"

        return NotificationCompat.Builder(this, TumorRecorderApp.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("腫瘤個管通話錄音中 (${EmailFormatter.formatDuration(elapsedSeconds)})")
            .setContentText("通話對象：$numberDisplay・結束後可口述補充並選擇分析")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent)
            .addAction(0, speakerLabel, speakerPendingIntent)
            .addAction(0, "停止錄音", stopPendingIntent)
            .build()
    }

    private fun stopSelfSafely() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        timerJob?.cancel()
        mediaRecorder?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        mediaRecorder = null
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TumorRecorder_Service"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_ID_CONFIRM = 1002
        private const val NOTIFICATION_ID_ERROR = 1003

        const val ACTION_START = "com.tumorrecorder.app.action.START_RECORDING"
        const val ACTION_STOP = "com.tumorrecorder.app.action.STOP_RECORDING"
        const val ACTION_TOGGLE_SPEAKER = "com.tumorrecorder.app.action.TOGGLE_SPEAKER"
        const val ACTION_UPDATE_NUMBER = "com.tumorrecorder.app.action.UPDATE_NUMBER"

        const val EXTRA_PHONE_NUMBER = "extra_phone_number"
        const val EXTRA_DIRECTION = "extra_direction"
        const val EXTRA_FROM_PHONE_CALL = "extra_from_phone_call"

        private val _recordingState = MutableStateFlow(RecordingState())
        val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

        private val _isFinalizingRecording = MutableStateFlow(false)
        val isFinalizingRecording: StateFlow<Boolean> = _isFinalizingRecording.asStateFlow()

        private val _pendingConfirmRecordId = MutableStateFlow<Long?>(null)
        val pendingConfirmRecordId: StateFlow<Long?> = _pendingConfirmRecordId.asStateFlow()

        fun requestConfirmForRecord(recordId: Long) {
            _pendingConfirmRecordId.value = recordId
        }

        fun clearPendingConfirm() {
            _pendingConfirmRecordId.value = null
        }

        fun startRecording(
            context: Context,
            phoneNumber: String = "",
            direction: CallDirection = CallDirection.MANUAL,
            fromPhoneCall: Boolean = false
        ) {
            val intent = Intent(context, CallRecordingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PHONE_NUMBER, phoneNumber)
                putExtra(EXTRA_DIRECTION, direction.name)
                putExtra(EXTRA_FROM_PHONE_CALL, fromPhoneCall)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopRecording(context: Context) {
            val intent = Intent(context, CallRecordingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun toggleSpeaker(context: Context) {
            val intent = Intent(context, CallRecordingService::class.java).apply {
                action = ACTION_TOGGLE_SPEAKER
            }
            context.startService(intent)
        }

        fun updateActivePhoneNumber(context: Context, phoneNumber: String) {
            val intent = Intent(context, CallRecordingService::class.java).apply {
                action = ACTION_UPDATE_NUMBER
                putExtra(EXTRA_PHONE_NUMBER, phoneNumber)
            }
            context.startService(intent)
        }
    }
}

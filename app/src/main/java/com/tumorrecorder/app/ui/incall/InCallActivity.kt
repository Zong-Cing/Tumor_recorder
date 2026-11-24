package com.tumorrecorder.app.ui.incall

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.telecom.Call
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tumorrecorder.app.MainActivity
import com.tumorrecorder.app.TumorRecorderApp
import com.tumorrecorder.app.data.remote.EmailFormatter
import com.tumorrecorder.app.service.ActiveCallUiState
import com.tumorrecorder.app.service.CallManager
import com.tumorrecorder.app.service.CallRecordingService
import com.tumorrecorder.app.service.RecordingState
import com.tumorrecorder.app.ui.theme.MedicalTeal
import com.tumorrecorder.app.ui.theme.MedicalTealDark
import com.tumorrecorder.app.ui.theme.SuccessGreen
import com.tumorrecorder.app.ui.theme.TumorRecorderTheme
import com.tumorrecorder.app.ui.theme.UrgentRed
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class InCallActivity : ComponentActivity() {

    private var proximityWakeLock: PowerManager.WakeLock? = null
    private var lastDesiredProximity: Boolean = false
    private var isResumedState: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableShowWhenLocked()
        initProximityWakeLock()
        handleCallActionIntent(intent)

        val container = (application as TumorRecorderApp).container

        setContent {
            TumorRecorderTheme {
                val callState by CallManager.callState.collectAsStateWithLifecycle()
                val recordingState by CallRecordingService.recordingState.collectAsStateWithLifecycle()
                val isFinalizingRecording by CallRecordingService.isFinalizingRecording.collectAsStateWithLifecycle()
                val pendingConfirmId by CallRecordingService.pendingConfirmRecordId.collectAsStateWithLifecycle()
                val scope = rememberCoroutineScope()

                val shouldUseProximity = callState != null &&
                    !callState!!.isSpeakerOn &&
                    callState!!.state in setOf(
                        Call.STATE_DIALING,
                        Call.STATE_CONNECTING,
                        Call.STATE_ACTIVE,
                        Call.STATE_HOLDING
                    )
                LaunchedEffect(shouldUseProximity) {
                    lastDesiredProximity = shouldUseProximity
                    updateProximityWakeLock(shouldUseProximity && isResumedState)
                }

                var hadRecordingInThisSession by remember { mutableStateOf(false) }
                if (recordingState.isRecording || isFinalizingRecording || callState?.shouldRecordThisCall == true) {
                    hadRecordingInThisSession = true
                }

                // Auto-close when call ends and there's no pending analysis confirmation to ask
                LaunchedEffect(callState, pendingConfirmId, hadRecordingInThisSession, isFinalizingRecording) {
                    val callEnded = callState == null || callState?.state == Call.STATE_DISCONNECTED
                    if (callEnded && pendingConfirmId == null && !isFinalizingRecording) {
                        // Give finishRecording() a short window to flush MediaRecorder and emit pendingConfirmId
                        delay(if (hadRecordingInThisSession) 2500L else 500L)
                        if (!isFinishing &&
                            CallManager.callState.value == null &&
                            !CallRecordingService.isFinalizingRecording.value &&
                            CallRecordingService.pendingConfirmRecordId.value == null
                        ) {
                            finish()
                        }
                    }
                }

                val confirmId = pendingConfirmId
                if (confirmId != null && (callState == null || callState?.state == Call.STATE_DISCONNECTED)) {
                    PostCallAnalysisPromptScreen(
                        recordId = confirmId,
                        onAnalyzeNow = { selectedPromptId, includeTranscript, forceStandardTier ->
                            container.callRecordRepository.enqueueProcessing(
                                recordId = confirmId,
                                promptTemplateId = selectedPromptId,
                                includeTranscript = includeTranscript,
                                forceStandardTier = forceStandardTier
                            )
                            CallRecordingService.clearPendingConfirm()
                            openHomeAndFinish(openRecordId = confirmId)
                        },
                        onKeepForLater = {
                            CallRecordingService.clearPendingConfirm()
                            openHomeAndFinish(openRecordId = confirmId)
                        },
                        onDiscardRecording = {
                            scope.launch {
                                container.callRecordRepository.getRecord(confirmId)?.let {
                                    container.callRecordRepository.deleteRecord(it)
                                }
                                CallRecordingService.clearPendingConfirm()
                                if (!isFinishing) finish()
                            }
                        }
                    )
                } else {
                    val active = callState
                    if (active != null) {
                        InCallScreenContent(
                            callState = active,
                            recordingState = recordingState
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF0F172A)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isFinalizingRecording || hadRecordingInThisSession) {
                                    "正在儲存錄音..."
                                } else {
                                    "正在結束通話..."
                                },
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isResumedState = true
        updateProximityWakeLock(lastDesiredProximity)
    }

    override fun onPause() {
        isResumedState = false
        updateProximityWakeLock(false, waitForNoProximity = false)
        super.onPause()
    }

    override fun onDestroy() {
        updateProximityWakeLock(false, waitForNoProximity = false)
        proximityWakeLock = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCallActionIntent(intent)
    }

    private fun initProximityWakeLock() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximityWakeLock = pm.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                "TumorRecorder:InCallProximity"
            ).apply {
                setReferenceCounted(false)
            }
        }
    }

    private fun updateProximityWakeLock(enable: Boolean, waitForNoProximity: Boolean = true) {
        val wl = proximityWakeLock ?: return
        runCatching {
            if (enable) {
                if (!wl.isHeld) {
                    wl.acquire(4 * 60 * 60 * 1000L) // safety timeout 4h
                }
            } else {
                if (wl.isHeld) {
                    if (waitForNoProximity) {
                        wl.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
                    } else {
                        wl.release()
                    }
                }
            }
        }
    }

    private fun handleCallActionIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_ANSWER_RECORD -> {
                intent.action = null
                CallManager.answer(recordThisCall = true)
            }
            ACTION_ANSWER_PRIVATE -> {
                intent.action = null
                CallManager.answer(recordThisCall = false)
            }
        }
    }

    private fun openHomeAndFinish(openRecordId: Long? = null) {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (openRecordId != null && openRecordId > 0L) {
                    putExtra(MainActivity.EXTRA_OPEN_RECORD_ID, openRecordId)
                }
            }
        )
        if (!isFinishing) finish()
    }

    @Suppress("DEPRECATION")
    private fun enableShowWhenLocked() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    companion object {
        const val ACTION_ANSWER_RECORD = "com.tumorrecorder.app.action.UI_ANSWER_RECORD"
        const val ACTION_ANSWER_PRIVATE = "com.tumorrecorder.app.action.UI_ANSWER_PRIVATE"
    }
}

@Composable
private fun InCallScreenContent(
    callState: ActiveCallUiState,
    recordingState: RecordingState
) {
    val context = LocalContext.current
    var showDtmfPad by remember { mutableStateOf(false) }
    var showQuickNotes by remember { mutableStateOf(false) }
    var dtmfInput by remember { mutableStateOf("") }
    var callElapsedSec by remember { mutableLongStateOf(0L) }

    LaunchedEffect(callState.connectedAtMillis, callState.state) {
        val start = callState.connectedAtMillis
        if (start != null && callState.state == Call.STATE_ACTIVE) {
            while (true) {
                callElapsedSec = ((System.currentTimeMillis() - start) / 1000L).coerceAtLeast(0L)
                delay(1000L)
            }
        } else {
            callElapsedSec = 0L
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0F2F2E), Color(0xFF0F172A))
                )
            )
            .statusBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Caller Header
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "腫瘤個管通話助理",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF99F6E4)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = callState.phoneNumber.ifBlank { "未知號碼" },
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = if (callState.state == Call.STATE_ACTIVE) {
                        "${callState.stateLabel} ・ ${EmailFormatter.formatDuration(callElapsedSec)}"
                    } else {
                        callState.stateLabel
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFFCBD5E1)
                )

                // Live Recording Badge
                if (callState.state != Call.STATE_RINGING) {
                    Spacer(Modifier.height(6.dp))
                    val recBg = if (recordingState.isRecording) UrgentRed.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.1f)
                    val recColor = if (recordingState.isRecording) Color(0xFFFCA5A5) else Color(0xFFCBD5E1)
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(recBg)
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FiberManualRecord,
                            contentDescription = null,
                            tint = if (recordingState.isRecording) UrgentRed else Color(0xFF94A3B8),
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = if (recordingState.isRecording) {
                                "本通錄音中 (${EmailFormatter.formatDuration(recordingState.elapsedSeconds)})・結束後將詢問是否分析"
                            } else if (callState.shouldRecordThisCall) {
                                "等待接通後開始錄音..."
                            } else {
                                "未錄音（私人通話模式）"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = recColor
                        )
                    }

                    if (recordingState.isRecording) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White.copy(alpha = 0.08f))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = "口頭複述提示（免擴音單端清晰模式）",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF99F6E4)
                            )
                            Text(
                                text = "建議自然複述：身分(本人/家屬) · 負責/代班接聽 · 姓名/病歷號 · 症狀數值與衛教待辦（掛斷後亦可補錄口述摘要）",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFE2E8F0)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            if (callState.state == Call.STATE_RINGING) {
                // Incoming Call Choice: Record & Analyze vs Private Answer vs Reject
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Button(
                        onClick = { CallManager.answer(recordThisCall = true) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(58.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.Call, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text("接聽並錄音（結束後詢問分析）", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = { CallManager.answer(recordThisCall = false) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        border = BorderStroke(1.dp, Color(0xFF94A3B8)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("僅接聽（不錄音／私人來電）", fontSize = 15.sp)
                    }

                    Spacer(Modifier.height(8.dp))

                    Button(
                        onClick = { CallManager.reject() },
                        modifier = Modifier.size(72.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = UrgentRed),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(Icons.Default.CallEnd, contentDescription = "拒接", modifier = Modifier.size(32.dp))
                    }
                    Text("拒接", color = Color(0xFFFCA5A5), style = MaterialTheme.typography.bodySmall)
                }
            } else {
                // Active / Dialing Controls
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (showQuickNotes) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.08f)),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "通話中個管速記（選填，將協助 AI 核對）",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color(0xFF99F6E4)
                                )
                                QuickNoteField(
                                    value = callState.quickPatientName,
                                    label = "病人姓名",
                                    onChange = {
                                        CallManager.updateQuickNotes(
                                            it,
                                            callState.quickPatientIdentifier,
                                            callState.quickCaseManager
                                        )
                                    }
                                )
                                QuickNoteField(
                                    value = callState.quickPatientIdentifier,
                                    label = "病歷號／身分證字號",
                                    onChange = {
                                        CallManager.updateQuickNotes(
                                            callState.quickPatientName,
                                            it,
                                            callState.quickCaseManager
                                        )
                                    }
                                )
                                QuickNoteField(
                                    value = callState.quickCaseManager,
                                    label = "所屬個管師",
                                    onChange = {
                                        CallManager.updateQuickNotes(
                                            callState.quickPatientName,
                                            callState.quickPatientIdentifier,
                                            it
                                        )
                                    }
                                )
                            }
                        }
                    }

                    if (showDtmfPad) {
                        DtmfKeypad(
                            entered = dtmfInput,
                            onDigit = { digit ->
                                dtmfInput += digit
                                CallManager.playDtmf(digit)
                            }
                        )
                    }

                    // 4-button control grid
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        CallControlTile(
                            icon = if (callState.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            label = if (callState.isMuted) "已靜音" else "靜音",
                            active = callState.isMuted,
                            onClick = { CallManager.toggleMute() }
                        )
                        CallControlTile(
                            icon = Icons.AutoMirrored.Filled.VolumeUp,
                            label = if (callState.isSpeakerOn) "擴音中" else "擴音",
                            active = callState.isSpeakerOn,
                            onClick = { CallManager.toggleSpeaker() }
                        )
                        CallControlTile(
                            icon = Icons.Default.Dialpad,
                            label = "分機鍵盤",
                            active = showDtmfPad,
                            onClick = { showDtmfPad = !showDtmfPad }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        CallControlTile(
                            icon = if (recordingState.isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                            label = if (recordingState.isRecording) "停止錄音" else "開始錄音",
                            active = recordingState.isRecording,
                            activeColor = UrgentRed,
                            onClick = { CallManager.toggleRecordingForActiveCall(context) }
                        )
                        CallControlTile(
                            icon = Icons.Default.EditNote,
                            label = "個管速記",
                            active = showQuickNotes,
                            onClick = { showQuickNotes = !showQuickNotes }
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    Button(
                        onClick = { CallManager.disconnect() },
                        modifier = Modifier.size(76.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = UrgentRed),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(Icons.Default.CallEnd, contentDescription = "掛斷", modifier = Modifier.size(34.dp))
                    }
                    Text("掛斷", color = Color(0xFFFCA5A5), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun QuickNoteField(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, color = Color(0xFFCBD5E1)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = Color(0xFF5EEAD4),
            unfocusedBorderColor = Color(0xFF64748B)
        )
    )
}

@Composable
private fun CallControlTile(
    icon: ImageVector,
    label: String,
    active: Boolean,
    activeColor: Color = MedicalTeal,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = if (active) activeColor else Color.White.copy(alpha = 0.14f),
            modifier = Modifier.size(60.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(26.dp))
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFE2E8F0)
        )
    }
}

@Composable
private fun DtmfKeypad(entered: String, onDigit: (Char) -> Unit) {
    val rows = listOf(
        listOf('1', '2', '3'),
        listOf('4', '5', '6'),
        listOf('7', '8', '9'),
        listOf('*', '0', '#')
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (entered.isNotEmpty()) {
            Text(entered, color = Color(0xFF99F6E4), fontSize = 18.sp, fontWeight = FontWeight.Medium)
        }
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                row.forEach { digit ->
                    Surface(
                        onClick = { onDigit(digit) },
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.14f),
                        modifier = Modifier.size(52.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(digit.toString(), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PostCallAnalysisPromptScreen(
    recordId: Long,
    onAnalyzeNow: (Long, Boolean, Boolean) -> Unit,
    onKeepForLater: () -> Unit,
    onDiscardRecording: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as TumorRecorderApp
    val scope = rememberCoroutineScope()
    val settings by app.container.settingsRepository.settings.collectAsStateWithLifecycle()
    val record by app.container.callRecordRepository
        .observeRecord(recordId)
        .collectAsStateWithLifecycle(initialValue = null)
    val templates by app.container.promptTemplateRepository
        .observeAll()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var selectedPromptId by remember { mutableStateOf<Long?>(null) }
    var includeTranscript by remember(recordId, settings.generateTranscriptByDefault) {
        mutableStateOf(settings.generateTranscriptByDefault)
    }
    var forceStandardTier by remember(recordId) { mutableStateOf(false) }
    var isRecordingMemo by remember { mutableStateOf(false) }

    LaunchedEffect(templates) {
        if (selectedPromptId == null && templates.isNotEmpty()) {
            selectedPromptId = (templates.firstOrNull { it.isDefault } ?: templates.first()).id
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "通話已結束，要進行 AI 分析嗎？",
                    style = MaterialTheme.typography.titleLarge,
                    color = MedicalTealDark
                )
                val info = record
                if (info != null) {
                    Text(
                        text = "通話號碼：${info.phoneNumber.ifBlank { "未知號碼" }} ・ 錄音長度：${EmailFormatter.formatDuration(info.durationSeconds)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                com.tumorrecorder.app.ui.components.SupplementVoiceMemoCard(
                    recordId = recordId,
                    recordingsDir = app.container.callRecordRepository.ensureRecordingsDir(),
                    supplementAudioPath = info?.supplementAudioFilePath.orEmpty(),
                    supplementDurationSeconds = info?.supplementDurationSeconds ?: 0L,
                    compact = true,
                    onSaveSupplement = { file, duration ->
                        scope.launch {
                            app.container.callRecordRepository.saveSupplementAudio(recordId, file, duration)
                        }
                    },
                    onClearSupplement = {
                        scope.launch {
                            app.container.callRecordRepository.clearSupplementAudio(recordId)
                        }
                    },
                    onRecordingStateChange = { recording ->
                        isRecordingMemo = recording
                    }
                )

                Text(
                    text = "請選擇要使用的分析 Prompt：",
                    style = MaterialTheme.typography.titleSmall,
                    color = MedicalTeal
                )

                com.tumorrecorder.app.ui.components.PromptTemplatePickerList(
                    templates = templates,
                    selectedTemplateId = selectedPromptId,
                    onSelect = { selectedPromptId = it }
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { includeTranscript = !includeTranscript }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = includeTranscript,
                        onCheckedChange = { includeTranscript = it }
                    )
                    Column {
                        Text(
                            text = "同時產生完整逐字稿（選填）",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "預設關閉較省費，之後仍可於紀錄詳情頁隨時補產",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (settings.useFlexTier) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { forceStandardTier = !forceStandardTier }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = forceStandardTier,
                            onCheckedChange = { forceStandardTier = it }
                        )
                        Column {
                            Text(
                                text = "急件：直接用標準層立即分析",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "不勾選則預設走 Flex 半價層（滿載時自動重試，最長 30 分鐘後自動切標準層）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                Button(
                    onClick = { onAnalyzeNow(selectedPromptId ?: -1L, includeTranscript, forceStandardTier) },
                    enabled = !isRecordingMemo,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MedicalTeal)
                ) {
                    Text(
                        if (isRecordingMemo) {
                            "請先停止口述補充錄音再開始分析"
                        } else if (info?.hasSupplementAudio == true) {
                            if (settings.useFlexTier && !forceStandardTier) "合併口述補充並以 Flex 半價分析" else "合併口述補充並開始 AI 分析"
                        } else {
                            if (settings.useFlexTier && !forceStandardTier) "以 Flex 半價開始分析並寄信" else "使用此 Prompt 開始分析並寄信"
                        }
                    )
                }

                FilledTonalButton(
                    onClick = onKeepForLater,
                    enabled = !isRecordingMemo,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("暫不分析（保留錄音，稍後決定）")
                }

                TextButton(
                    onClick = onDiscardRecording,
                    enabled = !isRecordingMemo,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("不需要，直接刪除這筆錄音", color = UrgentRed)
                }
            }
        }
    }
}

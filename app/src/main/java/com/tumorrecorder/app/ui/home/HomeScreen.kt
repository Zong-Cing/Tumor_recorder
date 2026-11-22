package com.tumorrecorder.app.ui.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tumorrecorder.app.data.local.CallRecordEntity
import com.tumorrecorder.app.data.remote.EmailFormatter
import com.tumorrecorder.app.domain.model.AppSettings
import com.tumorrecorder.app.domain.model.CallDirection
import com.tumorrecorder.app.domain.model.ProcessingStatus
import com.tumorrecorder.app.service.CallManager
import com.tumorrecorder.app.service.CallRecordingService
import com.tumorrecorder.app.service.RecordingState
import com.tumorrecorder.app.ui.components.StatusBadge
import com.tumorrecorder.app.ui.components.UrgencyBadge
import com.tumorrecorder.app.ui.theme.MedicalTeal
import com.tumorrecorder.app.ui.theme.MedicalTealLight
import com.tumorrecorder.app.ui.theme.SuccessGreen
import com.tumorrecorder.app.ui.theme.UrgentRed
import com.tumorrecorder.app.ui.theme.UrgentRedContainer
import com.tumorrecorder.app.ui.theme.WarningAmber
import com.tumorrecorder.app.ui.theme.WarningAmberContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    hasRequiredPermissions: Boolean,
    isAccessibilityEnabled: Boolean,
    isDefaultDialer: Boolean,
    onRequestPermissions: () -> Unit,
    onRequestDefaultDialer: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenAppDetailsSettings: () -> Unit,
    onOpenRecord: (Long) -> Unit,
    onOpenPrompts: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val records by viewModel.records.collectAsStateWithLifecycle()
    val recordingState by viewModel.recordingState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val dialNumber by viewModel.dialNumber.collectAsStateWithLifecycle()
    val snackbarMessage by viewModel.snackbarMessage.collectAsStateWithLifecycle()
    val pendingConfirmRecord by viewModel.pendingConfirmRecord.collectAsStateWithLifecycle()
    val promptTemplates by viewModel.promptTemplates.collectAsStateWithLifecycle()

    var manualAnalyzeRecordId by remember { mutableStateOf<Long?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(snackbarMessage) {
        val msg = snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearSnackbar()
    }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.importAudioFile(uri)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "腫瘤個管通話助理",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = if (isDefaultDialer) {
                                "${settings.defaultCaseManagerUnit}・預設電話模式"
                            } else {
                                settings.defaultCaseManagerUnit
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MedicalTeal,
                    titleContentColor = Color.White,
                    actionIconContentColor = Color.White
                ),
                actions = {
                    IconButton(onClick = onOpenPrompts) {
                        Icon(Icons.Default.EditNote, contentDescription = "編輯分析 Prompt")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "設定")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!isDefaultDialer) {
                item(key = "default_dialer_banner") {
                    DefaultDialerSetupCard(onRequestDefaultDialer = onRequestDefaultDialer)
                }
            }

            if (!hasRequiredPermissions) {
                item(key = "permission_banner") {
                    SetupWarningCard(
                        title = "尚未授予通話與錄音權限",
                        message = "請授予麥克風、撥號、電話狀態、通話紀錄與通知權限，以便直接透過本 App 收發電話與錄音。",
                        actionLabel = "立即授權",
                        onAction = onRequestPermissions
                    )
                }
            }

            if (!isAccessibilityEnabled) {
                item(key = "accessibility_banner") {
                    AccessibilitySetupCard(
                        onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                        onOpenAppDetailsSettings = onOpenAppDetailsSettings
                    )
                }
            }

            if (!settings.isAiConfigured || !settings.isEmailConfigured) {
                item(key = "config_banner") {
                    val missing = buildList {
                        if (!settings.isAiConfigured) {
                            add(
                                if (settings.aiProvider == com.tumorrecorder.app.domain.model.AiProvider.GROQ) {
                                    "Groq API Key"
                                } else {
                                    "Gemini API Key"
                                }
                            )
                        }
                        if (!settings.isEmailConfigured) add("Gmail SMTP 自動寄信")
                    }.joinToString("、")
                    SetupWarningCard(
                        title = "尚未完成雲端服務設定",
                        message = "尚未設定：$missing。完成設定後，確認分析即可產出腫瘤個管摘要、完整逐字稿並寄至指定信箱。",
                        actionLabel = "前往設定",
                        onAction = onOpenSettings
                    )
                }
            }

            item(key = "recording_control") {
                RecordingControlCard(
                    recordingState = recordingState,
                    settings = settings,
                    isAccessibilityEnabled = isAccessibilityEnabled,
                    isDefaultDialer = isDefaultDialer,
                    dialNumber = dialNumber,
                    onDialNumberChange = viewModel::onDialNumberChanged,
                    onAppendDigit = viewModel::appendDialDigit,
                    onBackspaceDigit = viewModel::backspaceDialDigit,
                    onStartManualRecord = {
                        if (!hasAudioPermission(context)) {
                            onRequestPermissions()
                        } else {
                            CallRecordingService.startRecording(
                                context = context,
                                phoneNumber = dialNumber.trim(),
                                direction = CallDirection.MANUAL,
                                fromPhoneCall = false
                            )
                        }
                    },
                    onStopRecord = {
                        CallRecordingService.stopRecording(context)
                    },
                    onToggleSpeaker = {
                        CallRecordingService.toggleSpeaker(context)
                    },
                    onDialWithRecord = {
                        CallManager.prepareAppOutgoingCall(recordThisCall = true)
                        placePhoneCall(context, dialNumber.trim())
                    },
                    onDialWithoutRecord = {
                        CallManager.prepareAppOutgoingCall(recordThisCall = false)
                        placePhoneCall(context, dialNumber.trim())
                    },
                    onImportAudio = {
                        audioPickerLauncher.launch(arrayOf("audio/*"))
                    }
                )
            }

            item(key = "search_bar") {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = viewModel::onSearchQueryChanged,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜尋病人姓名、病歷號／身分證、個管師或內容...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                                Icon(Icons.Default.Close, contentDescription = "清除")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
            }

            item(key = "list_header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "通話諮詢紀錄 (${records.size})",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = if (settings.autoAnalyzeAfterCall) "掛斷自動分析" else "掛斷先詢問分析",
                        style = MaterialTheme.typography.bodySmall,
                        color = MedicalTeal
                    )
                }
            }

            if (records.isEmpty()) {
                item(key = "empty_state") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = null,
                                tint = MedicalTeal.copy(alpha = 0.6f),
                                modifier = Modifier.size(40.dp)
                            )
                            Text(
                                text = "尚無通話錄音紀錄",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "透過本 App 接聽或撥出錄音通話，結束後會先詢問您要使用哪組 Prompt 進行 AI 摘要與寄信。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(records, key = { it.id }) { record ->
                    CallRecordItemCard(
                        record = record,
                        onClick = { onOpenRecord(record.id) },
                        onAnalyzeOrRetry = { manualAnalyzeRecordId = record.id }
                    )
                }
            }
        }
    }

    // Post-call confirmation dialog with Prompt template picker
    val confirmRecord = pendingConfirmRecord
    if (confirmRecord != null) {
        var selectedPromptId by remember(confirmRecord.id, promptTemplates) {
            mutableStateOf(
                (promptTemplates.firstOrNull { it.isDefault } ?: promptTemplates.firstOrNull())?.id
            )
        }
        AlertDialog(
            onDismissRequest = { viewModel.dismissConfirmKeepRecord() },
            title = { Text("通話已結束，選擇分析 Prompt") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "對象：${confirmRecord.phoneNumber.ifBlank { confirmRecord.callDirection.label }} ・ 長度：${EmailFormatter.formatDuration(confirmRecord.durationSeconds)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    com.tumorrecorder.app.ui.components.PromptTemplatePickerList(
                        templates = promptTemplates,
                        selectedTemplateId = selectedPromptId,
                        onSelect = { selectedPromptId = it }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(
                            onClick = {
                                viewModel.dismissConfirmKeepRecord()
                                onOpenRecord(confirmRecord.id)
                            },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                        ) {
                            Text("先補錄口述摘要 →")
                        }
                        TextButton(
                            onClick = {
                                viewModel.dismissConfirmKeepRecord()
                                onOpenPrompts()
                            },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                        ) {
                            Text("管理 Prompt →")
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.confirmAnalyzeNow(
                            recordId = confirmRecord.id,
                            promptTemplateId = selectedPromptId ?: -1L
                        )
                    }
                ) {
                    Text("開始分析並寄信")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { viewModel.discardRecord(confirmRecord) }) {
                        Text("刪除錄音", color = UrgentRed)
                    }
                    TextButton(onClick = { viewModel.dismissConfirmKeepRecord() }) {
                        Text("暫不分析")
                    }
                }
            }
        )
    }

    // Prompt picker dialog when tapping "開始分析" / "重試" on an existing list record
    val targetManualId = manualAnalyzeRecordId
    if (targetManualId != null) {
        var selectedPromptId by remember(targetManualId, promptTemplates) {
            mutableStateOf(
                (promptTemplates.firstOrNull { it.isDefault } ?: promptTemplates.firstOrNull())?.id
            )
        }
        AlertDialog(
            onDismissRequest = { manualAnalyzeRecordId = null },
            title = { Text("選擇要使用的分析 Prompt") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    com.tumorrecorder.app.ui.components.PromptTemplatePickerList(
                        templates = promptTemplates,
                        selectedTemplateId = selectedPromptId,
                        onSelect = { selectedPromptId = it }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.analyzeWithPrompt(targetManualId, selectedPromptId ?: -1L)
                        manualAnalyzeRecordId = null
                    }
                ) {
                    Text("開始 AI 分析")
                }
            },
            dismissButton = {
                TextButton(onClick = { manualAnalyzeRecordId = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun DefaultDialerSetupCard(
    onRequestDefaultDialer: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
        border = BorderStroke(1.dp, Color(0xFF3B82F6).copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.PhoneInTalk, contentDescription = null, tint = Color(0xFF1D4ED8))
                Text(
                    text = "設為預設電話 App（推薦）",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(0xFF1E3A8A)
                )
            }
            Text(
                text = "設為預設電話後，在本 App 收發的電話會顯示專屬接聽／通話畫面，可當下選擇「接聽並錄音」或「私人不錄音」，且結束後會先問您要不要分析。",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF1E40AF)
            )
            Button(
                onClick = onRequestDefaultDialer,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text("設為預設電話 App")
            }
        }
    }
}

@Composable
private fun SetupWarningCard(
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = WarningAmberContainer),
        border = BorderStroke(1.dp, WarningAmber.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = WarningAmber)
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(0xFF78350F)
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF78350F)
            )
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = WarningAmber),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun AccessibilitySetupCard(
    onOpenAccessibilitySettings: () -> Unit,
    onOpenAppDetailsSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MedicalTealLight.copy(alpha = 0.65f)),
        border = BorderStroke(1.dp, MedicalTeal.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "請啟用「無障礙通話錄音服務」",
                style = MaterialTheme.typography.titleSmall,
                color = MedicalTeal
            )
            Text(
                text = "啟用後本 App 可在通話中以 VOICE_RECOGNITION 音源收音（不會讀取螢幕內容）。\n" +
                    "※ Android 13 以上若開關呈灰色無法開啟：請先點「解除受限制設定」→ 右上角「︙」→「允許受限制的設定」，再回無障礙開啟。",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF115E59)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onOpenAccessibilitySettings,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("前往無障礙設定", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = onOpenAppDetailsSettings,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("解除受限制設定", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun RecordingControlCard(
    recordingState: RecordingState,
    settings: AppSettings,
    isAccessibilityEnabled: Boolean,
    isDefaultDialer: Boolean,
    dialNumber: String,
    onDialNumberChange: (String) -> Unit,
    onAppendDigit: (String) -> Unit,
    onBackspaceDigit: () -> Unit,
    onStartManualRecord: () -> Unit,
    onStopRecord: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onDialWithRecord: () -> Unit,
    onDialWithoutRecord: () -> Unit,
    onImportAudio: () -> Unit
) {
    var showDialpad by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (recordingState.isRecording) UrgentRedContainer.copy(alpha = 0.45f) else Color.White
        ),
        border = BorderStroke(
            1.dp,
            if (recordingState.isRecording) UrgentRed else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (recordingState.isRecording) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(UrgentRed)
                        )
                        Text(
                            text = "通話錄音中 (${recordingState.direction.label})",
                            style = MaterialTheme.typography.titleMedium,
                            color = UrgentRed
                        )
                    }
                    Text(
                        text = EmailFormatter.formatDuration(recordingState.elapsedSeconds),
                        style = MaterialTheme.typography.titleLarge,
                        color = UrgentRed
                    )
                }

                if (recordingState.phoneNumber.isNotBlank()) {
                    Text(
                        text = "通話號碼：${recordingState.phoneNumber}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                if (recordingState.activeAudioSourceLabel.isNotBlank()) {
                    Text(
                        text = "目前音源：${recordingState.activeAudioSourceLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MedicalTeal
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onToggleSpeaker,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (recordingState.speakerEnabled) "關閉擴音" else "開啟擴音")
                    }
                    Button(
                        onClick = onStopRecord,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = UrgentRed)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("停止錄音")
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "撥號與錄音控制",
                        style = MaterialTheme.typography.titleMedium,
                        color = MedicalTeal
                    )
                    TextButton(
                        onClick = { showDialpad = !showDialpad },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Icon(Icons.Default.Dialpad, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (showDialpad) "收合鍵盤" else "開啟撥號鍵盤")
                    }
                }

                OutlinedTextField(
                    value = dialNumber,
                    onValueChange = onDialNumberChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("輸入電話號碼...") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    trailingIcon = {
                        if (dialNumber.isNotEmpty()) {
                            IconButton(onClick = onBackspaceDigit) {
                                Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "刪除")
                            }
                        }
                    },
                    shape = RoundedCornerShape(10.dp)
                )

                if (showDialpad) {
                    InAppDialpad(onDigit = onAppendDigit)
                }

                // Dual outgoing call buttons: Record vs Normal (no recording)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onDialWithRecord,
                        enabled = dialNumber.isNotBlank(),
                        modifier = Modifier.weight(1.3f),
                        colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen)
                    ) {
                        Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("撥號並錄音")
                    }
                    OutlinedButton(
                        onClick = onDialWithoutRecord,
                        enabled = dialNumber.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("一般撥號")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilledTonalButton(
                        onClick = onStartManualRecord,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("手動錄音")
                    }
                    FilledTonalButton(
                        onClick = onImportAudio,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.AudioFile, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("匯入錄音檔")
                    }
                }

                if (recordingState.errorMessage != null) {
                    Text(
                        text = recordingState.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = UrgentRed
                    )
                }

                val statusLine = buildString {
                    append(if (isDefaultDialer) "預設電話：已啟用" else "預設電話：未設定")
                    append(" ・ ")
                    append(if (isAccessibilityEnabled) "無障礙：已啟用 (${settings.audioSource.name})" else "無障礙：未啟用")
                }
                Text(
                    text = statusLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isAccessibilityEnabled) SuccessGreen else WarningAmber
                )
            }
        }
    }
}

@Composable
private fun InAppDialpad(onDigit: (String) -> Unit) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("*", "0", "#")
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFF8FAFC))
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                row.forEach { digit ->
                    Surface(
                        onClick = { onDigit(digit) },
                        shape = CircleShape,
                        color = Color.White,
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                        modifier = Modifier.size(54.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = digit,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF0F172A)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CallRecordItemCard(
    record: CallRecordEntity,
    onClick: () -> Unit,
    onAnalyzeOrRetry: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(
            width = if (record.urgencyLevel == "緊急") 1.5.dp else 1.dp,
            color = if (record.urgencyLevel == "緊急") UrgentRed.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = MedicalTeal,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = record.patientName.ifBlank {
                            record.phoneNumber.ifBlank { "${record.callDirection.label}紀錄" }
                        },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (record.urgencyLevel.isNotBlank() && record.summary.isNotBlank()) {
                        UrgencyBadge(record.urgencyLevel)
                    }
                }
                StatusBadge(record.processingStatus)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (record.patientIdentifier.isNotBlank()) {
                    Text(
                        text = "病歷／證號：${record.patientIdentifier}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (record.caseManagerName.isNotBlank()) {
                    Text(
                        text = "個管師：${record.caseManagerName}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MedicalTeal
                    )
                }
            }

            if (record.summary.isNotBlank()) {
                Text(
                    text = record.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            } else if (!record.errorMessage.isNullOrBlank()) {
                Text(
                    text = record.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = UrgentRed,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val supplementTag = if (record.hasSupplementAudio) {
                    " ・ 含口述補充(${EmailFormatter.formatDuration(record.supplementDurationSeconds)})"
                } else {
                    ""
                }
                Text(
                    text = "${EmailFormatter.formatShortTimestamp(record.startedAtMillis)} ・ ${record.callDirection.label} ・ ${EmailFormatter.formatDuration(record.durationSeconds)}$supplementTag",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (record.emailSent) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "已寄信",
                            tint = SuccessGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "已寄信",
                            style = MaterialTheme.typography.labelMedium,
                            color = SuccessGreen
                        )
                    } else if (record.processingStatus == ProcessingStatus.PENDING) {
                        Button(
                            onClick = onAnalyzeOrRetry,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("開始分析", style = MaterialTheme.typography.labelMedium)
                        }
                    } else if (record.processingStatus == ProcessingStatus.FAILED) {
                        FilledTonalButton(
                            onClick = onAnalyzeOrRetry,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("重試", style = MaterialTheme.typography.labelMedium)
                        }
                    } else if (record.processingStatus == ProcessingStatus.COMPLETED_NO_EMAIL) {
                        Icon(
                            imageVector = Icons.Default.Email,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "未寄信",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun hasAudioPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED
}

private fun placePhoneCall(context: Context, rawNumber: String) {
    val sanitized = rawNumber.trim()
    if (sanitized.isEmpty()) return

    val hasCallPerm = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.CALL_PHONE
    ) == PackageManager.PERMISSION_GRANTED

    val telUri = Uri.fromParts("tel", sanitized, null)
    if (hasCallPerm) {
        val telecom = context.getSystemService(TelecomManager::class.java)
        val placed = runCatching {
            telecom?.placeCall(telUri, Bundle())
            telecom != null
        }.getOrDefault(false)
        if (placed) return
    }

    val action = if (hasCallPerm) Intent.ACTION_CALL else Intent.ACTION_DIAL
    val intent = Intent(action, Uri.parse("tel:${Uri.encode(sanitized)}"))
    runCatching {
        context.startActivity(intent)
    }.onFailure {
        Toast.makeText(context, "無法開啟電話撥號功能", Toast.LENGTH_SHORT).show()
    }
}

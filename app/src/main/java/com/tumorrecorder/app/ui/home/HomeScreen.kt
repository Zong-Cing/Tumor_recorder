package com.tumorrecorder.app.ui.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tumorrecorder.app.data.local.CallRecordEntity
import com.tumorrecorder.app.data.remote.EmailFormatter
import com.tumorrecorder.app.domain.model.AppSettings
import com.tumorrecorder.app.domain.model.CallDirection
import com.tumorrecorder.app.domain.model.ProcessingStatus
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
    onRequestPermissions: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenAppDetailsSettings: () -> Unit,
    onOpenRecord: (Long) -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val records by viewModel.records.collectAsStateWithLifecycle()
    val recordingState by viewModel.recordingState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val dialNumber by viewModel.dialNumber.collectAsStateWithLifecycle()
    val snackbarMessage by viewModel.snackbarMessage.collectAsStateWithLifecycle()

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
                            text = settings.defaultCaseManagerUnit,
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
            if (!hasRequiredPermissions) {
                item(key = "permission_banner") {
                    SetupWarningCard(
                        title = "尚未授予通話偵測與錄音權限",
                        message = "請授予麥克風、電話狀態、通話紀錄與通知權限，以便來電／去電時自動錄音並於背景通知。",
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

            if (!settings.isGeminiConfigured || !settings.isEmailConfigured) {
                item(key = "config_banner") {
                    val missing = buildList {
                        if (!settings.isGeminiConfigured) add("Gemini API Key")
                        if (!settings.isEmailConfigured) add("Gmail SMTP 自動寄信")
                    }.joinToString("、")
                    SetupWarningCard(
                        title = "尚未完成雲端服務設定",
                        message = "尚未設定：$missing。完成設定後，通話結束將自動產出病患個管摘要、完整逐字稿並寄至指定信箱。",
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
                    dialNumber = dialNumber,
                    onDialNumberChange = viewModel::onDialNumberChanged,
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
                    onDialCall = {
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
                        text = if (settings.autoRecordCalls) "自動監聽來去電：開啟" else "自動監聽：關閉",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (settings.autoRecordCalls) MedicalTeal else MaterialTheme.colorScheme.onSurfaceVariant
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
                                text = "接聽／撥出電話時會自動錄音，或點擊上方「手動錄音」、「匯入音檔」立即體驗腫瘤個管 AI 摘要與寄信。",
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
                        onRetry = { viewModel.retryProcessing(record.id) }
                    )
                }
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
    dialNumber: String,
    onDialNumberChange: (String) -> Unit,
    onStartManualRecord: () -> Unit,
    onStopRecord: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onDialCall: () -> Unit,
    onImportAudio: () -> Unit
) {
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
                        Text("停止並摘要")
                    }
                }
            } else {
                Text(
                    text = "快速撥號與錄音控制",
                    style = MaterialTheme.typography.titleMedium,
                    color = MedicalTeal
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = dialNumber,
                        onValueChange = onDialNumberChange,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("輸入回電號碼（選填）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        shape = RoundedCornerShape(10.dp)
                    )
                    Button(
                        onClick = onDialCall,
                        enabled = dialNumber.isNotBlank(),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp)
                    ) {
                        Icon(Icons.Default.Call, contentDescription = "撥號", modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("撥號")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
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

                val a11yStatusText = if (isAccessibilityEnabled) {
                    "無障礙錄音服務：已啟用 ・ 音源：${settings.audioSource.name}"
                } else {
                    "無障礙錄音服務：尚未啟用（建議至上方啟用，避免通話中遭系統靜音）"
                }
                Text(
                    text = a11yStatusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isAccessibilityEnabled) SuccessGreen else WarningAmber
                )
            }
        }
    }
}

@Composable
private fun CallRecordItemCard(
    record: CallRecordEntity,
    onClick: () -> Unit,
    onRetry: () -> Unit
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

            // Key oncology metadata row
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
                Text(
                    text = "${EmailFormatter.formatShortTimestamp(record.startedAtMillis)} ・ ${record.callDirection.label} ・ ${EmailFormatter.formatDuration(record.durationSeconds)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                    } else if (record.processingStatus == ProcessingStatus.FAILED || record.processingStatus == ProcessingStatus.PENDING) {
                        FilledTonalButton(
                            onClick = onRetry,
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
    val action = if (hasCallPerm) Intent.ACTION_CALL else Intent.ACTION_DIAL
    val intent = Intent(action, Uri.parse("tel:${Uri.encode(sanitized)}"))
    runCatching {
        context.startActivity(intent)
    }.onFailure {
        Toast.makeText(context, "無法開啟電話撥號功能", Toast.LENGTH_SHORT).show()
    }
}

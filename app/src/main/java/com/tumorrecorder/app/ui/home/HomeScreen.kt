package com.tumorrecorder.app.ui.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import com.tumorrecorder.app.data.local.DeviceCallLogEntry
import com.tumorrecorder.app.data.remote.EmailFormatter
import com.tumorrecorder.app.domain.model.AppSettings
import com.tumorrecorder.app.domain.model.CallDirection
import com.tumorrecorder.app.service.CallManager
import com.tumorrecorder.app.service.CallRecordingService
import com.tumorrecorder.app.service.RecordingState
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
    canUseFullScreenIntent: Boolean,
    onRequestPermissions: () -> Unit,
    onRequestDefaultDialer: () -> Unit,
    onOpenFullScreenIntentSettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenAppDetailsSettings: () -> Unit,
    onOpenConsultationRecords: () -> Unit,
    onOpenRecord: (Long) -> Unit,
    onOpenPrompts: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val callLogs by viewModel.filteredCallLogs.collectAsStateWithLifecycle()
    val callLogFilter by viewModel.callLogFilter.collectAsStateWithLifecycle()
    val hasCallLogPermission by viewModel.hasCallLogPermission.collectAsStateWithLifecycle()
    val consultationRecordCount by viewModel.consultationRecordCount.collectAsStateWithLifecycle()
    val pendingAnalysisCount by viewModel.pendingAnalysisCount.collectAsStateWithLifecycle()
    val recordingState by viewModel.recordingState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val dialNumber by viewModel.dialNumber.collectAsStateWithLifecycle()
    val snackbarMessage by viewModel.snackbarMessage.collectAsStateWithLifecycle()
    val pendingConfirmRecord by viewModel.pendingConfirmRecord.collectAsStateWithLifecycle()
    val promptTemplates by viewModel.promptTemplates.collectAsStateWithLifecycle()

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

            if (isDefaultDialer && !canUseFullScreenIntent) {
                item(key = "fsi_banner") {
                    SetupWarningCard(
                        title = "請開啟「全螢幕來電通知」權限",
                        message = "Android 14 以上需開啟此權限，鎖定畫面有來電時才會自動亮屏彈出接聽畫面，避免漏接電話。",
                        actionLabel = "開啟全螢幕來電權限",
                        onAction = onOpenFullScreenIntentSettings
                    )
                }
            }

            if (!hasRequiredPermissions) {
                item(key = "permission_banner") {
                    SetupWarningCard(
                        title = "尚未授予通話與錄音權限",
                        message = "請授予麥克風、撥號、電話狀態、通話紀錄與通知權限，以便直接透過本 App 收發電話、顯示通話紀錄與錄音。",
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
                        if (!settings.isAiConfigured) add("Gemini API Key")
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

            item(key = "consultation_records_entry") {
                ConsultationRecordsEntryCard(
                    totalCount = consultationRecordCount,
                    pendingCount = pendingAnalysisCount,
                    onClick = onOpenConsultationRecords
                )
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

            item(key = "call_log_header") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = null,
                                tint = MedicalTeal,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "最近通話紀錄（撥號／來電）",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        IconButton(
                            onClick = { viewModel.refreshCallLog() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "重新整理通話紀錄",
                                tint = MedicalTeal,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CallLogFilter.entries.forEach { filter ->
                            FilterChip(
                                selected = callLogFilter == filter,
                                onClick = { viewModel.onCallLogFilterChanged(filter) },
                                label = { Text(filter.label) }
                            )
                        }
                    }
                }
            }

            if (!hasCallLogPermission) {
                item(key = "call_log_perm_card") {
                    SetupWarningCard(
                        title = "需要「通話紀錄」讀取權限",
                        message = "授權後即可在首頁直接檢視最近的來電、撥出與未接紀錄，並一鍵選擇錄音回撥或一般回撥。",
                        actionLabel = "授權讀取通話紀錄",
                        onAction = onRequestPermissions
                    )
                }
            } else if (callLogs.isEmpty()) {
                item(key = "call_log_empty") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Call,
                                contentDescription = null,
                                tint = MedicalTeal.copy(alpha = 0.55f),
                                modifier = Modifier.size(36.dp)
                            )
                            Text(
                                text = "目前沒有${if (callLogFilter == CallLogFilter.ALL) "" else callLogFilter.label}通話紀錄",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "有來電或撥出電話後會自動顯示於此清單，點擊可直接回撥。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(callLogs, key = { it.id }) { entry ->
                    DeviceCallLogRow(
                        entry = entry,
                        onFillNumber = {
                            if (entry.number.isNotBlank()) {
                                viewModel.onDialNumberChanged(entry.number)
                            }
                        },
                        onCallWithRecord = {
                            if (entry.number.isNotBlank()) {
                                viewModel.onDialNumberChanged(entry.number)
                                CallManager.prepareAppOutgoingCall(recordThisCall = true)
                                placePhoneCall(context, entry.number)
                            }
                        },
                        onCallWithoutRecord = {
                            if (entry.number.isNotBlank()) {
                                viewModel.onDialNumberChanged(entry.number)
                                CallManager.prepareAppOutgoingCall(recordThisCall = false)
                                placePhoneCall(context, entry.number)
                            }
                        }
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
}

@Composable
private fun ConsultationRecordsEntryCard(
    totalCount: Int,
    pendingCount: Int,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MedicalTealLight.copy(alpha = 0.65f)),
        border = BorderStroke(1.dp, MedicalTeal.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(MedicalTeal),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.FolderShared,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "通話諮詢紀錄與搜尋",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MedicalTeal
                )
                val subtitle = buildString {
                    append("共 $totalCount 筆錄音摘要紀錄")
                    if (pendingCount > 0) {
                        append(" ・ $pendingCount 筆待分析")
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF115E59)
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "進入通話諮詢紀錄",
                tint = MedicalTeal,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

@Composable
private fun DeviceCallLogRow(
    entry: DeviceCallLogEntry,
    onFillNumber: () -> Unit,
    onCallWithRecord: () -> Unit,
    onCallWithoutRecord: () -> Unit
) {
    val directionIcon = when {
        entry.isMissedOrRejected -> Icons.AutoMirrored.Filled.CallMissed
        entry.type == CallLog.Calls.OUTGOING_TYPE -> Icons.AutoMirrored.Filled.CallMade
        else -> Icons.AutoMirrored.Filled.CallReceived
    }
    val directionTint = when {
        entry.isMissedOrRejected -> UrgentRed
        entry.type == CallLog.Calls.OUTGOING_TYPE -> SuccessGreen
        else -> MedicalTeal
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onFillNumber),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(directionTint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = directionIcon,
                    contentDescription = entry.typeLabel,
                    tint = directionTint,
                    modifier = Modifier.size(18.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = entry.displayTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (entry.isMissedOrRejected) UrgentRed else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (entry.hasCachedName && entry.number.isNotBlank()) {
                    Text(
                        text = entry.number,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val durationPart = if (entry.durationSeconds > 0L) {
                    " ・ ${EmailFormatter.formatDuration(entry.durationSeconds)}"
                } else {
                    ""
                }
                Text(
                    text = "${entry.typeLabel} ・ ${EmailFormatter.formatShortTimestamp(entry.dateMillis)}$durationPart",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (entry.number.isNotBlank()) {
                FilledTonalIconButton(
                    onClick = onCallWithRecord,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = SuccessGreen,
                        contentColor = Color.White
                    ),
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = "錄音回撥",
                            modifier = Modifier.size(19.dp)
                        )
                        Icon(
                            imageVector = Icons.Default.FiberManualRecord,
                            contentDescription = null,
                            tint = Color(0xFFFCA5A5),
                            modifier = Modifier
                                .size(8.dp)
                                .align(Alignment.TopEnd)
                        )
                    }
                }
                IconButton(
                    onClick = onCallWithoutRecord,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Call,
                        contentDescription = "一般回撥（不錄音）",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }
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
                    placeholder = { Text("輸入電話號碼或點下方紀錄填入...") },
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

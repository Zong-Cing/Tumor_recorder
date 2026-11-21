package com.tumorrecorder.app.ui.detail

import android.media.MediaPlayer
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tumorrecorder.app.data.local.CallRecordEntity
import com.tumorrecorder.app.data.remote.EmailFormatter
import com.tumorrecorder.app.domain.model.AppSettings
import com.tumorrecorder.app.ui.components.StatusBadge
import com.tumorrecorder.app.ui.components.UrgencyBadge
import com.tumorrecorder.app.ui.theme.MedicalTeal
import com.tumorrecorder.app.ui.theme.SuccessGreen
import com.tumorrecorder.app.ui.theme.UrgentRed
import com.tumorrecorder.app.ui.theme.UrgentRedContainer
import kotlinx.coroutines.delay
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecordDetailScreen(
    viewModel: RecordDetailViewModel,
    settings: AppSettings,
    onBack: () -> Unit
) {
    val record by viewModel.record.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val deleted by viewModel.deleted.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current

    var selectedTab by remember { mutableIntStateOf(0) }
    var isEditing by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Editable drafts
    var draftPatientName by remember { mutableStateOf("") }
    var draftIdentifier by remember { mutableStateOf("") }
    var draftIdentifierType by remember { mutableStateOf("") }
    var draftCaseManager by remember { mutableStateOf("") }
    var draftCallerRelation by remember { mutableStateOf("") }
    var draftCategory by remember { mutableStateOf("") }
    var draftUrgency by remember { mutableStateOf("一般") }
    var draftSummary by remember { mutableStateOf("") }
    var draftActions by remember { mutableStateOf("") }

    LaunchedEffect(deleted) {
        if (deleted) onBack()
    }

    LaunchedEffect(message) {
        val msg = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("通話個管紀錄詳情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    val current = record
                    if (current != null) {
                        IconButton(
                            onClick = {
                                if (!isEditing) {
                                    draftPatientName = current.patientName
                                    draftIdentifier = current.patientIdentifier
                                    draftIdentifierType = current.identifierType
                                    draftCaseManager = current.caseManagerName
                                    draftCallerRelation = current.callerRelation
                                    draftCategory = current.inquiryCategory
                                    draftUrgency = current.urgencyLevel.ifBlank { "一般" }
                                    draftSummary = current.summary
                                    draftActions = current.actionItemsText
                                    isEditing = true
                                } else {
                                    viewModel.saveEdits(
                                        patientName = draftPatientName,
                                        patientIdentifier = draftIdentifier,
                                        identifierType = draftIdentifierType,
                                        caseManagerName = draftCaseManager,
                                        callerRelation = draftCallerRelation,
                                        inquiryCategory = draftCategory,
                                        urgencyLevel = draftUrgency,
                                        summary = draftSummary,
                                        actionItemsText = draftActions
                                    )
                                    isEditing = false
                                }
                            }
                        ) {
                            Icon(
                                imageVector = if (isEditing) Icons.Default.Check else Icons.Default.Edit,
                                contentDescription = if (isEditing) "儲存" else "編輯核對"
                            )
                        }
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "刪除")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MedicalTeal,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        val current = record
        if (current == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MedicalTeal)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Status & Metadata header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusBadge(current.processingStatus)
                if (current.urgencyLevel.isNotBlank()) {
                    UrgencyBadge(current.urgencyLevel)
                }
            }

            if (!current.errorMessage.isNullOrBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = UrgentRedContainer)
                ) {
                    Text(
                        text = current.errorMessage,
                        color = UrgentRed,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // Audio playback card
            AudioPlayerCard(audioPath = current.audioFilePath)

            // Patient & Oncology Case Manager Info Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "腫瘤個管基本資訊",
                            style = MaterialTheme.typography.titleMedium,
                            color = MedicalTeal
                        )
                        if (isEditing) {
                            TextButton(onClick = { isEditing = false }) {
                                Text("取消編輯")
                            }
                        }
                    }

                    if (isEditing) {
                        OutlinedTextField(
                            value = draftPatientName,
                            onValueChange = { draftPatientName = it },
                            label = { Text("病人姓名") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = draftIdentifier,
                            onValueChange = { draftIdentifier = it },
                            label = { Text("病歷號碼／身分證字號") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = draftIdentifierType,
                            onValueChange = { draftIdentifierType = it },
                            label = { Text("證件類型（病歷號碼／身分證字號）") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = draftCaseManager,
                            onValueChange = { draftCaseManager = it },
                            label = { Text("所屬個管師姓名") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = draftCallerRelation,
                            onValueChange = { draftCallerRelation = it },
                            label = { Text("來電者身分") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = draftCategory,
                            onValueChange = { draftCategory = it },
                            label = { Text("諮詢分類") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Text("緊急程度", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("一般", "需追蹤", "緊急").forEach { level ->
                                FilterChip(
                                    selected = draftUrgency == level,
                                    onClick = { draftUrgency = level },
                                    label = { Text(level) }
                                )
                            }
                        }
                        OutlinedTextField(
                            value = draftSummary,
                            onValueChange = { draftSummary = it },
                            label = { Text("通話重點摘要") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 4
                        )
                        OutlinedTextField(
                            value = draftActions,
                            onValueChange = { draftActions = it },
                            label = { Text("後續待辦事項（每行一項）") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2
                        )
                        Button(
                            onClick = {
                                viewModel.saveEdits(
                                    patientName = draftPatientName,
                                    patientIdentifier = draftIdentifier,
                                    identifierType = draftIdentifierType,
                                    caseManagerName = draftCaseManager,
                                    callerRelation = draftCallerRelation,
                                    inquiryCategory = draftCategory,
                                    urgencyLevel = draftUrgency,
                                    summary = draftSummary,
                                    actionItemsText = draftActions
                                )
                                isEditing = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("儲存核對結果")
                        }
                    } else {
                        InfoRow("病人姓名", current.patientName.ifBlank { "未提及" })
                        InfoRow(
                            "病歷號／身分證",
                            buildString {
                                append(current.patientIdentifier.ifBlank { "未提及" })
                                if (current.identifierType.isNotBlank() && current.identifierType != "未提及") {
                                    append("（${current.identifierType}）")
                                }
                            }
                        )
                        InfoRow("所屬個管師", current.caseManagerName.ifBlank { "未提及" })
                        InfoRow("來電者身分", current.callerRelation.ifBlank { "未提及" })
                        InfoRow("諮詢分類", current.inquiryCategory.ifBlank { "一般諮詢" })
                        InfoRow(
                            "通話資訊",
                            "${current.phoneNumber.ifBlank { "未知號碼" }}（${current.callDirection.label}）・${EmailFormatter.formatDuration(current.durationSeconds)}"
                        )
                        InfoRow("通話時間", EmailFormatter.formatTimestamp(current.startedAtMillis))
                        if (current.emailSent && current.emailSentAtMillis != null) {
                            InfoRow(
                                "寄信狀態",
                                "已於 ${EmailFormatter.formatShortTimestamp(current.emailSentAtMillis)} 寄至 ${current.emailRecipientSnapshot}",
                                valueColor = SuccessGreen
                            )
                        }
                    }
                }
            }

            // Action Buttons
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { viewModel.resendEmail() },
                    enabled = !isBusy && current.summary.isNotBlank()
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    } else {
                        Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(if (current.emailSent) "重新寄信" else "寄送摘要信")
                }

                FilledTonalButton(
                    onClick = { viewModel.reAnalyze() },
                    enabled = !isBusy && current.audioFilePath.isNotBlank()
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("重新 AI 分析")
                }

                OutlinedButton(
                    onClick = {
                        val plain = EmailFormatter.buildPlainTextBody(current, settings)
                        clipboard.setText(AnnotatedString(plain))
                    }
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("複製全文")
                }
            }

            // Summary vs Transcript Tabs
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            ) {
                Column {
                    TabRow(selectedTabIndex = selectedTab) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("重點摘要與待辦") }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("完整逐字稿") }
                        )
                    }

                    SelectionContainer {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (selectedTab == 0) {
                                Text(
                                    text = "討論重點摘要",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MedicalTeal
                                )
                                Text(
                                    text = current.summary.ifBlank { "尚未產生摘要，請稍候或點擊「重新 AI 分析」。" },
                                    style = MaterialTheme.typography.bodyLarge
                                )

                                HorizontalDivider()

                                Text(
                                    text = "後續待辦／追蹤事項",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MedicalTeal
                                )
                                val items = current.actionItemsList()
                                if (items.isEmpty()) {
                                    Text(
                                        text = "無特別待辦事項",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    items.forEachIndexed { idx, item ->
                                        Text(
                                            text = "${idx + 1}. $item",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            } else {
                                Text(
                                    text = "完整通話逐字稿",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MedicalTeal
                                )
                                Text(
                                    text = current.transcript.ifBlank { "尚未產生逐字稿。" },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("確認刪除此筆通話紀錄？") },
            text = { Text("刪除後將同時移除本機錄音檔、摘要與逐字稿，無法復原。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteRecord()
                    }
                ) {
                    Text("確認刪除", color = UrgentRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(104.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = valueColor,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun AudioPlayerCard(audioPath: String) {
    val file = remember(audioPath) { audioPath.takeIf { it.isNotBlank() }?.let(::File) }
    val exists = file != null && file.exists() && file.length() > 0L

    var isPlaying by remember { mutableStateOf(false) }
    var currentMs by remember { mutableIntStateOf(0) }
    var totalMs by remember { mutableIntStateOf(0) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(audioPath) {
        onDispose {
            runCatching {
                mediaPlayer?.stop()
                mediaPlayer?.release()
            }
            mediaPlayer = null
        }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            val mp = mediaPlayer
            if (mp != null && mp.isPlaying) {
                currentMs = mp.currentPosition
                totalMs = mp.duration.coerceAtLeast(1)
            }
            delay(250L)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        if (!exists) {
            Text(
                text = "本機原始錄音檔已移除或不存在（摘要與逐字稿仍完整保留）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(14.dp)
            )
            return@Card
        }

        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilledTonalButton(
                    onClick = {
                        val mp = mediaPlayer
                        if (mp == null) {
                            val created = MediaPlayer().apply {
                                setDataSource(file!!.absolutePath)
                                prepare()
                                setOnCompletionListener {
                                    isPlaying = false
                                    currentMs = 0
                                }
                                start()
                            }
                            mediaPlayer = created
                            totalMs = created.duration.coerceAtLeast(1)
                            isPlaying = true
                        } else if (mp.isPlaying) {
                            mp.pause()
                            isPlaying = false
                        } else {
                            mp.start()
                            isPlaying = true
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "暫停" else "播放"
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (isPlaying) "暫停" else "播放錄音")
                }

                Text(
                    text = "${EmailFormatter.formatDuration((currentMs / 1000).toLong())} / ${EmailFormatter.formatDuration((totalMs / 1000).toLong())}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (totalMs > 0) {
                Slider(
                    value = currentMs.toFloat().coerceIn(0f, totalMs.toFloat()),
                    onValueChange = { pos ->
                        currentMs = pos.toInt()
                        mediaPlayer?.seekTo(currentMs)
                    },
                    valueRange = 0f..totalMs.toFloat()
                )
            }
        }
    }
}

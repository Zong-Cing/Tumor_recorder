package com.tumorrecorder.app.ui.records

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tumorrecorder.app.data.local.CallRecordEntity
import com.tumorrecorder.app.data.remote.EmailFormatter
import com.tumorrecorder.app.domain.model.ProcessingStatus
import com.tumorrecorder.app.ui.components.PromptTemplatePickerList
import com.tumorrecorder.app.ui.components.StatusBadge
import com.tumorrecorder.app.ui.components.UrgencyBadge
import com.tumorrecorder.app.ui.theme.MedicalTeal
import com.tumorrecorder.app.ui.theme.SuccessGreen
import com.tumorrecorder.app.ui.theme.UrgentRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsultationRecordsScreen(
    viewModel: ConsultationRecordsViewModel,
    onOpenRecord: (Long) -> Unit,
    onBack: () -> Unit
) {
    val records by viewModel.records.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val promptTemplates by viewModel.promptTemplates.collectAsStateWithLifecycle()
    val snackbarMessage by viewModel.snackbarMessage.collectAsStateWithLifecycle()

    var manualAnalyzeRecordId by remember { mutableStateOf<Long?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(snackbarMessage) {
        val msg = snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearSnackbar()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "通話諮詢紀錄與搜尋",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "共 ${records.size} 筆 ・ ${if (settings.autoAnalyzeAfterCall) "掛斷自動分析" else "掛斷先詢問分析"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回撥號首頁")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MedicalTeal,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
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
                                text = if (searchQuery.isBlank()) "尚無通話諮詢紀錄" else "找不到符合「$searchQuery」的紀錄",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "透過本 App 接聽或撥出錄音通話後，錄音、AI 摘要、口述補充與完整逐字稿都會保存在這裡。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(records, key = { it.id }) { record ->
                    ConsultationRecordCard(
                        record = record,
                        onClick = { onOpenRecord(record.id) },
                        onAnalyzeOrRetry = { manualAnalyzeRecordId = record.id }
                    )
                }
            }
        }
    }

    val targetManualId = manualAnalyzeRecordId
    if (targetManualId != null) {
        var selectedPromptId by remember(targetManualId, promptTemplates) {
            mutableStateOf(
                (promptTemplates.firstOrNull { it.isDefault } ?: promptTemplates.firstOrNull())?.id
            )
        }
        var includeTranscript by remember(targetManualId, settings.generateTranscriptByDefault) {
            mutableStateOf(settings.generateTranscriptByDefault)
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
                    PromptTemplatePickerList(
                        templates = promptTemplates,
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
                        Text(
                            text = "同時產生完整逐字稿（選填）",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.analyzeWithPrompt(
                            recordId = targetManualId,
                            promptTemplateId = selectedPromptId ?: -1L,
                            includeTranscript = includeTranscript
                        )
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
private fun ConsultationRecordCard(
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

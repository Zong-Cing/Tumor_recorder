package com.tumorrecorder.app.ui.prompts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tumorrecorder.app.data.local.PromptTemplateEntity
import com.tumorrecorder.app.ui.theme.MedicalTeal
import com.tumorrecorder.app.ui.theme.MedicalTealLight
import com.tumorrecorder.app.ui.theme.UrgentRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptManagerScreen(
    viewModel: PromptManagerViewModel,
    onBack: () -> Unit
) {
    val templates by viewModel.templates.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var editingTemplate by remember { mutableStateOf<PromptTemplateEntity?>(null) }
    var isCreatingNew by remember { mutableStateOf(false) }
    var deletingTemplate by remember { mutableStateOf<PromptTemplateEntity?>(null) }

    LaunchedEffect(message) {
        val msg = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("分析 Prompt 範本管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.restoreMissingDefaults() }) {
                        Icon(Icons.Default.Restore, contentDescription = "補回內建範本")
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
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    editingTemplate = null
                    isCreatingNew = true
                },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("新增 Prompt") },
                containerColor = MedicalTeal,
                contentColor = Color.White
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "intro") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MedicalTealLight.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = "您可以自由編輯、修改或新增多組分析 Prompt。每次通話掛斷後，系統會先詢問您要使用哪一組 Prompt 進行 AI 摘要與寄信。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF115E59),
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            items(templates, key = { it.id }) { template ->
                PromptTemplateCard(
                    template = template,
                    canDelete = templates.size > 1,
                    onEdit = {
                        isCreatingNew = false
                        editingTemplate = template
                    },
                    onSetDefault = { viewModel.setDefault(template.id) },
                    onDelete = { deletingTemplate = template }
                )
            }
        }
    }

    if (isCreatingNew || editingTemplate != null) {
        PromptEditorDialog(
            initial = editingTemplate,
            onDismiss = {
                isCreatingNew = false
                editingTemplate = null
            },
            onSave = { name, desc, content, isDefault ->
                viewModel.saveTemplate(
                    id = editingTemplate?.id ?: 0L,
                    name = name,
                    description = desc,
                    promptContent = content,
                    isDefault = isDefault
                )
                isCreatingNew = false
                editingTemplate = null
            }
        )
    }

    val toDelete = deletingTemplate
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { deletingTemplate = null },
            title = { Text("刪除 Prompt 範本？") },
            text = { Text("確定要刪除「${toDelete.name}」嗎？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteTemplate(toDelete)
                        deletingTemplate = null
                    }
                ) {
                    Text("刪除", color = UrgentRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingTemplate = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun PromptTemplateCard(
    template: PromptTemplateEntity,
    canDelete: Boolean,
    onEdit: () -> Unit,
    onSetDefault: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(
            width = if (template.isDefault) 1.5.dp else 1.dp,
            color = if (template.isDefault) MedicalTeal else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
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
                    Text(
                        text = template.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MedicalTeal
                    )
                    if (template.isDefault) {
                        Text(
                            text = "預設",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MedicalTeal,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(MedicalTealLight)
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "編輯", tint = MedicalTeal, modifier = Modifier.size(18.dp))
                }
            }

            if (template.description.isNotBlank()) {
                Text(
                    text = template.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Text(
                text = template.promptContent,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!template.isDefault) {
                    TextButton(onClick = onSetDefault) {
                        Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("設為預設")
                    }
                }
                if (canDelete) {
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = UrgentRed, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("刪除", color = UrgentRed)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PromptEditorDialog(
    initial: PromptTemplateEntity?,
    onDismiss: () -> Unit,
    onSave: (name: String, description: String, content: String, isDefault: Boolean) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var content by remember { mutableStateOf(initial?.promptContent ?: "") }
    var isDefault by remember { mutableStateOf(initial?.isDefault ?: false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = if (initial == null) "新增分析 Prompt 範本" else "編輯 Prompt 範本",
                    style = MaterialTheme.typography.titleLarge,
                    color = MedicalTeal
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("範本名稱") },
                    placeholder = { Text("例如：乳癌個管初診追蹤") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("簡短說明（選填，顯示於掛斷選擇清單）") },
                    placeholder = { Text("例如：著重術後傷口照護與回診時間") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Text(
                    text = "可插入動態變數（點擊插入）：",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "{{unitName}}" to "單位名稱",
                        "{{phoneNumber}}" to "通話號碼",
                        "{{callTime}}" to "通話時間"
                    ).forEach { (token, label) ->
                        AssistChip(
                            onClick = { content += token },
                            label = { Text("$label ($token)", style = MaterialTheme.typography.labelMedium) }
                        )
                    }
                }

                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("分析 Prompt 內容") },
                    placeholder = { Text("請輸入要指示 Gemini 如何分析與整理摘要的內容...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                )

                Text(
                    text = "註：系統會自動在尾端補上結構化 JSON 欄位規範（病人姓名、病歷號、個管師、來電者身分、摘要、逐字稿），確保輸出格式穩定。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isDefault = !isDefault }
                ) {
                    Checkbox(checked = isDefault, onCheckedChange = { isDefault = it })
                    Text("設為通話結束後的預設選取範本", style = MaterialTheme.typography.bodyMedium)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("取消")
                    }
                    Button(
                        onClick = { onSave(name, description, content, isDefault) },
                        enabled = name.isNotBlank() && content.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("儲存範本")
                    }
                }
            }
        }
    }
}

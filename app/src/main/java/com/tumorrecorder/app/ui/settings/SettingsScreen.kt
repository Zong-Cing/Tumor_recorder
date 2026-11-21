package com.tumorrecorder.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tumorrecorder.app.domain.model.AppSettings
import com.tumorrecorder.app.domain.model.RecordingAudioSource
import com.tumorrecorder.app.ui.theme.MedicalTeal
import com.tumorrecorder.app.ui.theme.MedicalTealLight
import com.tumorrecorder.app.ui.theme.SuccessGreen
import com.tumorrecorder.app.ui.theme.SuccessGreenContainer
import com.tumorrecorder.app.ui.theme.WarningAmber
import com.tumorrecorder.app.ui.theme.WarningAmberContainer

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    isAccessibilityEnabled: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenAppDetailsSettings: () -> Unit,
    onBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val testingGemini by viewModel.testingGemini.collectAsStateWithLifecycle()
    val testingEmail by viewModel.testingEmail.collectAsStateWithLifecycle()
    val feedbackMessage by viewModel.feedbackMessage.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var showGeminiKey by remember { mutableStateOf(false) }
    var showSmtpPass by remember { mutableStateOf(false) }

    LaunchedEffect(feedbackMessage) {
        val msg = feedbackMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearFeedback()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定與無障礙錄音") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section 1: AccessibilityService & Audio Source
            SettingsSectionCard(title = "1. 無障礙通話錄音服務（AccessibilityService）") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("無障礙服務狀態", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = if (isAccessibilityEnabled) "已啟用" else "尚未啟用",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isAccessibilityEnabled) SuccessGreen else WarningAmber,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (isAccessibilityEnabled) SuccessGreenContainer else WarningAmberContainer)
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }

                Text(
                    text = "啟用無障礙服務後，Android 系統會允許本 App 在通話進行時使用 VOICE_RECOGNITION 音源收音，避免被系統強制靜音（本服務不讀取任何螢幕畫面文字）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onOpenAccessibilitySettings,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(if (isAccessibilityEnabled) "管理無障礙設定" else "開啟無障礙服務")
                    }
                    OutlinedButton(
                        onClick = onOpenAppDetailsSettings,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text("解除受限制設定")
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = MedicalTealLight.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = "Android 13 / 14 / 15 自行安裝 APK 開啟步驟：\n" +
                            "1. 點上方「解除受限制設定」開啟本 App 應用程式資訊。\n" +
                            "2. 點右上角「︙」→「允許受限制的設定」（驗證指紋／密碼）。\n" +
                            "3. 回到這裡點「開啟無障礙服務」→「已安裝的應用程式」→ 開啟「腫瘤個管通話助理」。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }

                Text("通話錄音音源（依機型微調）", style = MaterialTheme.typography.titleSmall)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    RecordingAudioSource.entries.forEach { src ->
                        FilterChip(
                            selected = settings.audioSource == src,
                            onClick = { viewModel.update { it.copy(audioSource = src) } },
                            label = { Text(src.name) }
                        )
                    }
                }
                Text(
                    text = "${settings.audioSource.label}：${settings.audioSource.description}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MedicalTeal
                )
            }

            // Section 2: Call Recording Behavior
            SettingsSectionCard(title = "2. 通話監聽與自動化設定") {
                SettingToggleRow(
                    title = "來去電自動錄音",
                    subtitle = "偵測手機接通通話時自動啟動背景錄音，掛斷自動停止",
                    checked = settings.autoRecordCalls,
                    onCheckedChange = { enabled ->
                        viewModel.update { it.copy(autoRecordCalls = enabled) }
                    }
                )
                SettingToggleRow(
                    title = "通話接通時自動開啟擴音（選用備案）",
                    subtitle = "若使用無障礙 + VOICE_RECOGNITION 後對方聲音仍偏小，可開啟此項輔助收音",
                    checked = settings.autoEnableSpeakerOnCall,
                    onCheckedChange = { enabled ->
                        viewModel.update { it.copy(autoEnableSpeakerOnCall = enabled) }
                    }
                )
                SettingToggleRow(
                    title = "錄音結束自動 AI 摘要",
                    subtitle = "通話掛斷後自動上傳 Gemini 產出病人姓名、病歷號、個管師、摘要與逐字稿",
                    checked = settings.autoAnalyzeAfterCall,
                    onCheckedChange = { enabled ->
                        viewModel.update { it.copy(autoAnalyzeAfterCall = enabled) }
                    }
                )
                OutlinedTextField(
                    value = settings.defaultCaseManagerUnit,
                    onValueChange = { v -> viewModel.update { it.copy(defaultCaseManagerUnit = v) } },
                    label = { Text("所屬醫療單位名稱（顯示於信件標題）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            // Section 3: Google Gemini API
            SettingsSectionCard(title = "3. Google Gemini AI 語音轉錄與摘要") {
                OutlinedTextField(
                    value = settings.geminiApiKey,
                    onValueChange = { v -> viewModel.update { it.copy(geminiApiKey = v) } },
                    label = { Text("Gemini API Key") },
                    placeholder = { Text("AIzaSy...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (showGeminiKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showGeminiKey = !showGeminiKey }) {
                            Icon(
                                imageVector = if (showGeminiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "顯示/隱藏"
                            )
                        }
                    }
                )

                Text("模型選擇", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(
                        "gemini-4-hydrogen",
                        "gemini-4-carbon",
                        "gemini-4-neon",
                        "gemini-3.8-flash",
                        "gemini-3.5-flash-lite"
                    ).forEach { model ->
                        FilterChip(
                            selected = settings.geminiModel == model,
                            onClick = { viewModel.update { it.copy(geminiModel = model) } },
                            label = { Text(model) }
                        )
                    }
                }
                Text(
                    text = "推薦：gemini-4-hydrogen（最新主力、大幅降低幻覺、有免費額度）；追求最高複雜醫療推理可選 gemini-4-carbon；極致省錢可選 gemini-3.5-flash-lite。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = settings.geminiModel,
                    onValueChange = { v -> viewModel.update { it.copy(geminiModel = v) } },
                    label = { Text("自訂模型 ID") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = settings.customPromptAppendix,
                    onValueChange = { v -> viewModel.update { it.copy(customPromptAppendix = v) } },
                    label = { Text("額外個管摘要指示（選填）") },
                    placeholder = { Text("例如：若有提及化療藥物名稱請特別標記劑量與施打日期...") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )

                Button(
                    onClick = { viewModel.testGeminiConnection() },
                    enabled = !testingGemini && settings.geminiApiKey.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (testingGemini) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("測試 Gemini API 連線")
                }
            }

            // Section 4: Gmail SMTP Auto Email
            SettingsSectionCard(title = "4. Gmail SMTP 自動寄信設定") {
                SettingToggleRow(
                    title = "摘要完成後自動背景寄信",
                    subtitle = "不需手動點擊確認，直接寄送結構化摘要與完整逐字稿",
                    checked = settings.autoSendEmailAfterSummary,
                    onCheckedChange = { enabled ->
                        viewModel.update { it.copy(autoSendEmailAfterSummary = enabled) }
                    }
                )

                OutlinedTextField(
                    value = settings.senderEmail,
                    onValueChange = { v -> viewModel.update { it.copy(senderEmail = v) } },
                    label = { Text("寄件者 Gmail 帳號") },
                    placeholder = { Text("your.name@gmail.com") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                )

                OutlinedTextField(
                    value = settings.senderAppPassword,
                    onValueChange = { v -> viewModel.update { it.copy(senderAppPassword = v) } },
                    label = { Text("Gmail 應用程式密碼（16 碼）") },
                    placeholder = { Text("xxxx xxxx xxxx xxxx") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (showSmtpPass) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showSmtpPass = !showSmtpPass }) {
                            Icon(
                                imageVector = if (showSmtpPass) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "顯示/隱藏"
                            )
                        }
                    }
                )

                OutlinedTextField(
                    value = settings.recipientEmails,
                    onValueChange = { v -> viewModel.update { it.copy(recipientEmails = v) } },
                    label = { Text("收件人 Email（多組請用逗號分隔）") },
                    placeholder = { Text("casemanager@hospital.org.tw") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = settings.smtpHost,
                        onValueChange = { v -> viewModel.update { it.copy(smtpHost = v) } },
                        label = { Text("SMTP 主機") },
                        modifier = Modifier.weight(2f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = settings.smtpPort.toString(),
                        onValueChange = { v ->
                            val port = v.filter(Char::isDigit).toIntOrNull() ?: AppSettings.DEFAULT_SMTP_PORT
                            viewModel.update { it.copy(smtpPort = port) }
                        },
                        label = { Text("Port") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }

                SettingToggleRow(
                    title = "信件附加原始錄音檔 (.m4a)",
                    subtitle = "若考量信箱容量或院內資安，建議維持關閉（僅寄出文字摘要與逐字稿）",
                    checked = settings.attachAudioToEmail,
                    onCheckedChange = { enabled ->
                        viewModel.update { it.copy(attachAudioToEmail = enabled) }
                    }
                )

                SettingToggleRow(
                    title = "寄信成功後自動刪除本機錄音檔",
                    subtitle = "強化病患隱私保護：僅保留摘要與逐字稿文字，自動清除手機音檔",
                    checked = settings.deleteAudioAfterEmailSent,
                    onCheckedChange = { enabled ->
                        viewModel.update { it.copy(deleteAudioAfterEmailSent = enabled) }
                    }
                )

                Card(
                    colors = CardDefaults.cardColors(containerColor = MedicalTealLight.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = "如何取得 Gmail 應用程式密碼：\n" +
                            "1. 前往 Google 帳戶 →「安全性」→ 開啟「兩步驟驗證」。\n" +
                            "2. 在上方搜尋欄搜尋「應用程式密碼」(App passwords)。\n" +
                            "3. 建立名稱（如 TumorRecorder）即可獲得 16 位專屬密碼，貼入上方欄位即可（勿使用一般登入密碼）。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }

                Button(
                    onClick = { viewModel.sendTestEmail() },
                    enabled = !testingEmail && settings.isEmailConfigured,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (testingEmail) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("寄送測試信件")
                }
            }

            // Section 5: Privacy Notes
            SettingsSectionCard(title = "5. 醫療隱私與安全說明") {
                Text(
                    text = "• 金鑰與密碼均使用 Android Keystore AES-256 加密儲存於本機。\n" +
                        "• 無障礙服務設定 canRetrieveWindowContent=\"false\"，絕不讀取螢幕畫面內容。\n" +
                        "• 上傳至 Gemini Files API 的音檔會在摘要完成後立即呼叫 API 刪除暫存檔。\n" +
                        "• 涉及病人姓名、病歷號與身分證字號等個資，請遵守所屬醫院資訊安全與個資法規定，並於歸檔前人工核對 AI 摘要內容。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MedicalTeal
            )
            content()
        }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

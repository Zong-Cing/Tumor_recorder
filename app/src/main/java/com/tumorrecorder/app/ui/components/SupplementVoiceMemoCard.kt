package com.tumorrecorder.app.ui.components

import android.media.MediaPlayer
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tumorrecorder.app.data.remote.EmailFormatter
import com.tumorrecorder.app.service.audio.VoiceMemoRecorder
import com.tumorrecorder.app.ui.theme.MedicalTeal
import com.tumorrecorder.app.ui.theme.MedicalTealLight
import com.tumorrecorder.app.ui.theme.SuccessGreen
import com.tumorrecorder.app.ui.theme.SuccessGreenContainer
import com.tumorrecorder.app.ui.theme.UrgentRed
import com.tumorrecorder.app.ui.theme.UrgentRedContainer
import kotlinx.coroutines.delay
import java.io.File

/**
 * Reusable post-call voice supplement memo recorder & player card.
 * Used both on InCallActivity's PostCallAnalysisPromptScreen and on RecordDetailScreen.
 */
@Composable
fun SupplementVoiceMemoCard(
    recordId: Long,
    recordingsDir: File,
    supplementAudioPath: String,
    supplementDurationSeconds: Long,
    onSaveSupplement: (file: File, durationSeconds: Long) -> Unit,
    onClearSupplement: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onRecordingStateChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val memoRecorder = remember(recordId) { VoiceMemoRecorder(context.applicationContext) }

    var isRecording by remember(recordId) { mutableStateOf(false) }
    var recordElapsedSec by remember(recordId) { mutableLongStateOf(0L) }
    var recorderError by remember(recordId) { mutableStateOf<String?>(null) }

    val existingFile = remember(supplementAudioPath) {
        supplementAudioPath.takeIf { it.isNotBlank() }?.let(::File)
    }
    val hasValidFile = existingFile != null && existingFile.exists() && existingFile.length() > 0L

    // Playback state
    var isPlaying by remember(supplementAudioPath) { mutableStateOf(false) }
    var currentMs by remember(supplementAudioPath) { mutableIntStateOf(0) }
    var totalMs by remember(supplementAudioPath) { mutableIntStateOf(0) }
    var mediaPlayer by remember(supplementAudioPath) { mutableStateOf<MediaPlayer?>(null) }

    fun releasePlayer() {
        runCatching {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        }
        mediaPlayer = null
        isPlaying = false
        currentMs = 0
        totalMs = 0
    }

    DisposableEffect(recordId) {
        onDispose {
            if (memoRecorder.isRecording) {
                memoRecorder.cancelAndRelease()
                onRecordingStateChange(false)
            }
            releasePlayer()
        }
    }

    DisposableEffect(supplementAudioPath) {
        onDispose {
            releasePlayer()
        }
    }

    LaunchedEffect(isRecording) {
        onRecordingStateChange(isRecording)
        if (isRecording) {
            val started = System.currentTimeMillis()
            while (isRecording) {
                recordElapsedSec = ((System.currentTimeMillis() - started) / 1000L).coerceAtLeast(0L)
                delay(500L)
            }
        } else {
            recordElapsedSec = 0L
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

    fun startRecordingMemo() {
        releasePlayer()
        recorderError = null
        val target = File(recordingsDir, "supplement_${recordId}_${System.currentTimeMillis()}.m4a")
        val started = memoRecorder.start(target)
        if (started) {
            isRecording = true
        } else {
            recorderError = "無法啟動麥克風錄製補充摘要，請確認錄音權限後重試。"
        }
    }

    fun stopAndSaveMemo() {
        val result = memoRecorder.stop()
        isRecording = false
        if (result != null) {
            recorderError = null
            onSaveSupplement(result.first, result.second)
        } else {
            recorderError = "補充錄音時間過短，未儲存。請長按／錄製超過 1 秒。"
        }
    }

    val containerBg = when {
        isRecording -> UrgentRedContainer.copy(alpha = 0.55f)
        hasValidFile -> SuccessGreenContainer.copy(alpha = 0.45f)
        else -> MedicalTealLight.copy(alpha = 0.45f)
    }
    val borderColor = when {
        isRecording -> UrgentRed
        hasValidFile -> SuccessGreen.copy(alpha = 0.6f)
        else -> MedicalTeal.copy(alpha = 0.45f)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerBg),
        border = BorderStroke(1.dp, borderColor),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(if (compact) 12.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (isRecording) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(UrgentRed)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = null,
                            tint = if (hasValidFile) SuccessGreen else MedicalTeal,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Text(
                        text = when {
                            isRecording -> "正在錄製口述補充摘要..."
                            hasValidFile -> "已附加口述補充摘要"
                            else -> "通話後口述補充摘要（選填）"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            isRecording -> UrgentRed
                            hasValidFile -> SuccessGreen
                            else -> MedicalTeal
                        }
                    )
                }

                if (isRecording) {
                    Text(
                        text = EmailFormatter.formatDuration(recordElapsedSec),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = UrgentRed
                    )
                } else if (hasValidFile) {
                    Text(
                        text = EmailFormatter.formatDuration(supplementDurationSeconds),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = SuccessGreen
                    )
                }
            }

            Text(
                text = when {
                    isRecording -> "請口述補充：是否代班接聽（原負責個管師）、病人姓名／病歷號、未收錄到的症狀數值或須轉交事項。"
                    hasValidFile -> "此補充錄音會另外獨立保存，並於 AI 分析時與原始通話錄音「合併整合」為一份完整摘要。"
                    else -> "掛斷後可直接補講 10 秒摘要（如：代班對象、病人資訊、重要決策），將與通話錄音分開保存並一併送交 AI 分析。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (isRecording) {
                Button(
                    onClick = { stopAndSaveMemo() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = UrgentRed)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("停止並儲存補充錄音 (${EmailFormatter.formatDuration(recordElapsedSec)})")
                }
            } else if (hasValidFile) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            val mp = mediaPlayer
                            if (mp == null) {
                                runCatching {
                                    val created = MediaPlayer().apply {
                                        setDataSource(existingFile!!.absolutePath)
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
                                }.onFailure {
                                    recorderError = "無法播放補充錄音檔"
                                }
                            } else if (mp.isPlaying) {
                                mp.pause()
                                isPlaying = false
                            } else {
                                mp.start()
                                isPlaying = true
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "暫停" else "播放",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (isPlaying) "暫停" else "試聽")
                    }

                    OutlinedButton(
                        onClick = { startRecordingMemo() },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("重錄")
                    }

                    Spacer(Modifier.weight(1f))

                    IconButton(
                        onClick = {
                            releasePlayer()
                            onClearSupplement()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "刪除補充錄音",
                            tint = UrgentRed
                        )
                    }
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
            } else {
                OutlinedButton(
                    onClick = { startRecordingMemo() },
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, MedicalTeal)
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null, tint = MedicalTeal, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("錄製口述補充摘要", color = MedicalTeal, fontWeight = FontWeight.SemiBold)
                }
            }

            recorderError?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = UrgentRed
                )
            }
        }
    }
}

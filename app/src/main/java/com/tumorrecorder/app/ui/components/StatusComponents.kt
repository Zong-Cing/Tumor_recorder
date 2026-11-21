package com.tumorrecorder.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tumorrecorder.app.domain.model.ProcessingStatus
import com.tumorrecorder.app.ui.theme.MedicalTeal
import com.tumorrecorder.app.ui.theme.MedicalTealLight
import com.tumorrecorder.app.ui.theme.SuccessGreen
import com.tumorrecorder.app.ui.theme.SuccessGreenContainer
import com.tumorrecorder.app.ui.theme.UrgentRed
import com.tumorrecorder.app.ui.theme.UrgentRedContainer
import com.tumorrecorder.app.ui.theme.WarningAmber
import com.tumorrecorder.app.ui.theme.WarningAmberContainer

@Composable
fun StatusBadge(status: ProcessingStatus, modifier: Modifier = Modifier) {
    val (bg, fg, loading) = when (status) {
        ProcessingStatus.RECORDING -> Triple(UrgentRedContainer, UrgentRed, true)
        ProcessingStatus.PENDING -> Triple(WarningAmberContainer, WarningAmber, false)
        ProcessingStatus.TRANSCRIBING -> Triple(MedicalTealLight, MedicalTeal, true)
        ProcessingStatus.SENDING_EMAIL -> Triple(MedicalTealLight, MedicalTeal, true)
        ProcessingStatus.COMPLETED -> Triple(SuccessGreenContainer, SuccessGreen, false)
        ProcessingStatus.COMPLETED_NO_EMAIL -> Triple(Color(0xFFE2E8F0), Color(0xFF334155), false)
        ProcessingStatus.FAILED -> Triple(UrgentRedContainer, UrgentRed, false)
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 2.dp,
                color = fg
            )
        }
        Text(
            text = status.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = fg
        )
    }
}

@Composable
fun UrgencyBadge(urgencyLevel: String, modifier: Modifier = Modifier) {
    val normalized = urgencyLevel.ifBlank { "一般" }
    val (bg, fg) = when (normalized) {
        "緊急" -> UrgentRed to Color.White
        "需追蹤" -> WarningAmberContainer to WarningAmber
        else -> Color(0xFFE2E8F0) to Color(0xFF334155)
    }

    Text(
        text = normalized,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = fg,
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

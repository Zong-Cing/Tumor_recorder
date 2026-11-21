package com.tumorrecorder.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tumorrecorder.app.data.local.PromptTemplateEntity
import com.tumorrecorder.app.ui.theme.MedicalTeal
import com.tumorrecorder.app.ui.theme.MedicalTealLight

/**
 * Reusable prompt template radio picker used in:
 *  - Post-call analysis confirmation (InCallActivity & HomeScreen)
 *  - Pending record "開始分析" action
 *  - RecordDetailScreen "重新 AI 分析" action
 */
@Composable
fun PromptTemplatePickerList(
    templates: List<PromptTemplateEntity>,
    selectedTemplateId: Long?,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        templates.forEach { template ->
            val selected = template.id == selectedTemplateId
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(template.id) },
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) MedicalTealLight.copy(alpha = 0.45f) else Color(0xFFF8FAFC)
                ),
                border = BorderStroke(
                    width = if (selected) 1.5.dp else 1.dp,
                    color = if (selected) MedicalTeal else Color(0xFFE2E8F0)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    RadioButton(
                        selected = selected,
                        onClick = { onSelect(template.id) },
                        colors = RadioButtonDefaults.colors(selectedColor = MedicalTeal)
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = template.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (selected) MedicalTeal else MaterialTheme.colorScheme.onSurface
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
                                        .padding(horizontal = 6.dp, vertical = 1.dp)
                                )
                            }
                        }
                        if (template.description.isNotBlank()) {
                            Text(
                                text = template.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

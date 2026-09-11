package com.vibemusic.speechtotext.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibemusic.speechtotext.pipeline.ComponentStatus

@Composable
fun ComponentStatusCard(
    status: ComponentStatus,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "SYSTEM PIPELINE STATUS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Row 1: Language & Model Status (2 equal columns)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusItem(
                    icon = Icons.Default.Translate,
                    label = "Language",
                    value = status.language,
                    isActive = true,
                    modifier = Modifier.weight(1f)
                )

                val languageBase = status.language.substringBefore(" ").trim()
                StatusItem(
                    icon = Icons.Default.CheckCircle,
                    label = "$languageBase Model",
                    value = status.modelStatus,
                    isActive = status.modelStatus == "INSTALLED" || status.modelStatus == "READY",
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Row 2: Microphone & VAD (2 equal columns)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusItem(
                    icon = Icons.Default.Mic,
                    label = "Microphone",
                    value = status.microphone,
                    isActive = status.microphone == "ON",
                    modifier = Modifier.weight(1f)
                )

                StatusItem(
                    icon = Icons.Default.RecordVoiceOver,
                    label = "Voice Activity (VAD)",
                    value = status.vad,
                    isActive = status.vad.contains("Silero"),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Row 3: ASR Engine & Noise Suppressor (2 equal columns)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusItem(
                    icon = Icons.Default.Translate,
                    label = "ASR Engine",
                    value = status.asr,
                    isActive = status.asr.contains("Ready") || status.asr.contains("Vosk"),
                    modifier = Modifier.weight(1f)
                )

                StatusItem(
                    icon = Icons.Default.GraphicEq,
                    label = "Noise Suppression",
                    value = if (status.noiseSuppression.startsWith("Unavailable")) "Passthrough" else status.noiseSuppression,
                    isActive = status.noiseSuppression.startsWith("ON") || status.noiseSuppression.contains("Passthrough"),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Row 4: Offline Privacy Mode
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = "Privacy",
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = status.mode,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatusItem(
    icon: ImageVector,
    label: String,
    value: String,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isActive) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column {
            Text(
                text = label,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isActive) MaterialTheme.colorScheme.onSurface else Color(0xFFE57373),
                maxLines = 1
            )
        }
    }
}

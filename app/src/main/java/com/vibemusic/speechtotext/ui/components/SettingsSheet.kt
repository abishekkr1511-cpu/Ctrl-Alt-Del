package com.vibemusic.speechtotext.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibemusic.speechtotext.pipeline.PipelineSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    currentSettings: PipelineSettings,
    onSaveSettings: (PipelineSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var speechStart by remember { mutableFloatStateOf(currentSettings.speechStartThreshold) }
    var speechEnd by remember { mutableFloatStateOf(currentSettings.speechEndThreshold) }
    var silenceDuration by remember { mutableLongStateOf(currentSettings.silenceDurationMs) }
    var maxUtteranceDuration by remember { mutableLongStateOf(currentSettings.maxUtteranceDurationMs) }
    var noiseSuppressionEnabled by remember { mutableStateOf(currentSettings.isNoiseSuppressionEnabled) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "Pipeline Settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(18.dp))

            // Noise Suppression Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Noise Suppression",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "Enable DeepFilterNet / spectral cleanup",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = noiseSuppressionEnabled,
                    onCheckedChange = { noiseSuppressionEnabled = it }
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // VAD Speech Start Sensitivity
            Text(
                text = "VAD Speech Start Threshold: ${(speechStart * 100).toInt()}%",
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
            Text(
                text = "Higher values prevent triggering on background noises.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = speechStart,
                onValueChange = { speechStart = it },
                valueRange = 0.1f..0.9f
            )

            Spacer(modifier = Modifier.height(14.dp))

            // VAD Speech End Threshold
            Text(
                text = "VAD Speech End Threshold: ${(speechEnd * 100).toInt()}%",
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
            Slider(
                value = speechEnd,
                onValueChange = { speechEnd = it },
                valueRange = 0.1f..0.8f
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Silence Duration
            Text(
                text = "Silence Timeout: ${silenceDuration} ms",
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
            Text(
                text = "Silence delay before finalizing an utterance.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = silenceDuration.toFloat(),
                onValueChange = { silenceDuration = it.toLong() },
                valueRange = 300f..2000f,
                steps = 16
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Max Utterance Duration
            Text(
                text = "Max Utterance Length: ${maxUtteranceDuration / 1000} seconds",
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
            Slider(
                value = (maxUtteranceDuration / 1000).toFloat(),
                onValueChange = { maxUtteranceDuration = (it * 1000).toLong() },
                valueRange = 5f..30f,
                steps = 24
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Model Information
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "OFFLINE MODELS INSTALLED",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "• VAD: Silero VAD (ONNX)", fontSize = 12.sp)
                    Text(text = "• ASR Model: vosk-model-small-en-us (English Offline)", fontSize = 12.sp)
                    Text(text = "• Audio Format: 16kHz Mono PCM 16-bit", fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = {
                    onSaveSettings(
                        currentSettings.copy(
                            speechStartThreshold = speechStart,
                            speechEndThreshold = speechEnd,
                            silenceDurationMs = silenceDuration,
                            maxUtteranceDurationMs = maxUtteranceDuration,
                            isNoiseSuppressionEnabled = noiseSuppressionEnabled
                        )
                    )
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(text = "Apply Settings", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

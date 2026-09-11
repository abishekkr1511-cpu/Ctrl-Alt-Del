package com.disasteralert.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.disasteralert.emergency.EmergencyAlert
import com.disasteralert.ui.EmergencyViewModel
import com.offline.ble.mesh.ble.DiscoveredDevice
import com.offline.ble.mesh.data.model.MessageEntity
import com.offline.ble.mesh.data.model.MessageStatus
import com.vibemusic.speechtotext.language.SpeechLanguage
import com.vibemusic.speechtotext.model.ModelStatus
import com.vibemusic.speechtotext.ui.components.AudioLevelMeter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmergencyMainScreen(viewModel: EmergencyViewModel) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("🚨 Emergency", "📡 Nearby Mesh", "⚙️ Diagnostics")

    // State collections
    val speechUi by viewModel.speechUiState.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val incomingAlert by viewModel.incomingAlert.collectAsState()
    val lastOutgoingAlert by viewModel.lastOutgoingAlert.collectAsState()
    val targetDevice by viewModel.selectedTargetDevice.collectAsState()
    val messageHistory by viewModel.messageHistory.collectAsState()
    val transmissionStatus by viewModel.transmissionStatus.collectAsState()

    // Model Installation Dialog state
    var showInstallDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFFD32F2F),
                            modifier = Modifier.size(32.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "🚨",
                                    fontSize = 16.sp
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Ctrl+Alt+Del",
                                fontWeight = FontWeight.Black,
                                fontSize = 18.sp,
                                letterSpacing = 0.5.sp
                            )
                            Text(
                                text = "Offline Emergency Mesh • Node: ${viewModel.myShortDeviceId}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    // Bluetooth radio status indicator
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (viewModel.isBluetoothEnabled) Color(0xFF1B5E20).copy(alpha = 0.15f)
                        else Color(0xFFB71C1C).copy(alpha = 0.15f),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (viewModel.isBluetoothEnabled) Icons.Default.BluetoothConnected else Icons.Default.BluetoothDisabled,
                                contentDescription = "BT Status",
                                tint = if (viewModel.isBluetoothEnabled) Color(0xFF2E7D32) else Color(0xFFD32F2F),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${discoveredDevices.size} Peers",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (viewModel.isBluetoothEnabled) Color(0xFF2E7D32) else Color(0xFFD32F2F)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = title,
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 13.sp
                            )
                        }
                    )
                }
            }

            when (selectedTab) {
                0 -> EmergencyWorkflowScreen(
                    viewModel = viewModel,
                    onOpenModelInstall = { showInstallDialog = true }
                )
                1 -> NearbyMeshTab(viewModel = viewModel)
                2 -> DiagnosticsTab(
                    viewModel = viewModel,
                    onOpenModelInstall = { showInstallDialog = true }
                )
            }
        }

        // ==========================================
        // PROMINENT RECEIVER EMERGENCY ALERT MODAL
        // ==========================================
        if (incomingAlert != null) {
            val alert = incomingAlert!!
            AlertDialog(
                onDismissRequest = { /* Require explicit acknowledgment */ },
                icon = {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFFD32F2F),
                        modifier = Modifier.size(56.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.NotificationsActive,
                                contentDescription = "Emergency Alert",
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                },
                title = {
                    Text(
                        text = "🚨 EMERGENCY MESSAGE RECEIVED",
                        fontWeight = FontWeight.Black,
                        fontSize = 18.sp,
                        textAlign = TextAlign.Center,
                        color = Color(0xFFD32F2F)
                    )
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "\"${alert.text}\"",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFB71C1C),
                                    lineHeight = 24.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Sender: Node-${alert.senderId.take(8)}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Time: ${alert.formattedTime}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "🔊 Speaking received text at full volume",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFD32F2F)
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "✓ Delivery Acknowledged via BLE Mesh",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF2E7D32)
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { viewModel.dismissIncomingAlert() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = "I HAVE RECEIVED THIS ALERT",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            )
        }
    }
}

/**
 * TAB 1: Core Emergency Alert Workflow
 */
@Composable
fun EmergencyWorkflowScreen(
    viewModel: EmergencyViewModel,
    onOpenModelInstall: () -> Unit
) {
    val speechUi by viewModel.speechUiState.collectAsState()
    val targetDevice by viewModel.selectedTargetDevice.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val lastOutgoingAlert by viewModel.lastOutgoingAlert.collectAsState()
    val transmissionStatus by viewModel.transmissionStatus.collectAsState()

    var isHoldingEmergency by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. LANGUAGE & TARGET SELECTION BAR
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Language Dropdown
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Translate,
                            contentDescription = "Language",
                            tint = Color(0xFFD32F2F),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))

                        var langExpanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(
                                onClick = { langExpanded = true },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = speechUi.selectedLanguage.label,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            DropdownMenu(
                                expanded = langExpanded,
                                onDismissRequest = { langExpanded = false }
                            ) {
                                SpeechLanguage.entries.forEach { lang ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = lang.label,
                                                fontWeight = if (lang == speechUi.selectedLanguage) FontWeight.Bold else FontWeight.Normal
                                            )
                                        },
                                        onClick = {
                                            langExpanded = false
                                            viewModel.selectLanguage(lang)
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Target Device Dropdown
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Radio,
                            contentDescription = "Target",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))

                        var targetExpanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(
                                onClick = { targetExpanded = true },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = if (targetDevice == "BROADCAST") "ALL PEERS" else "Node-${targetDevice.take(6)}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            DropdownMenu(
                                expanded = targetExpanded,
                                onDismissRequest = { targetExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("📢 BROADCAST (All Nearby Devices)", fontWeight = FontWeight.Bold) },
                                    onClick = {
                                        targetExpanded = false
                                        viewModel.selectTargetDevice("BROADCAST")
                                    }
                                )
                                discoveredDevices.forEach { dev ->
                                    val id = if (dev.deviceId.isNotBlank()) dev.deviceId else dev.device.address
                                    DropdownMenuItem(
                                        text = { Text("📱 Node-${id.take(8)}") },
                                        onClick = {
                                            targetExpanded = false
                                            viewModel.selectTargetDevice(id)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. MODEL STATUS ALERT (e.g. if Tamil model needs installation)
        if (speechUi.modelStatus != ModelStatus.READY) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Missing Model",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${speechUi.selectedLanguage.displayName} model not installed",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = "Tap to import model archive (.zip)",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Button(
                            onClick = onOpenModelInstall,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("INSTALL", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // 3. PROMINENT 🚨 EMERGENCY BUTTON (Supports Press & Hold OR Tap to Start)
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (speechUi.isListening) Color(0xFFFFEBEE) else Color(0xFFFBE9E7)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "VICTIM EMERGENCY SYSTEM",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFFB71C1C),
                        letterSpacing = 1.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Big Push-To-Talk / Press-to-Speak Button
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(130.dp)
                            .clip(CircleShape)
                            .background(
                                if (speechUi.isListening) Color(0xFFD32F2F) else Color(0xFFE53935)
                            )
                            .border(
                                width = 4.dp,
                                color = if (speechUi.isListening) Color(0xFFFFCDD2) else Color(0xFFFF8A80),
                                shape = CircleShape
                            )
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = {
                                        isHoldingEmergency = true
                                        viewModel.startListening()
                                        tryAwaitRelease()
                                        isHoldingEmergency = false
                                        viewModel.stopListening()
                                        // Auto dispatch on release if speech detected
                                        val currentText = viewModel.speechUiState.value.finalText
                                            .ifBlank { viewModel.speechUiState.value.partialText }
                                        if (currentText.isNotBlank()) {
                                            viewModel.sendEmergencyAlert()
                                        }
                                    },
                                    onTap = {
                                        if (speechUi.isListening) {
                                            viewModel.stopListening()
                                        } else {
                                            viewModel.startListening()
                                        }
                                    }
                                )
                            }
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = if (speechUi.isListening) Icons.Default.Stop else Icons.Default.Mic,
                                contentDescription = "Emergency Mic",
                                tint = Color.White,
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (speechUi.isListening) "LISTENING..." else "🚨 EMERGENCY",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = if (speechUi.isListening) "● Speaking... Release or tap to send" else "PRESS & HOLD TO SPEAK\nor tap to toggle microphone",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFB71C1C),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Audio level waveform meter
                    AudioLevelMeter(
                        levelNormalized = speechUi.normalizedAudioLevel,
                        levelDb = speechUi.audioLevelDb,
                        speechProbability = speechUi.speechProbability,
                        isListening = speechUi.isListening
                    )
                }
            }
        }

        // 4. SPEECH TRANSCRIPTION DISPLAY
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Recognized Emergency Speech",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            if (speechUi.isListening) {
                                Spacer(modifier = Modifier.width(6.dp))
                                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                            }
                        }

                        if (speechUi.finalText.isNotBlank() || speechUi.partialText.isNotBlank()) {
                            IconButton(
                                onClick = { viewModel.clearTranscript() },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Clear",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Display finalized or live interim speech
                    val activeText = speechUi.finalText.ifBlank { speechUi.partialText }
                    if (activeText.isNotBlank()) {
                        Text(
                            text = "\"$activeText\"",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    } else {
                        Text(
                            text = "Speak into microphone (e.g. \"Help me. I am trapped inside the building\" or \"எனக்கு உதவி தேவை.\")",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Broadcast / Send Action Button
                    Button(
                        onClick = { viewModel.sendEmergencyAlert() },
                        enabled = activeText.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "TRANSMIT EMERGENCY ALERT",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // 5. LAST OUTGOING ALERT TRANSMISSION STATUS
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Transmission Status",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = when (transmissionStatus) {
                                "DISPATCHED" -> "✓ Dispatched to nearby devices (Awaiting delivery confirmation)"
                                "TRANSMITTING" -> "📡 Transmitting over BLE mesh..."
                                "LISTENING" -> "🎤 Capturing speech..."
                                else -> "Ready to transmit"
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = when (transmissionStatus) {
                                "DISPATCHED" -> Color(0xFF2E7D32)
                                "TRANSMITTING" -> Color(0xFF1976D2)
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }

                    if (transmissionStatus == "TRANSMITTING") {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }

        // 6. NEARBY DISCOVERED DEVICES SUMMARY
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Nearby Rescue Devices (${discoveredDevices.size})",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = if (discoveredDevices.isEmpty()) "Scanning..." else "Available",
                            fontSize = 11.sp,
                            color = if (discoveredDevices.isEmpty()) MaterialTheme.colorScheme.error else Color(0xFF2E7D32),
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    if (discoveredDevices.isEmpty()) {
                        Text(
                            text = "No nearby receivers detected yet. Keep Bluetooth enabled; the app continuously scans and broadcasts to in-range phones.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        discoveredDevices.take(3).forEach { device ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        shape = CircleShape,
                                        color = Color(0xFF2E7D32),
                                        modifier = Modifier.size(8.dp)
                                    ) {}
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (device.deviceId.isNotBlank()) "Node-${device.deviceId.take(8)}" else device.device.address,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Text(
                                    text = "${device.rssi} dBm",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * TAB 2: Nearby Mesh Devices and Full Message Log
 */
@Composable
fun NearbyMeshTab(viewModel: EmergencyViewModel) {
    val context = LocalContext.current
    val messageHistory by viewModel.messageHistory.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()

    var manualMessageText by remember { mutableStateOf("") }
    var selectedPeerId by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        // Quick Manual Text Input
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Manual Mesh Texting",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = manualMessageText,
                        onValueChange = { manualMessageText = it },
                        placeholder = { Text("Enter text to send...", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val target = selectedPeerId.ifBlank { "BROADCAST" }
                            viewModel.sendStandardMessage(target, manualMessageText)
                            manualMessageText = ""
                        },
                        enabled = manualMessageText.isNotBlank(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Send")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // History list
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Mesh Message Log (${messageHistory.size})",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            IconButton(onClick = { viewModel.clearHistory() }) {
                Icon(Icons.Default.Delete, contentDescription = "Clear", modifier = Modifier.size(18.dp))
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        if (messageHistory.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No messages sent or received yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messageHistory) { msg ->
                    MessageHistoryCard(msg = msg, myDeviceId = viewModel.myDeviceId)
                }
            }
        }
    }
}

@Composable
fun MessageHistoryCard(msg: MessageEntity, myDeviceId: String) {
    val isMine = msg.senderId == myDeviceId
    val isEmergency = msg.messageType.name == "EMERGENCY"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isEmergency) Color(0xFFFFEBEE)
            else if (isMine) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isEmergency) {
                        Text("🚨 EMERGENCY", fontWeight = FontWeight.Black, fontSize = 11.sp, color = Color(0xFFD32F2F))
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = if (isMine) "Sent to: ${msg.receiverId.take(8)}" else "From: ${msg.senderId.take(8)}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }

                Text(
                    text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(msg.timestamp)),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = msg.content,
                fontSize = 13.sp,
                fontWeight = if (isEmergency) FontWeight.Bold else FontWeight.Normal
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Status: ${msg.status.name}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = when (msg.status) {
                        MessageStatus.DELIVERED -> Color(0xFF2E7D32)
                        MessageStatus.TRANSMITTING -> Color(0xFF1976D2)
                        MessageStatus.FAILED -> Color(0xFFD32F2F)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )

                if (msg.hopCount > 0) {
                    Text(
                        text = "Hops: ${msg.hopCount}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * TAB 3: Diagnostics & Model Settings
 */
@Composable
fun DiagnosticsTab(
    viewModel: EmergencyViewModel,
    onOpenModelInstall: () -> Unit
) {
    val speechUi by viewModel.speechUiState.collectAsState()
    val metrics by viewModel.metrics.collectAsState()
    val logs by viewModel.logs.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Speech Subsystem Card
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("🎤 Offline Speech Subsystem", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Selected Language: ${speechUi.selectedLanguage.label}", fontSize = 12.sp)
                    Text("Model Status: ${speechUi.modelStatus.name}", fontSize = 12.sp)
                    Text("VAD Probability: ${"%.2f".format(speechUi.speechProbability)}", fontSize = 12.sp)
                    Text("Mic Level: ${"%.1f dB".format(speechUi.audioLevelDb)}", fontSize = 12.sp)

                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onOpenModelInstall,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Manage / Import Speech Models (.zip)")
                    }
                }
            }
        }

        // Offline TTS Voice Alert Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE).copy(alpha = 0.6f))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🔊 Text-to-Speech Engine (Full Volume)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFFB71C1C))
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Architecture: On-Device Speech Synthesis", fontSize = 12.sp)
                    Text("Playback: Full Maximum Volume (Pure Speech, No Alarm Sound)", fontSize = 12.sp)
                    Text("Status: ${if (viewModel.ttsIsSpeaking) "Speaking..." else "Ready"}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.testEmergencyVoiceAlert("This is a test message. Speech is playing at full volume.") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                        ) {
                            Text("Test Speech (Full Volume)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = { viewModel.testNormalVoicePlayback() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Test Normal Voice", fontSize = 11.sp)
                        }
                    }

                    if (viewModel.ttsIsSpeaking) {
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = { viewModel.stopVoicePlayback() },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Stop Speech Playback", color = Color(0xFFD32F2F), fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // BLE Mesh Metrics Card
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("📡 BLE Mesh Metrics", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Device ID: ${viewModel.myDeviceId}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text("Packets Delivered: ${metrics.successfulTransmissions}", fontSize = 12.sp)
                    Text("Transmission Failures: ${metrics.failedTransmissions}", fontSize = 12.sp)
                    Text("Duplicates Blocked: ${metrics.duplicatesPreventedCount}", fontSize = 12.sp)
                    Text("Retries Performed: ${metrics.totalRetryCount}", fontSize = 12.sp)
                    Text("Payload Sent: ${metrics.bytesSent} bytes", fontSize = 12.sp)
                    Text("Payload Received: ${metrics.bytesReceived} bytes", fontSize = 12.sp)
                }
            }
        }

        // Action Buttons
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { viewModel.retryFailedMessages() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Retry Queue", fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = { viewModel.clearLogs() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clear Logs", fontSize = 12.sp)
                }
            }
        }

        // Live Log Output
        item {
            Text("Live System Logs (${logs.size})", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }

        items(logs.takeLast(30).reversed()) { log ->
            Text(
                text = "[${log.formattedTime}] [${log.tag}] ${log.message}",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = when (log.level) {
                    "ERROR" -> Color(0xFFD32F2F)
                    "WARN" -> Color(0xFFF57C00)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(vertical = 1.dp)
            )
        }
    }
}

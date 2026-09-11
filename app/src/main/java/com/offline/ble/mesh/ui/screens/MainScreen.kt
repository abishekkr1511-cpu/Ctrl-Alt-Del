package com.offline.ble.mesh.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offline.ble.mesh.ble.DiscoveredDevice
import com.offline.ble.mesh.data.model.MessageEntity
import com.offline.ble.mesh.data.model.MessageStatus
import com.offline.ble.mesh.data.model.MessageType
import com.offline.ble.mesh.manager.LogEntry
import com.offline.ble.mesh.manager.MetricsState
import com.offline.ble.mesh.ui.MainViewModel
import com.offline.ble.mesh.ui.theme.StatusDelivered
import com.offline.ble.mesh.ui.theme.StatusFailed
import com.offline.ble.mesh.ui.theme.StatusPending
import com.offline.ble.mesh.ui.theme.StatusReceived
import com.offline.ble.mesh.ui.theme.StatusTransmitting
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Messages", "Nearby Devices", "Debug Lab")
    val icons = listOf(Icons.Default.Forum, Icons.Default.Devices, Icons.Default.BugReport)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.BluetoothConnected,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Offline BLE Mesh", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, fontSize = 13.sp) },
                        icon = { Icon(icons[index], contentDescription = title, modifier = Modifier.size(20.dp)) }
                    )
                }
            }

            when (selectedTab) {
                0 -> MessagesTab(viewModel)
                1 -> NearbyDevicesTab(viewModel, onDeviceSelected = { selectedTab = 0 })
                2 -> DebugLabTab(viewModel)
            }
        }
    }
}

@Composable
fun MessagesTab(viewModel: MainViewModel) {
    val context = LocalContext.current
    val messageHistory by viewModel.messageHistory.collectAsState()
    val receiverInput by viewModel.receiverInput.collectAsState()
    val messageInput by viewModel.messageInput.collectAsState()
    val selectedType by viewModel.selectedMessageType.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        // Device Info Banner
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("My Device ID", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = viewModel.myDeviceId,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = {
                    viewModel.copyDeviceIdToClipboard(context)
                    Toast.makeText(context, "Device ID copied!", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy ID", modifier = Modifier.size(18.dp))
                }
            }
        }

        // Quick Pick Nearby Peers Chips
        if (discoveredDevices.isNotEmpty()) {
            Text(
                text = "Nearby Compatible Peers (Tap to Select):",
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(discoveredDevices) { peer ->
                    val isSelected = receiverInput == peer.displayName || (peer.deviceId.isNotBlank() && receiverInput == peer.deviceId)
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.selectPeer(peer) },
                        label = {
                            Text(
                                text = "${peer.displayName} (${peer.rssi} dBm)",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    )
                }
            }
        }

        // Messages List
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            if (messageHistory.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No offline messages yet.\nSelect a nearby device and send a message over BLE.",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                items(messageHistory, key = { it.messageId }) { msg ->
                    MessageCard(msg, isSentByMe = msg.senderId == viewModel.myDeviceId)
                }
            }
        }

        // Message Input Area
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                // Receiver Input Field
                OutlinedTextField(
                    value = receiverInput,
                    onValueChange = { viewModel.onReceiverInputChanged(it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Receiver Device ID / Address", fontSize = 12.sp) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Message Type Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    MessageType.values().forEach { type ->
                        FilterChip(
                            selected = selectedType == type,
                            onClick = { viewModel.onMessageTypeSelected(type) },
                            label = { Text(type.name, fontSize = 10.sp) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Content Input & Send
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = messageInput,
                        onValueChange = { viewModel.onMessageInputChanged(it) },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Type offline message...", fontSize = 14.sp) },
                        maxLines = 3
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    IconButton(
                        onClick = { viewModel.sendMessage() },
                        enabled = receiverInput.isNotBlank() && messageInput.isNotBlank(),
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                if (receiverInput.isNotBlank() && messageInput.isNotBlank())
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.surfaceVariant
                            )
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MessageCard(msg: MessageEntity, isSentByMe: Boolean) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val formattedTime = remember(msg.timestamp) { timeFormat.format(Date(msg.timestamp)) }

    val (statusColor, statusText) = when (msg.status) {
        MessageStatus.PENDING -> StatusPending to "PENDING"
        MessageStatus.TRANSMITTING -> StatusTransmitting to "TRANSMITTING"
        MessageStatus.DELIVERED -> StatusDelivered to "DELIVERED"
        MessageStatus.RECEIVED -> StatusReceived to "RECEIVED"
        MessageStatus.FAILED -> StatusFailed to "FAILED"
        MessageStatus.EXPIRED -> Color.Gray to "EXPIRED"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSentByMe)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isSentByMe) "To: ${msg.receiverId.take(12)}..." else "From: ${msg.senderId.take(12)}...",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(statusColor)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = statusText,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = msg.content,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Type: ${msg.messageType} | Hops: ${msg.hopCount} | Retries: ${msg.retryCount}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formattedTime,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun NearbyDevicesTab(viewModel: MainViewModel, onDeviceSelected: () -> Unit) {
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val isAdvertising by viewModel.isAdvertising.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        // Controls Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { viewModel.toggleScanning() },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isScanning) StatusPending else MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(if (isScanning) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (isScanning) "Stop Scan" else "Start Scan", fontSize = 13.sp)
            }

            Button(
                onClick = { viewModel.toggleAdvertising() },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isAdvertising) StatusDelivered else MaterialTheme.colorScheme.secondary
                )
            ) {
                Icon(if (isAdvertising) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (isAdvertising) "Advertising" else "Advertise", fontSize = 13.sp)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Discovered Peers (${discoveredDevices.size}):",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            if (isScanning) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Scanning...", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        if (discoveredDevices.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Bluetooth,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No compatible BLE peers discovered yet.\n\nMake sure the other phone has this app open\nwith Bluetooth and Advertising enabled.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(discoveredDevices) { peer ->
                    DiscoveredDeviceCard(peer, onSelect = {
                        viewModel.selectPeer(peer)
                        onDeviceSelected()
                    })
                }
            }
        }
    }
}

@Composable
fun DiscoveredDeviceCard(peer: DiscoveredDevice, onSelect: () -> Unit) {
    val secAgo = (System.currentTimeMillis() - peer.lastSeenTimestamp) / 1000

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "ID: ${peer.displayName}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Address: ${peer.address}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "RSSI: ${peer.rssi} dBm | Seen: ${secAgo}s ago | State: ${peer.connectionState}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(
                onClick = onSelect,
                shape = RoundedCornerShape(6.dp)
            ) {
                Text("Chat", fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun DebugLabTab(viewModel: MainViewModel) {
    val metrics by viewModel.metrics.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val connState by viewModel.connectionState.collectAsState()
    val connectedPeer by viewModel.connectedDeviceId.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val isAdvertising by viewModel.isAdvertising.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Hardware & State Card
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Hardware & Radio Status", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("• Bluetooth Enabled: ${viewModel.isBluetoothEnabled}", fontSize = 12.sp)
                    Text("• BLE Hardware Supported: ${viewModel.isBleSupported}", fontSize = 12.sp)
                    Text("• Advertising Active: $isAdvertising", fontSize = 12.sp)
                    Text("• Scanning Active: $isScanning", fontSize = 12.sp)
                    Text("• Nearby Mesh Peers: ${discoveredDevices.size}", fontSize = 12.sp)
                    Text("• Current Connection State: $connState", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    Text("• Active Connected Peer: ${connectedPeer ?: "None"}", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        // Evaluation Metrics Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Evaluation Metrics", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("• Successful Transfers: ${metrics.successfulTransmissions}", fontSize = 12.sp)
                        Text("• Failed: ${metrics.failedTransmissions}", fontSize = 12.sp)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("• Duplicates Blocked: ${metrics.duplicatesPreventedCount}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = StatusDelivered)
                        Text("• Retries: ${metrics.totalRetryCount}", fontSize = 12.sp)
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text("• Discovery Latency: ${metrics.discoveryDurationMs} ms", fontSize = 12.sp)
                    Text("• Connection Latency: ${metrics.connectionDurationMs} ms", fontSize = 12.sp)
                    Text("• Chunk Transfer Duration: ${metrics.transmissionDurationMs} ms", fontSize = 12.sp)
                    Text("• ACK Round-Trip Latency: ${metrics.ackDurationMs} ms", fontSize = 12.sp)
                    Text("• Total Delivery Latency: ${metrics.totalDeliveryDurationMs} ms", fontSize = 12.sp, fontWeight = FontWeight.Bold)

                    Spacer(modifier = Modifier.height(4.dp))
                    Text("• Bytes Sent: ${metrics.bytesSent} | Received: ${metrics.bytesReceived}", fontSize = 12.sp)
                    if (metrics.lastBleError != null) {
                        Text("• Last BLE Error: ${metrics.lastBleError}", fontSize = 11.sp, color = StatusFailed)
                    }
                }
            }
        }

        // Test Actions
        item {
            Text("Automated Physical Device Test Actions", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = { viewModel.sendLargeTestMessage() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Test Chunks (2KB)", fontSize = 11.sp)
                }
                Button(
                    onClick = { viewModel.testDuplicateMessage() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Test Duplicate", fontSize = 11.sp)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = { viewModel.triggerRetryQueue() },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Retry Queue", fontSize = 11.sp)
                }
                OutlinedButton(
                    onClick = { viewModel.clearHistory() },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clear DB", fontSize = 11.sp)
                }
            }
        }

        // Live Log Viewer Console
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Live Technical Console Logs (${logs.size}):", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                OutlinedButton(onClick = { viewModel.clearLogs() }) {
                    Text("Clear Logs", fontSize = 10.sp)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1117)),
                shape = RoundedCornerShape(8.dp)
            ) {
                val logListState = rememberLazyListState()
                LaunchedEffect(logs.size) {
                    if (logs.isNotEmpty()) {
                        logListState.animateScrollToItem(logs.size - 1)
                    }
                }

                LazyColumn(
                    state = logListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                ) {
                    items(logs) { log ->
                        val logColor = when (log.level) {
                            "ERROR" -> Color(0xFFFF7B72)
                            "WARN" -> Color(0xFFFFA657)
                            "INFO" -> Color(0xFF7EE787)
                            else -> Color(0xFF79C0FF)
                        }
                        Text(
                            text = "[${log.formattedTime}] [${log.level}] [${log.tag}] ${log.message}",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = logColor,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }
    }
}
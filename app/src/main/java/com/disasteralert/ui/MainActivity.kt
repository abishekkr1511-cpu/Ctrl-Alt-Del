package com.disasteralert.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.disasteralert.ui.components.UnifiedPermissionHandler
import com.disasteralert.ui.screens.EmergencyMainScreen
import com.offline.ble.mesh.ui.theme.OfflineBleMeshTheme

class MainActivity : ComponentActivity() {

    private val viewModel: EmergencyViewModel by viewModels()

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            viewModel.startSubsystems()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            OfflineBleMeshTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    UnifiedPermissionHandler(
                        onPermissionsGranted = {
                            checkAndEnableBluetooth()
                        }
                    ) {
                        EmergencyMainScreen(viewModel = viewModel)
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun checkAndEnableBluetooth() {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        if (adapter != null && !adapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBtLauncher.launch(enableBtIntent)
        } else {
            viewModel.startSubsystems()
        }
    }
}

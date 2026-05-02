package com.mididrum.controller

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.util.UUID

data class PadConfig(val note: Int, val label: String, val color: Color)

class MainActivity : ComponentActivity() {

    companion object {
        val MIDI_SERVICE_UUID: UUID = UUID.fromString("03b80e5a-ede8-4b33-a751-6ce34ec4c700")
        val MIDI_CHAR_UUID: UUID = UUID.fromString("7772e5db-3868-4112-a1a9-f2669d106bf3")
        const val TARGET_DEVICE_NAME = "ESP32_Drum"
    }

    private val pads = listOf(
        PadConfig(36, "Kick", Color(0xFFFF073A)),
        PadConfig(38, "Snare", Color(0xFF00F0FF)),
        PadConfig(42, "HH Cls", Color(0xFFFFE600)),
        PadConfig(46, "HH Opn", Color(0xFFFF6B00)),
        PadConfig(41, "Tom L", Color(0xFFFF00FF)),
        PadConfig(43, "Tom M", Color(0xFFBB00FF)),
        PadConfig(45, "Tom H", Color(0xFF0080FF)),
        PadConfig(49, "Crash", Color(0xFF00FF66)),
        PadConfig(51, "Ride", Color(0xFF00F0FF)),
        PadConfig(39, "Clap", Color(0xFFFF00FF)),
        PadConfig(37, "Rimshot", Color(0xFFFF6B00)),
        PadConfig(56, "Cowbell", Color(0xFFFFE600)),
        PadConfig(44, "Pedal", Color(0xFF0080FF)),
        PadConfig(47, "Tom F", Color(0xFFBB00FF)),
        PadConfig(52, "China", Color(0xFFFF073A)),
        PadConfig(55, "Splash", Color(0xFF00FF66)),
    )

    private var gatt: BluetoothGatt? = null
    private var midiChar: BluetoothGattCharacteristic? = null
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _status = mutableStateOf("disconnected")
    private val _deviceName = mutableStateOf("")
    private val _activePads = mutableStateMapOf<Int, Boolean>()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) startScan()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                DrumControllerScreen()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ioScope.cancel()
        gatt?.close()
    }

    @Composable
    fun DrumControllerScreen() {
        val status by _status
        val deviceName by _deviceName

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0A0F))
                .padding(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("MIDI", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                    Text("DRUM", color = Color(0xFF00F0FF), fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                }
                Button(
                    onClick = { if (status == "connected") disconnect() else requestPermissionsAndScan() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (status == "connected") Color(0xFF00FF66) else Color(0xFF00F0FF)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        when (status) {
                            "connected" -> "DISCONNECT"
                            "scanning" -> "SCANNING..."
                            else -> "CONNECT"
                        },
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                if (status == "connected") "Connected: $deviceName" else "Not connected",
                color = if (status == "connected") Color(0xFF00FF66) else Color.Gray,
                fontSize = 10.sp
            )
            Spacer(Modifier.height(12.dp))

            // 4x4 Grid
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (row in 0 until 4) {
                    Row(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (col in 0 until 4) {
                            val pad = pads[row * 4 + col]
                            val isActive = _activePads[pad.note] == true
                            DrumPadButton(
                                pad = pad,
                                isActive = isActive,
                                enabled = status == "connected",
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                onPress = {
                                    _activePads[pad.note] = true
                                    sendNoteOn(pad.note)
                                },
                                onRelease = {
                                    _activePads[pad.note] = false
                                    sendNoteOff(pad.note)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun DrumPadButton(
        pad: PadConfig,
        isActive: Boolean,
        enabled: Boolean,
        modifier: Modifier,
        onPress: () -> Unit,
        onRelease: () -> Unit
    ) {
        val bgColor by animateColorAsState(
            if (isActive) pad.color.copy(alpha = 0.4f) else Color(0xFF1A1A2E),
            label = "padBg"
        )
        val borderColor = if (isActive) pad.color else pad.color.copy(alpha = 0.3f)

        Box(
            modifier = modifier
                .clip(RoundedCornerShape(12.dp))
                .background(bgColor)
                .then(
                    if (isActive) Modifier.shadow(8.dp, RoundedCornerShape(12.dp), ambientColor = pad.color)
                    else Modifier
                )
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        onPress = {
                            onPress()
                            tryAwaitRelease()
                            onRelease()
                        }
                    )
                }
                .padding(2.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(pad.label, color = pad.color, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(2.dp))
                Text("${pad.note}", color = pad.color.copy(alpha = 0.5f), fontSize = 9.sp)
            }
        }
    }

    // ─── BLE ────────────────────────────────────────────
    private fun requestPermissionsAndScan() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION)

        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) startScan() else permLauncher.launch(needed.toTypedArray())
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        _status.value = "scanning"
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val scanner = adapter.bluetoothLeScanner ?: return

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(MIDI_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: return
                if (name == TARGET_DEVICE_NAME) {
                    scanner.stopScan(this)
                    connectToDevice(result.device)
                }
            }
        }
        scanner.startScan(listOf(filter), settings, callback)

        // Timeout after 10s
        ioScope.launch {
            delay(10000)
            try { scanner.stopScan(callback) } catch (_: Exception) {}
            if (_status.value == "scanning") _status.value = "disconnected"
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        _status.value = "connecting"
        gatt = device.connectGatt(this, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.discoverServices()
                } else {
                    _status.value = "disconnected"
                    _deviceName.value = ""
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val service = g.getService(MIDI_SERVICE_UUID) ?: return
                midiChar = service.getCharacteristic(MIDI_CHAR_UUID)
                if (midiChar != null) {
                    _status.value = "connected"
                    _deviceName.value = device.name ?: "ESP32"
                }
            }
        }, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    private fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        midiChar = null
        _status.value = "disconnected"
        _deviceName.value = ""
    }

    private fun sendMidiPacket(status: Byte, note: Byte, velocity: Byte) {
        val char = midiChar ?: return
        val g = gatt ?: return
        val packet = byteArrayOf(0x80.toByte(), 0x80.toByte(), status, note, velocity)
        ioScope.launch {
            char.value = packet
            @SuppressLint("MissingPermission")
            g.writeCharacteristic(char)
        }
    }

    private fun sendNoteOn(note: Int) = sendMidiPacket(0x90.toByte(), note.toByte(), 127.toByte())
    private fun sendNoteOff(note: Int) = sendMidiPacket(0x80.toByte(), note.toByte(), 0.toByte())
}

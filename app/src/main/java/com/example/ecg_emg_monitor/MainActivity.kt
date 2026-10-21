package com.example.ecg_emg_monitor

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.IOException
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvADC: TextView
    private lateinit var tvVoltage: TextView
    private lateinit var tvSampleRate: TextView
    private lateinit var tvSampleCount: TextView
    private lateinit var waveformView: WaveformView
    private lateinit var infoLabel: TextView

    private lateinit var spinnerDevices: Spinner
    private lateinit var btnRefresh: Button
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnScale: Button

    private var bluetoothAdapter: BluetoothAdapter? = null
    @Volatile private var bluetoothSocket: BluetoothSocket? = null
    @Volatile private var sessionId = 0
    private var connected = false
    private var connecting = false
    private var pairedDevices = listOf<BluetoothDevice>()

    private val sppUUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val uiHandler = Handler(Looper.getMainLooper())
    private val queueLock = Any()
    private val pendingSamples = ArrayDeque<Int>()
    private val receivedCount = AtomicLong(0)
    private val droppedForDisplay = AtomicLong(0)
    private val ratePoints = ArrayDeque<Pair<Long, Long>>()
    private var autoY = false
    private var mode = "ECG"

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) refreshDevices() else setStatus("Bluetooth permission denied")
    }

    // UI refresh ~25 FPS. Bluetooth receiving runs on a separate thread.
    private val displayTick = object : Runnable {
        override fun run() {
            renderPendingSamples()
            uiHandler.postDelayed(this, 40L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(
            findViewById(android.R.id.content)
        ) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        tvStatus = findViewById(R.id.tvBluetoothStatus)
        tvADC = findViewById(R.id.tvADCValue)
        tvVoltage = findViewById(R.id.tvVoltage)
        tvSampleRate = findViewById(R.id.tvSampleRate)
        tvSampleCount = findViewById(R.id.tvSampleCount)
        spinnerDevices = findViewById(R.id.spinnerDevices)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)

        buildWaveformPanel()
        findViewById<RadioGroup>(R.id.radioSignalMode).setOnCheckedChangeListener { _, checked ->
            mode = if (checked == R.id.radioEMG) "EMG" else "ECG"
            waveformView.setMode(mode)
            updateChartLabel()
        }
        // The prior XML contains a Demo button; hide it rather than leave a dead control.
        findViewById<Button>(R.id.btnDemo).visibility = View.GONE

        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter

        btnRefresh.setOnClickListener { checkPermissionAndRefresh() }
        btnConnect.setOnClickListener { connectSelectedDevice() }
        btnDisconnect.setOnClickListener { disconnectBluetooth() }
        btnScale.setOnClickListener {
            autoY = !autoY
            waveformView.setAutoScale(autoY)
            btnScale.text = if (autoY) "AUTO Y ✓" else "AUTO Y"
        }

        tvSampleRate.text = "Received Rate (5s avg): -- Hz"
        tvSampleCount.text = "Received Samples: 0"
        updateButtons()
        uiHandler.post(displayTick)
        checkPermissionAndRefresh()
    }

    private fun dp(x: Int): Int = (x * resources.displayMetrics.density).toInt()

    private fun buildWaveformPanel() {
        val container = findViewById<FrameLayout>(R.id.waveformContainer)
        container.removeAllViews()

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(panel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        infoLabel = TextView(this).apply {
            textSize = 11f
            setPadding(dp(6), dp(2), dp(4), dp(2))
        }
        panel.addView(infoLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        waveformView = WaveformView(this)
        panel.addView(waveformView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        panel.addView(controls, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        btnScale = Button(this).apply { text = "AUTO Y"; textSize = 11f }
        controls.addView(btnScale, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
        ))
        updateChartLabel()
    }

    private fun updateChartLabel() {
        if (::infoLabel.isInitialized) {
            infoLabel.text = "$mode display | A0 | 5 s @ nominal 250 Hz | ADC input: 0–5 V"
        }
    }

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

    private fun checkPermissionAndRefresh() {
        if (!hasBluetoothPermission()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        refreshDevices()
    }

    @SuppressLint("MissingPermission")
    private fun refreshDevices() {
        if (!hasBluetoothPermission()) return
        val adapter = bluetoothAdapter
        if (adapter == null) { setStatus("Bluetooth not supported"); return }
        if (!adapter.isEnabled) { setStatus("Please enable Bluetooth"); return }
        pairedDevices = adapter.bondedDevices.sortedBy { it.name ?: "" }
        val names = pairedDevices.map { "${it.name ?: "Unknown"} (${it.address})" }
        val selection = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            names.ifEmpty { listOf("No paired Bluetooth devices") }
        )
        selection.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerDevices.adapter = selection
        if (!connected && !connecting)
            setStatus(if (pairedDevices.isEmpty()) "No paired devices" else "Select HC-05 and Connect")
    }

    @SuppressLint("MissingPermission")
    private fun connectSelectedDevice() {
        if (!hasBluetoothPermission()) { checkPermissionAndRefresh(); return }
        if (connecting || connected) { toast("Already connecting/connected"); return }
        val index = spinnerDevices.selectedItemPosition
        if (index !in pairedDevices.indices) { toast("Select a paired device"); return }
        val device = pairedDevices[index]
        val mySession = ++sessionId
        connecting = true
        receivedCount.set(0)
        droppedForDisplay.set(0)
        synchronized(queueLock) { pendingSamples.clear() }
        ratePoints.clear()
        waveformView.clearWaveform()
        tvSampleRate.text = "Received Rate (5s avg): -- Hz"
        tvSampleCount.text = "Received Samples: 0"
        setStatus("Connecting to ${device.name}...")
        updateButtons()

        Thread {
            var socket: BluetoothSocket? = null
            var problem: String? = null
            try {
                val connectedSocket = device.createRfcommSocketToServiceRecord(sppUUID)
                socket = connectedSocket
                bluetoothSocket = connectedSocket
                connectedSocket.connect()
                runOnUiThread {
                    if (mySession == sessionId) {
                        connected = true
                        connecting = false
                        setStatus("Connected: ${device.name}")
                        updateButtons()
                    }
                }
                receiveData(connectedSocket, mySession)
            } catch (e: Exception) {
                problem = e.message ?: e.javaClass.simpleName
            } finally {
                try { socket?.close() } catch (_: IOException) { }
                if (bluetoothSocket === socket) bluetoothSocket = null
                val reason = problem
                runOnUiThread {
                    if (mySession == sessionId) {
                        connected = false
                        connecting = false
                        setStatus(if (reason == null) "Disconnected" else "Connection ended: $reason")
                        updateButtons()
                    }
                }
            }
        }.start()
    }

    private fun receiveData(socket: BluetoothSocket, mySession: Int) {
        val input = socket.inputStream
        val bytes = ByteArray(512)
        var state = 0
        var highByte = 0
        while (mySession == sessionId && !Thread.currentThread().isInterrupted) {
            val n = input.read(bytes)
            if (n < 0) break
            for (i in 0 until n) {
                if (mySession != sessionId) return
                val b = bytes[i].toInt() and 0xFF
                when (state) {
                    0 -> if (b == 0xAA) state = 1
                    1 -> {
                        if (b <= 3) { highByte = b; state = 2 }
                        else state = if (b == 0xAA) 1 else 0
                    }
                    2 -> {
                        onSampleReceived((highByte shl 8) or b)
                        state = 0
                    }
                }
            }
        }
    }

    private fun onSampleReceived(adc: Int) {
        receivedCount.incrementAndGet()
        synchronized(queueLock) {
            // Bound memory use if the UI falls behind the Bluetooth receiver.
            if (pendingSamples.size >= 4000) {
                pendingSamples.removeFirst()
                droppedForDisplay.incrementAndGet()
            }
            pendingSamples.addLast(adc)
        }

    }

    private fun renderPendingSamples() {
        val batch = ArrayList<Int>()
        synchronized(queueLock) {
            while (pendingSamples.isNotEmpty()) batch.add(pendingSamples.removeFirst())
        }
        if (batch.isNotEmpty()) {
            waveformView.appendSamples(batch)
            val latest = batch.last()
            tvADC.text = "ADC Value: $latest"
            tvVoltage.text = String.format(Locale.US, "Voltage: %.3f V", latest * 5.0 / 1023.0)
        }
        val now = SystemClock.elapsedRealtime()
        val total = receivedCount.get()
        ratePoints.addLast(now to total)
        while (ratePoints.size > 1 && now - ratePoints.peekFirst().first > 5000L)
            ratePoints.removeFirst()
        if (ratePoints.size > 1) {
            val first = ratePoints.peekFirst()
            val elapsed = now - first.first
            if (elapsed >= 1000L) {
                val hz = (total - first.second) * 1000.0 / elapsed
                tvSampleRate.text = String.format(Locale.US, "Received Rate (5s avg): %.1f Hz", hz)
            }
        }
        tvSampleCount.text = "Received Samples: $total"
    }

    private fun disconnectBluetooth() {
        ++sessionId  // Ignore callbacks from the old connection.
        try { bluetoothSocket?.close() } catch (_: IOException) { }
        bluetoothSocket = null
        connecting = false
        connected = false
        setStatus("Disconnected")
        updateButtons()
    }

    private fun updateButtons() {
        btnConnect.isEnabled = !connected && !connecting
        btnDisconnect.isEnabled = connected || connecting

    }

    private fun setStatus(message: String) {
        tvStatus.text = "Status: $message"
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(displayTick)
        ++sessionId
        try { bluetoothSocket?.close() } catch (_: IOException) { }
        super.onDestroy()
    }
}

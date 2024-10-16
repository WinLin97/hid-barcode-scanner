package dev.fabik.bluetoothhid.bt

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.fabik.bluetoothhid.R
import dev.fabik.bluetoothhid.utils.PreferenceStore
import dev.fabik.bluetoothhid.utils.getPreference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import android.util.Base64
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

typealias Listener = (BluetoothDevice?, Int) -> Unit

@SuppressLint("MissingPermission")
class BluetoothController(var context: Context) {
    companion object {
        private const val TAG = "BluetoothController"
        private const val RFCTAG = "BluetoothController.SPP_RFCOMM"
        private val RFCOMM_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private var connectionMode: Int = 0
    }

    private val keyTranslator: KeyTranslator = KeyTranslator(context)

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private val bluetoothAdapter: BluetoothAdapter? by lazy { bluetoothManager.adapter }

    private var deviceListener: MutableList<Listener> = mutableListOf()

    @Volatile
    private var hidDevice: BluetoothHidDevice? = null
    private var hostDevice: BluetoothDevice? by mutableStateOf(null)

    private var latch: CountDownLatch = CountDownLatch(0)

    private var autoConnectEnabled: Boolean = false

    private var rfcSocket: BluetoothSocket? = null
    private var serverSocket: BluetoothServerSocket? = null  // Serwer RFCOMM

    var keyboardSender: KeyboardSender? = null
        private set

    var isSending: Boolean by mutableStateOf(false)
        private set

    var isCapsLockOn: Boolean by mutableStateOf(false)
        private set

    private val serviceListener = object : BluetoothProfile.ServiceListener {

        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            CoroutineScope(Dispatchers.IO).launch {

                Log.d(TAG, "onServiceConnected")

                connectionMode = context.getPreference(PreferenceStore.CONNECTION_MODE).first()
                Log.d(TAG, "ConnectionMode: $connectionMode")
            }

                if (connectionMode == 1) {
                    // RFCOMM MODE
                    startSPPServer()
                }

                hostDevice = null
                hidDevice = proxy as? BluetoothHidDevice

                hidDevice?.registerApp(
                    Descriptor.SDP_RECORD,
                    null,
                    Descriptor.QOS_OUT,
                    Executors.newCachedThreadPool(),
                    hidDeviceCallback
                )

                Toast.makeText(
                    context,
                    context.getString(R.string.bt_service_connected),
                    Toast.LENGTH_SHORT
                ).show()

                latch.countDown()

        }

        override fun onServiceDisconnected(profile: Int) {
            Log.d(TAG, "onServiceDisconnected")

            hidDevice = null
            hostDevice = null

            Toast.makeText(
                context,
                context.getString(R.string.bt_service_disconnected),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private val hidDeviceCallback = object : BluetoothHidDevice.Callback() {
        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            super.onConnectionStateChanged(device, state)

            if (state == BluetoothProfile.STATE_CONNECTED) {
                hostDevice = device
                hidDevice?.let {
                    keyboardSender = KeyboardSender(keyTranslator, it, device)
                }
            } else {
                hostDevice = null
                keyboardSender = null
            }

            deviceListener.forEach { it.invoke(device, state) }
        }

        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            super.onAppStatusChanged(pluggedDevice, registered)

            if (registered && autoConnectEnabled) {
                if (pluggedDevice != null) {
                    Log.d(TAG, "onAppStatusChanged: connecting with $pluggedDevice")
                    hidDevice?.connect(pluggedDevice)
                } else {
                    hidDevice?.getDevicesMatchingConnectionStates(
                        intArrayOf(
                            BluetoothProfile.STATE_CONNECTED,
                            BluetoothProfile.STATE_CONNECTING,
                            BluetoothProfile.STATE_DISCONNECTED,
                            BluetoothProfile.STATE_DISCONNECTING
                        )
                    )?.firstOrNull()?.let {
                        Log.d(TAG, "onAppStatusChanged: connecting with $it")
                        hidDevice?.connect(it)
                    }
                }
            }
        }

        override fun onInterruptData(device: BluetoothDevice?, reportId: Byte, data: ByteArray?) {
            super.onInterruptData(device, reportId, data)

            data?.getOrNull(0)?.toInt()?.let {
                isCapsLockOn = it and 0x02 != 0
                // isNumLockOn = it and 0x01 != 0
                // isScrollLockOn = it and 0x04 != 0
            }

            Log.d(TAG, "onInterruptData: $device, $reportId, ${data?.contentToString()}")
        }

    }

    val currentDevice: BluetoothDevice?
        get() = hostDevice

    val bluetoothEnabled: Boolean
        get() = bluetoothAdapter?.isEnabled ?: false

    val pairedDevices: Set<BluetoothDevice>
        get() = bluetoothAdapter?.bondedDevices ?: emptySet()

    val isScanning: Boolean
        get() = bluetoothAdapter?.isDiscovering ?: false

    fun registerListener(listener: Listener): Listener {
        deviceListener.add(listener)
        return listener
    }

    fun unregisterListener(listener: Listener) = deviceListener.remove(listener)

    suspend fun register(): Boolean =
        register(context.getPreference(PreferenceStore.AUTO_CONNECT).first())

    private fun register(autoConnect: Boolean): Boolean {
        autoConnectEnabled = autoConnect

        if (hidDevice != null) {
            unregister()
        }

        return bluetoothAdapter?.getProfileProxy(
            context,
            serviceListener,
            BluetoothProfile.HID_DEVICE
        ) ?: false
    }

    private fun startSPPServer() {
        val bluetoothAdapter = bluetoothManager.adapter

        // Open BluetoothServerSocket for RFCOMM connections (SPP)
        serverSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord("Barcode Scanner", RFCOMM_UUID)
        Log.d(RFCTAG, "Server Started! Waiting for connections...")

        // Uruchom serwer w tle w korutynie
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Akceptuj połączenie - blokuje do czasu nadejścia połączenia
                rfcSocket = serverSocket?.accept()
                Log.d(RFCTAG, "Server: Client connected via RFCOMM")

                // Sprawdź, czy rfcSocket nie jest null
                rfcSocket?.let { socket ->
                    withContext(Dispatchers.IO) {
                        manageSPPConnection(socket)
                    }
                } ?: Log.e(RFCTAG, "Socket: Bluetooth socket is null")
            } catch (e: IOException) {
                Log.e(RFCTAG, "Server: Error starting server", e)
            }
        }
    }

    private fun manageSPPConnection(socket: BluetoothSocket) {
        val inputStream = socket.inputStream
        val outputStream = socket.outputStream

        Log.e(RFCTAG, "Socket: Opened!")

        try {
            // Przykład wysłania wiadomości do klienta
            val message = "Hello from Android SPP Server implemented in HID Barcode Scanner".toByteArray()
            outputStream.write(message)

            // Czytaj dane od klienta
            val buffer = ByteArray(1024)
            var bytes: Int
            while (true) {
                bytes = inputStream.read(buffer)
                val readMessage = String(buffer, 0, bytes)
                Log.d(RFCTAG, "Received: $readMessage")
            }
        } catch (e: IOException) {
            Log.e(RFCTAG, "Socket: Error during communication", e)
        } finally {
            socket.close()
            Log.e(RFCTAG, "Socket: Closed! - > Client disconnected?")
        }
    }

    fun unregister() {
        disconnect()

        hidDevice?.unregisterApp()
        bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)

        hidDevice = null
        hostDevice = null

        // Notify listeners that proxy is disconnected.
        deviceListener.forEach { it.invoke(null, -1) }
    }

    fun scanDevices() {
        if (bluetoothAdapter?.isDiscovering == true) {
            bluetoothAdapter?.cancelDiscovery()
        }
        bluetoothAdapter?.startDiscovery()
    }

    fun cancelScan() {
        bluetoothAdapter?.cancelDiscovery()
    }

    suspend fun connect(device: BluetoothDevice) {
        // Cancel discovery because it otherwise slows down the connection.
        bluetoothAdapter?.cancelDiscovery()

        val success = hidDevice?.connect(device) ?: run {
            // Initialize latch to wait for service to be connected.
            latch = CountDownLatch(1)

            // Try to register proxy.
            return@run if (!register()) {
                (context as? Activity)?.runOnUiThread {
                    Toast.makeText(
                        context,
                        context.getString(R.string.bt_proxy_error),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                false
            } else {
                (context as? Activity)?.runOnUiThread {
                    Toast.makeText(
                        context,
                        context.getString(R.string.proxy_waiting),
                        Toast.LENGTH_SHORT
                    ).show()
                }

                latch.await(5000, TimeUnit.MILLISECONDS)
                hidDevice?.connect(device) ?: false
            }
        }

        if (!success) {
            (context as? Activity)?.runOnUiThread {
                Toast.makeText(
                    context,
                    context.getString(R.string.error_connecting_to_device),
                    Toast.LENGTH_SHORT
                ).show()
            }

            Intent(
                context,
                BluetoothService::class.java
            ).apply {
                action = BluetoothService.ACTION_REGISTER
            }.also {
                (context as? Activity)?.runOnUiThread {
                    context.startForegroundService(it)
                }
            }
        }
    }

    fun disconnect(): Boolean {
        if (isSending) {
            return false
        }

        return hostDevice?.let {
            hidDevice?.disconnect(it)
        } ?: true
    }

    suspend fun sendString(string: String) = with(context) {
        if (isSending) {
            return@with
        }
        isSending = true

        val sendDelay = getPreference(PreferenceStore.SEND_DELAY).first()
        val extraKeys = getPreference(PreferenceStore.EXTRA_KEYS).first()
        val layout = getPreference(PreferenceStore.KEYBOARD_LAYOUT).first()
        val template = getPreference(PreferenceStore.TEMPLATE_TEXT).first()
        val expand = getPreference(PreferenceStore.EXPAND_CODE).first()

        if (connectionMode != 1) {
            keyboardSender?.sendString(
                string,
                sendDelay.toLong(),
                extraKeys,
                when (layout) {
                    1 -> "de"
                    2 -> "fr"
                    3 -> "en"
                    4 -> "es"
                    5 -> "it"
                    6 -> "tr"
                    7 -> "pl"
                    else -> "us"
                },
                template,
                expand
            )
        } else {
            sendDataByRFCOMM(string, template)
        }
        isSending = false
    }

    private fun sendDataByRFCOMM(data: String, template: String) {

        // Define the current date and time
        val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

        // Define the placeholders and their replacements
        val placeholders = mapOf(
            "{SPACE}" to " ",
            "{TAB}" to "\t",
            "{CR}" to "\r",
            "{LF}" to "\n",
            "{ENTER}" to "\r\n",
            "{DATE}" to currentDate,
            "{TIME}" to currentTime
        )

        // Check if the template contains at least one of {CODE}, {CODE_B64}, or {CODE_HEX}
        val codeRegex = Regex("\\{CODE(_B64|_HEX)?\\}")
        if (!codeRegex.containsMatchIn(template)) {
            Log.e(TAG, "Template must contain {CODE}, {CODE_B64}, or {CODE_HEX}")
            return
        }

        // Start processing the template
        var processedTemplate = template

        // Replace {CODE_B64} if present
        if (processedTemplate.contains("{CODE_B64}")) {
            val encodedB64 = Base64.encodeToString(data.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            processedTemplate = processedTemplate.replace("{CODE_B64}", encodedB64)
        }

        // Replace {CODE_HEX} if present
        if (processedTemplate.contains("{CODE_HEX}")) {
            val encodedHex = data.toByteArray(Charsets.UTF_8).joinToString("") { String.format("%02X", it) }
            processedTemplate = processedTemplate.replace("{CODE_HEX}", encodedHex)
        }

        // Replace {CODE} if present
        if (processedTemplate.contains("{CODE}")) {
            processedTemplate = processedTemplate.replace("{CODE}", data)
        }

        // Replace other placeholders
        for ((placeholder, replacement) in placeholders) {
            processedTemplate = processedTemplate.replace(placeholder, replacement)
        }

        // Convert the final message to UTF-8 byte array
        val messageBytes = processedTemplate.toByteArray(Charsets.UTF_8)

        // Send data via RFCOMM
        rfcSocket?.let { socket ->
            try {
                Log.e(RFCTAG, "Sent: $processedTemplate")
                socket.outputStream.write(messageBytes)
            } catch (e: Exception) {
                Log.e(RFCTAG, "Socket: Error sending data", e)
            }
        } ?: Log.e(RFCTAG, "Socket: socket is null")
    }
}

fun BluetoothDevice.removeBond() {
    javaClass.getMethod("removeBond").invoke(this)
}
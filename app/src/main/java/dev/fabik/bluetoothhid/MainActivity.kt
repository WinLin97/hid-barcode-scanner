package dev.fabik.bluetoothhid

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import dev.fabik.bluetoothhid.bt.BluetoothController
import dev.fabik.bluetoothhid.bt.BluetoothService
import dev.fabik.bluetoothhid.ui.NavGraph
import dev.fabik.bluetoothhid.ui.RequiresBluetoothPermission
import dev.fabik.bluetoothhid.ui.theme.BluetoothHIDTheme
import dev.fabik.bluetoothhid.utils.*

class MainActivity : ComponentActivity() {

    private var bluetoothService: BluetoothService.LocalBinder? = null
    private var bluetoothController: BluetoothController? by mutableStateOf(null)

    private var serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = (service as BluetoothService.LocalBinder)
            bluetoothService = binder
            bluetoothController = binder.getController()

            Toast.makeText(
                this@MainActivity,
                getText(R.string.bt_service_connected),
                Toast.LENGTH_SHORT
            ).show()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bluetoothService = null
            bluetoothController = null

            Toast.makeText(
                this@MainActivity,
                getText(R.string.bt_service_disconnected),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BluetoothHIDTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RequiresBluetoothPermission {
                        LaunchedEffect(Unit) {
                            // Start and bind bluetooth service
                            Intent(this@MainActivity, BluetoothService::class.java).let {
                                startForegroundService(it)
                                bindService(it, serviceConnection, BIND_AUTO_CREATE)
                            }
                        }

                        bluetoothController?.let {
                            NavGraph(it)
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        // Stop bluetooth service when activity is destroyed
        stopService(Intent(this, BluetoothService::class.java))

        super.onDestroy()
    }

}

package com.bose.control

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.*
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit


val uuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
val SAVED_DEVICE_KEY = "com.bose.control.SAVED_DEVICE_KEY"
val MY_PERMISSIONS_REQUEST_LOCATION = 99

class MainActivity : AppCompatActivity() {

    enum class ConnectionStatus {
        CONNECTED,
        CONNECTING,
        DISCONNECTED
    }

    class State {
        var connection: ConnectionStatus = ConnectionStatus.DISCONNECTED
        var version: String = "UNKNOWN"
        var ncLevel: Protocol.NoiseLevels = Protocol.NoiseLevels.OFF
        var name: String = "UNKNOWN"
        var auto_off_period: Int = -1
        var btnMode: Protocol.ButtonModes = Protocol.ButtonModes.ERROR
        var batteryLevel: Int = -1
    }

    val state = State()
    var textview: TextView? = null
    var connecting: ProgressBar? = null
    var socket: BTSocket? = null

    fun checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) { // Should we show an explanation?
            if (ActivityCompat.shouldShowRequestPermissionRationale(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            ) {
                //Prompt the user once explanation has been shown
                ActivityCompat.requestPermissions(
                    this@MainActivity,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    MY_PERMISSIONS_REQUEST_LOCATION
                )
            }
        } else { // No explanation needed, we can request the permission.
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                MY_PERMISSIONS_REQUEST_LOCATION
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String?>, grantResults: IntArray
    ) {
        when (requestCode) {
            MY_PERMISSIONS_REQUEST_LOCATION -> {
                val request_completed =
                    (grantResults.size > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                if (request_completed) {
                    val perm = ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    )
                    if (perm == PackageManager.PERMISSION_GRANTED) { //Request location updates:
                        Log.i("Location", "permission granted")
                    }
                }
                return
            }
        }
    }

    fun connect(device: BluetoothDevice) {



    }

    override fun onDestroy() {
        socket?.let {
            it.stop = true
        }
        super.onDestroy()
    }

    fun findDeviceByName(bluetoothAdapter: BluetoothAdapter, name: String): BluetoothDevice {
        return bluetoothAdapter.bondedDevices.filter { it.name.equals(name) }.first()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)
        checkLocationPermission()

        val button = findViewById<Button>(R.id.button)
        connecting = findViewById(R.id.progressBar)
        textview = findViewById(R.id.text)

        val uiToQueue: Map<View, Protocol.Messages> = mapOf(
            Pair(findViewById(R.id.radioNCHigh), Protocol.Messages.NOISE_LEVEL_HIGH),
            Pair(findViewById(R.id.radioNCLow), Protocol.Messages.NOISE_LEVEL_LOW),
            Pair(findViewById(R.id.radioNCOff), Protocol.Messages.NOISE_LEVEL_OFF),
            Pair(findViewById(R.id.radioOFFNever), Protocol.Messages.AUTO_OFF_NEVER),
            Pair(findViewById(R.id.radioOFF20), Protocol.Messages.AUTO_OFF_20),
            Pair(findViewById(R.id.radioOFF60), Protocol.Messages.AUTO_OFF_60),
            Pair(findViewById(R.id.radioOFF180), Protocol.Messages.AUTO_OFF_180),
            Pair(findViewById(R.id.btnAlexa), Protocol.Messages.BTN_MODE_ALEXA),
            Pair(findViewById(R.id.btnNC), Protocol.Messages.BTN_MODE_NC)
        )

        val outgoingMsg: BlockingQueue<ShortArray> = LinkedBlockingQueue()
        val incomingEvents: BlockingQueue<BTSocket.Events> = LinkedBlockingQueue()
        uiToQueue.forEach { e ->
            run {
                e.key.setOnClickListener { outgoingMsg.put(e.value.msg) }
            }
        }

        socket = BTSocket(incomingEvents, outgoingMsg)
        parseEvents(incomingEvents)

        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        bluetoothAdapter.cancelDiscovery()

        val entries: Array<String> = bluetoothAdapter.bondedDevices.map { it.name }.toTypedArray()
        val sharedPref = getPreferences(Context.MODE_PRIVATE)

        val device_name = sharedPref.getString(SAVED_DEVICE_KEY, null)
        device_name?.let {
            Log.i("preferences", "Read name $SAVED_DEVICE_KEY = $device_name")
            socket?.connectToDevice(findDeviceByName(bluetoothAdapter, it))
        }


        button.setOnClickListener {
            showDialog(entries, { pickedEntry ->
                val device = findDeviceByName(bluetoothAdapter, pickedEntry)
                device.uuids.forEach { Log.i("client", "${it.uuid}") }
                Log.i("client", "Device ${device.name} found")
                with(sharedPref.edit()) {
                    putString(SAVED_DEVICE_KEY, pickedEntry)
                    Log.i("preferences", "Saving name $SAVED_DEVICE_KEY = $pickedEntry")
                    commit()
                }
                socket?.connectToDevice(device)

            })
        }
    }

    fun updateUI() {
        runOnUiThread {
            when (state.connection) {
                ConnectionStatus.DISCONNECTED -> {
                    textview?.text = "DISCONNECTED"
                    textview?.visibility = View.VISIBLE
                    connecting?.visibility = View.INVISIBLE
                }
                ConnectionStatus.CONNECTED -> {
                    textview?.text = "CONNECTED"
                    textview?.visibility = View.VISIBLE
                    connecting?.visibility = View.INVISIBLE
                }
                ConnectionStatus.CONNECTING -> {
                    textview?.text = "CONNECTING"
                    textview?.visibility = View.VISIBLE
                    connecting?.visibility = View.VISIBLE
                }
            }
            val id = when (state.ncLevel) {
                Protocol.NoiseLevels.HIGH -> R.id.radioNCHigh
                Protocol.NoiseLevels.LOW -> R.id.radioNCLow
                Protocol.NoiseLevels.OFF -> R.id.radioNCOff
            }
            findViewById<RadioButton>(id).isChecked = true

            val modeID = when (state.btnMode) {
                Protocol.ButtonModes.ALEXA -> R.id.btnAlexa
                Protocol.ButtonModes.NC -> R.id.btnNC
                Protocol.ButtonModes.ERROR -> null
            }
            modeID?.let {
                findViewById<RadioButton>(modeID).isChecked = true
            }

            findViewById<TextView>(R.id.version).text = "Version ${state.version}"
            findViewById<TextView>(R.id.name).text = "Name ${state.name}"
            findViewById<TextView>(R.id.battery).text = "Battery level ${state.batteryLevel}"

            when (state.auto_off_period) {
                0 -> findViewById<RadioButton>(R.id.radioOFFNever).isChecked = true
                20 -> findViewById<RadioButton>(R.id.radioOFF20).isChecked = true
                60 -> findViewById<RadioButton>(R.id.radioOFF60).isChecked = true
                180 -> findViewById<RadioButton>(R.id.radioOFF180).isChecked = true
                else -> Log.e("AUTO_OFF", "Can't handle ${state.auto_off_period}")
            }

        }
    }

    fun parseEvents(incomingEvents: BlockingQueue<BTSocket.Events>) {
        Thread {
            while (true) {
                val event = incomingEvents.poll(1, TimeUnit.SECONDS)
                event?.let {
                    Log.i("Events", "Got ${it.type.name} ${it.payload}")
                    when (it.type) {
                        BTSocket.EventType.DISCONNECTED -> state.connection = ConnectionStatus.DISCONNECTED
                        BTSocket.EventType.CONNECTING -> state.connection = ConnectionStatus.CONNECTING
                        BTSocket.EventType.CONNECTED -> {
                            state.connection = ConnectionStatus.CONNECTED
                            state.version = it.payload!!
                        }
                        BTSocket.EventType.RCV_NC_LEVEL -> state.ncLevel = Protocol.NoiseLevels.valueOf(it.payload!!)
                        BTSocket.EventType.RCV_NAME -> state.name = it.payload!!
                        BTSocket.EventType.RCV_AUTO_OFF -> state.auto_off_period = it.payload!!.toInt()
                        BTSocket.EventType.UNKNOWN -> {
                        }
                        BTSocket.EventType.RCV_BTN_MODE -> state.btnMode = Protocol.ButtonModes.valueOf(it.payload!!)
                        BTSocket.EventType.RCV_BATTERY_LEVEL -> state.batteryLevel = it.payload!!.toInt()
                    }
                    updateUI()
                }
            }
        }.start()
    }


    private fun showDialog(array: Array<String>, callback: (String) -> Unit) {

        val builder = AlertDialog.Builder(this)
        builder.setTitle("Pick your headphones")
        builder.setItems(array, { _, item ->
            Log.i("lambda", "Lambda ${array[item]}")
            callback(array[item])
        })
        val dialog = builder.create()
        dialog.show()
    }

}
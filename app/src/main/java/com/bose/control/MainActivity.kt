package com.bose.control

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.LocationManager
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
import kotlinx.android.synthetic.main.activity_main.*
import java.util.*
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit


val uuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

class MainActivity : AppCompatActivity() {

    val MY_PERMISSIONS_REQUEST_LOCATION = 99
    var provider: String? = null
    var textview: TextView? = null

    var connecting: ProgressBar? = null
    var socket: BTSocket? = null

    val SAVED_DEVICE_KEY = "com.bose.control.SAVED_DEVICE_KEY"


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

    fun setNCStatus(e: BTSocket.Events) {
        val id = when (e.type) {
            BTSocket.EventType.RCV_NC_LEVEL_HIGH -> R.id.radioNCHigh
            BTSocket.EventType.RCV_NC_LEVEL_LOW -> R.id.radioNCLow
            BTSocket.EventType.RCV_NC_LEVEL_OFF -> R.id.radioNCOff
            else -> null
        }
        id?.let {
            findViewById<RadioButton>(it).isChecked = true
        }
    }

    fun setStatusText(text: String) {
        runOnUiThread {
            this.textview?.text = text
            this.textview?.visibility = View.VISIBLE
        }
    }

    fun setProgressVisible(visible: Boolean) {
        runOnUiThread {
            connecting?.visibility = when (visible) {
                true -> View.VISIBLE
                false -> View.INVISIBLE
            }
        }
    }

    fun connect(device: BluetoothDevice) {
        setProgressVisible(true)
        Thread {
            socket?.connectToDevice(device)
        }.start()

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
        val locationManager: LocationManager =
            getSystemService(Context.LOCATION_SERVICE) as LocationManager
        provider = locationManager.getBestProvider(Criteria(), false)
        checkLocationPermission()

        val button = findViewById<Button>(R.id.button)
        connecting = findViewById(R.id.progressBar)

        textview = findViewById(R.id.text)
        setProgressVisible(false)

        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        bluetoothAdapter.cancelDiscovery()

        val outgoingMsg: BlockingQueue<ShortArray> = LinkedBlockingQueue()
        val incomingEvents: BlockingQueue<BTSocket.Events> = LinkedBlockingQueue()

        val uiToQueue: Map<View, Protocol.Messages> = mapOf(
            Pair(findViewById(R.id.radioNCHigh), Protocol.Messages.NOISE_LEVEL_HIGH),
            Pair(findViewById(R.id.radioNCLow), Protocol.Messages.NOISE_LEVEL_LOW),
            Pair(findViewById(R.id.radioNCOff), Protocol.Messages.NOISE_LEVEL_OFF),
            Pair(findViewById(R.id.btnDeviceStatus), Protocol.Messages.GET_DEVICE_STATUS),
            Pair(findViewById(R.id.radioOFFNever), Protocol.Messages.AUTO_OFF_NEVER),
            Pair(findViewById(R.id.radioOFF20), Protocol.Messages.AUTO_OFF_20),
            Pair(findViewById(R.id.radioOFF60), Protocol.Messages.AUTO_OFF_60),
            Pair(findViewById(R.id.radioOFF180), Protocol.Messages.AUTO_OFF_180)
        )
        uiToQueue.forEach { e -> run {
            e.key.setOnClickListener { outgoingMsg.put(e.value.msg)}
        } }

        socket = BTSocket(incomingEvents, outgoingMsg)

        parseEvents(incomingEvents, outgoingMsg)

        val entries: Array<String> = bluetoothAdapter.bondedDevices.map { it.name }.toTypedArray()
        val sharedPref = getPreferences(Context.MODE_PRIVATE)

        val device_name = sharedPref.getString(SAVED_DEVICE_KEY, null)
        device_name?.let {
            Log.i("preferences", "Read name $SAVED_DEVICE_KEY = $device_name")
            connect(findDeviceByName(bluetoothAdapter, it))
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
                connect(device)

            })
        }
    }

    // FIXME set state and have update UI function
    fun parseEvents(incomingEvents: BlockingQueue<BTSocket.Events>, outgoingMsg: BlockingQueue<ShortArray>) {
        Thread {
            while (true) {
                val event = incomingEvents.poll(1, TimeUnit.SECONDS)
                event?.let {
                    Log.i("Events", "Got ${it.type.name} ${it.payload}")
                    when (it.type) {
                        BTSocket.EventType.DISCONNECTED -> setStatusText("DISCONNECTED")
                        BTSocket.EventType.CONNECTING -> setStatusText("CONNECTING")
                        BTSocket.EventType.CONNECTED -> {
                            setStatusText("CONNECTED")
                            runOnUiThread {
                                progressBar.visibility = View.INVISIBLE
                                findViewById<TextView>(R.id.version).text = "Version ${it.payload}"
                                findViewById<TextView>(R.id.text).visibility = View.INVISIBLE

                            }
                        }
                        BTSocket.EventType.RCV_NC_LEVEL_HIGH -> setNCStatus(it)
                        BTSocket.EventType.RCV_NC_LEVEL_LOW -> setNCStatus(it)
                        BTSocket.EventType.RCV_NC_LEVEL_OFF -> setNCStatus(it)
                        BTSocket.EventType.RCV_NAME -> runOnUiThread {
                            findViewById<TextView>(R.id.name).text = "Name ${it.payload}"
                            runOnUiThread {
                                progressBar.visibility = View.INVISIBLE
                            }
                        }
                        BTSocket.EventType.RCV_AUTO_OFF -> runOnUiThread {
                            when (it.payload!!.toInt()) {
                                0 -> findViewById<RadioButton>(R.id.radioOFFNever).isChecked = true
                                20 -> findViewById<RadioButton>(R.id.radioOFF20).isChecked = true
                                60 -> findViewById<RadioButton>(R.id.radioOFF60).isChecked = true
                                180 -> findViewById<RadioButton>(R.id.radioOFF180).isChecked = true
                                else -> Log.e("AUTO_OFF", "Can't handle ${it.payload}")
                            }

                        }
                        BTSocket.EventType.UNKNOWN -> true
                    }
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
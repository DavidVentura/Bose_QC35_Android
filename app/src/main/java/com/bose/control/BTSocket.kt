package com.bose.control

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit

class BTSocket constructor(
    private val incomingEvents: BlockingQueue<Events>,
    private val outgoingMsg: BlockingQueue<ShortArray>
) {

    private var inHandler: Thread? = null
    private var outHandler: Thread? = null

    class Events constructor(
        val type: EventType,
        val payload: String?
    ) {
        override fun equals(other: Any?): Boolean {
            if (other == null) return false
            if (other.javaClass != javaClass) return false
            val otherEv = other as Events
            return type == otherEv.type && payload == otherEv.payload
        }

    }

    enum class EventType {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        RCV_NC_LEVEL,
        RCV_NAME,
        UNKNOWN,
        RCV_AUTO_OFF,
        RCV_BATTERY_LEVEL,
        RCV_BTN_MODE,
    }


    var stop = false
    private var socket: BluetoothSocket? = null

    fun emit(e: Events) {
        incomingEvents.put(e)
    }

    fun connectToDevice(device: BluetoothDevice) {
        outgoingMsg.clear()

        socket?.let {
            if (it.isConnected) {
                emit(Events(EventType.DISCONNECTED, null))
                Log.i("client", "socket was still connected")
                it.close()
                Log.i("client", "old socket closed")
                stop = true
                inHandler?.join()
                Log.i("client", "inHandler joined")
                outHandler?.join()
                Log.i("client", "outHandler joined")

            }
        }
        stop = false

        emit(Events(EventType.CONNECTING, null))
        socket = device.createRfcommSocketToServiceRecord(uuid)

        Log.i("client", "Socket created")

        Thread {
            try {
                socket!!.connect()

            } catch (e: IOException) {
                Log.e("client", "Failed to connect: ${e.localizedMessage}")
                socket!!.close()
            }
            Log.i("client", "Connected? ${socket!!.isConnected}")
            if (socket!!.isConnected) {
                outgoingMsg.offer(Protocol.Messages.CONNECT.msg)
                inHandler = MessageHandler(socket!!)
                outHandler = OutputHandler(socket!!)
                inHandler!!.start()
                outHandler!!.start()
            } else {
                incomingEvents.put(Events(EventType.DISCONNECTED, "Disconnected from device"))
            }
        }.start()
    }


    inner class OutputHandler constructor(
        val socket: BluetoothSocket
    ) : Thread() {
        override fun run() {

            while (socket.isConnected && !stop) {
                Log.i("SocketSend", "About to poll")

                val value = outgoingMsg.poll(1, TimeUnit.SECONDS)
                if (value != null) {
                    Log.i("SocketSend", "Sending ${value.asList()}")
                    try {
                        value.forEach { sh -> socket.outputStream.write(sh.toInt()) }
                        socket.outputStream.flush()
                    } catch (e: IOException) {
                        Log.e("SocketSend", "Failed to write: ${e.localizedMessage}")
                        stop = true
                        break
                    }
                } else {
                    sleep(100)
                }
            }
            Log.e("client", "Disconnected")
            try {
                socket.close()
            } catch (e: IOException) {
                Log.w("close", "Can't close socket: ${e.localizedMessage}")
            }
        }
    }

    inner class MessageHandler constructor(
        val socket: BluetoothSocket
    ) : Thread() {
        override fun run() {
            var count: Int = 0
            val i = socket.inputStream
            Log.i("thread", "Listening on socket")
            var sawConnected = false
            while (!stop && socket.isConnected) {
                var bAv : Int
                try {
                    bAv = i.available()
                } catch (e: IOException) {
                    Log.i("thread", "Failure when reading from socket: $e")
                    return
                }
                if (bAv == 0) {
                    sleep(100)
                    count += 1
                    if (count >= 15) {
                        count = 0
                        outgoingMsg.offer(Protocol.Messages.GET_BATTERY_LEVEL.msg)
                        outgoingMsg.put(Protocol.Messages.GET_DEVICE_STATUS.msg)
                        Log.i("thread", "Asking for status")
                    }
                    continue
                }
                count = 0
                Log.i("thread", "${bAv} bytes to read")
                val buf = ByteArray(bAv)
                i.read(buf)
                val bufOfShorts = buf.map {
                    it.toUByte().toShort()
                }.toShortArray()
                Log.i("thread", "Bytes gotten: ${bufOfShorts.toList()}")

                bufferToEvents(bufOfShorts).forEach {
                    if (it.type == EventType.CONNECTED) {
                        sawConnected = true
                        outgoingMsg.offer(Protocol.Messages.GET_DEVICE_STATUS.msg)
                    }
                    if (!sawConnected) { // sometimes a connection will be established without the headphones
                                         // sending a proper connection ACK
                        outgoingMsg.offer(Protocol.Messages.CONNECT.msg)
                    }
                    emit(it)
                }
            }
        }
    }
}
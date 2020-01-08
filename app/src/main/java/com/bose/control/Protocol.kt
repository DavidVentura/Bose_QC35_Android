package com.bose.control

import android.util.Log

class Protocol {

    enum class NoiseLevels(val level: Short) {
        LOW(0x03),
        HIGH(0x01),
        OFF(0x0);

        companion object {
            fun from(findValue: Short): NoiseLevels = values().first { it.level == findValue }
        }
    }
    enum class ButtonModes(val mode: Short) {
        ALEXA(0x01),
        NC(0x02),
        ERROR(0x7F);
        companion object {
            fun from(findValue: Short): ButtonModes = ButtonModes.values().first { it.mode == findValue }
        }
    }

    enum class AutoOffTimeout(val timeout: Short) {
        NEVER(0),
        _20(20),
        _60(60),
        _180(180)

    }

    enum class Messages(val msg: ShortArray) {
        CONNECT(shortArrayOf            (0x00, 0x01, 0x01, 0x00)),
        NOISE_LEVEL_LOW(shortArrayOf    (0x01, 0x06, 0x02, 0x01, NoiseLevels.LOW.level)),
        NOISE_LEVEL_HIGH(shortArrayOf   (0x01, 0x06, 0x02, 0x01, NoiseLevels.HIGH.level)),
        NOISE_LEVEL_OFF(shortArrayOf    (0x01, 0x06, 0x02, 0x01, NoiseLevels.OFF.level)),
        AUTO_OFF_NEVER(shortArrayOf     (0x01, 0x04, 0x02, 0x01, AutoOffTimeout.NEVER.timeout)),
        AUTO_OFF_20(shortArrayOf        (0x01, 0x04, 0x02, 0x01, AutoOffTimeout._20.timeout)),
        AUTO_OFF_60(shortArrayOf        (0x01, 0x04, 0x02, 0x01, AutoOffTimeout._60.timeout)),
        AUTO_OFF_180(shortArrayOf       (0x01, 0x04, 0x02, 0x01, AutoOffTimeout._180.timeout)),

        GET_DEVICE_STATUS(shortArrayOf( 0x01, 0x01, 0x05, 0x00)),
        GET_BATTERY_LEVEL(shortArrayOf( 0x02, 0x02, 0x01, 0x00)),
        BTN_MODE_ALEXA(shortArrayOf(    0x1, 0x9, 0x2, 0x3, 0x10, 0x4, 0x1)),
        BTN_MODE_NC(shortArrayOf(       0x1, 0x9, 0x2, 0x3, 0x10, 0x4, 0x2)),
    }


    enum class ACKMessages(val msg: ShortArray) {
        CONNECT(shortArrayOf        (0x0, 0x1, 0x3, 0x5)),
        ACK_1(shortArrayOf          (0x1, 0x1, 0x7, 0x0)),
        ACK_2(shortArrayOf          (0x1, 0x1, 0x6, 0x0)),
        NAME(shortArrayOf           (0x1, 0x2, 0x3, 0x7F, 0x0)), // FIXME 0x7F ANY
        AUTO_OFF(shortArrayOf       (0x1, 0x4, 0x3, 0x1, 0x7F)),
        NOISE_LEVEL(shortArrayOf    (0x1, 0x6, 0x3, 0x2, 0x7F, 0xb)),
        LANG(shortArrayOf           (0x1, 0x3, 0x3, 0x5, 0x7F, 0x00, 0x7F, 0x7F, 0xde)),
        BATTERY_LEVEL(shortArrayOf  (0x02, 0x02, 0x03, 0x01)),
        BTN_ACTION(shortArrayOf(     0x01, 0x09, 0x03, 0x04, 0x10, 0x04, 0x7F, 0x07)),
        UNKNOWN(shortArrayOf(0x7E, 0x7E)) // FIXME 0x7E "NOTHING"
    }


}

fun stringifyShort(buf: ShortArray): String {
    return String(buf.map { it.toChar() }.toCharArray())
}

fun matchBufferToACKMessage(buf: ShortArray): Protocol.ACKMessages? {
    var matched: Protocol.ACKMessages? = null
    for (it in Protocol.ACKMessages.values()) {
        if (it.msg.size > buf.count()) continue

        val items = it.msg.asList()
        val firstBytes = buf.asList().subList(0, items.count())
        Log.d("thread", "Comparing $items with $firstBytes")

        val allBytesMatchOrAreMasked = items.mapIndexed { index, comp ->
            (comp == buf[index]) || comp == 0x7F.toShort()
        }.all { i -> i }

        if (allBytesMatchOrAreMasked) {
            matched = it
            break
        }
    }
    return matched
}

fun messageToEventAndModifyBuffer(msg: Protocol.ACKMessages, buf: ShortArray) : Pair<BTSocket.Events, ShortArray> {
    var localBuf = buf.clone()

    val ret = when (msg) {
        Protocol.ACKMessages.CONNECT -> {
            val fwVersion = buf.copyOfRange(msg.msg.size, msg.msg.size + 5)
            localBuf = buf.copyOfRange(
                fwVersion.size,
                buf.size
            ) // Advance `buf` by the length of the firmware version (5)
            BTSocket.Events(BTSocket.EventType.CONNECTED, stringifyShort(fwVersion))
        }
        Protocol.ACKMessages.AUTO_OFF -> {
            val minutes = buf[4]
            BTSocket.Events(BTSocket.EventType.RCV_AUTO_OFF, minutes.toString())
        }
        Protocol.ACKMessages.NOISE_LEVEL -> BTSocket.Events(BTSocket.EventType.RCV_NC_LEVEL, Protocol.NoiseLevels.from(buf[4]).toString())
        Protocol.ACKMessages.ACK_1 -> BTSocket.Events(BTSocket.EventType.UNKNOWN, "ACK1")
        Protocol.ACKMessages.ACK_2 -> BTSocket.Events(BTSocket.EventType.UNKNOWN, "ACK2")
        Protocol.ACKMessages.NAME -> {
            val nameBuf = buf.copyOfRange(
                5,
                5 + buf[3] - 1
            ) // Size is stored at the 3rd byte of the header
            // and 5 is the start of the name in the current buffer
            localBuf = buf.copyOfRange(
                nameBuf.size,
                buf.size
            ) // Advance `buf` by the length of the name
            BTSocket.Events(BTSocket.EventType.RCV_NAME, stringifyShort(nameBuf))
        }
        Protocol.ACKMessages.LANG -> BTSocket.Events(
            BTSocket.EventType.UNKNOWN,
            "Got some language ${buf[4]}"
        )
        Protocol.ACKMessages.BATTERY_LEVEL -> {
            val batteryValue = buf[4]
            localBuf = buf.copyOfRange(
                1,
                buf.size
            ) // Advance `buf` by the length of the battery status (1 byte)
            BTSocket.Events(BTSocket.EventType.RCV_BATTERY_LEVEL, batteryValue.toString())
        }
        Protocol.ACKMessages.BTN_ACTION -> {
            val btnMode = buf[6]
            BTSocket.Events(BTSocket.EventType.RCV_BTN_MODE, Protocol.ButtonModes.from(btnMode).toString())
        }
        Protocol.ACKMessages.UNKNOWN -> BTSocket.Events(BTSocket.EventType.UNKNOWN, null)
    }
    return Pair(ret, localBuf)
}

fun bufferToEvents(buf: ShortArray): List<BTSocket.Events> {
    val ret = ArrayList<BTSocket.Events>()

    var localBuf = buf.clone()
    while (localBuf.count() > 0) {
        val matched = matchBufferToACKMessage(localBuf)

        if (matched == null) {
            Log.i("thread", "Unknown message: ${localBuf.asList()}")
            localBuf = localBuf.copyOfRange(1, localBuf.size)
            continue
        }

        Log.i("thread", "Message: ${matched.msg.asList()}")


        val eventAndBuffer = messageToEventAndModifyBuffer(matched, localBuf)
        ret.add(eventAndBuffer.first)
        localBuf = eventAndBuffer.second
        localBuf = localBuf.copyOfRange(matched.msg.size, localBuf.size)

    }
    return ret
}
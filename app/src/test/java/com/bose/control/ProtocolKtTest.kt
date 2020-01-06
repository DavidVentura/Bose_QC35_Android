package com.bose.control

import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolKtTest {

    @Test
    fun stringifyShort() {
        assertEquals(stringifyShort(shortArrayOf(65)), "A")
        assertEquals(
            stringifyShort(shortArrayOf(66, 111, 115, 101, 32, 81, 67, 51, 53, 32, 73, 73)),
            "Bose QC35 II"
        )
        assertEquals(stringifyShort(shortArrayOf(49, 46, 48, 46, 52)), "1.0.4")
    }

    @Test
    fun bufferToEventsConnected() {
        val connectedFirmware = shortArrayOf(0, 1, 3, 5, 49, 46, 48, 46, 52)
        val expectedConnectedMessage = BTSocket.Events(BTSocket.EventType.CONNECTED, "1.0.4")
        assertEquals(bufferToEvents(connectedFirmware), listOf(expectedConnectedMessage))
    }

    @Test
    fun bufferToEventsStatus() {
        val statusReply = shortArrayOf(
            1, 1, 7, 0, 1, 2, 3, 13, 0, 66, 111, 115, 101, 32, 81, 67, 51, 53, 32, 73, 73,
            1, 3, 3, 5, 129, 0, 4, 207, 222, 1, 4, 3, 1, 20, 1, 6, 3, 2, 1, 11, 1, 9, 3, 4, 16, 4, 2, 7, 1, 1, 6, 0
        )
        val expectedStatusMessages = listOf(
            BTSocket.Events(BTSocket.EventType.UNKNOWN, "ACK1"),
            BTSocket.Events(BTSocket.EventType.RCV_NAME, "Bose QC35 II"),
            BTSocket.Events(BTSocket.EventType.UNKNOWN, "Got some language 129"), // FIXME
            BTSocket.Events(BTSocket.EventType.RCV_AUTO_OFF, "20"),
            BTSocket.Events(BTSocket.EventType.RCV_NC_LEVEL_HIGH, null)
        )

        assertEquals(bufferToEvents(statusReply), expectedStatusMessages)
    }
}
package org.crisisnetwork.android.core.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SendQueueTest {
    @Test
    fun packetsComeOutInTheOrderTheyWentIn() {
        val queue = SendQueue(1_000)
        assertTrue(queue.offer(byteArrayOf(1)))
        assertTrue(queue.offer(byteArrayOf(2, 2)))
        assertArrayEquals(byteArrayOf(1), queue.peek())
        assertArrayEquals(byteArrayOf(1), queue.removeFirst())
        assertArrayEquals(byteArrayOf(2, 2), queue.removeFirst())
        assertNull(queue.removeFirst())
        assertTrue(queue.isEmpty)
    }

    @Test
    fun aPacketThatDoesNotFitIsRefusedAndTheQueueIsUnchanged() {
        val queue = SendQueue(10)
        assertTrue(queue.offer(ByteArray(6)))
        assertFalse(queue.offer(ByteArray(5)))
        assertEquals(6, queue.bytes)
        assertEquals(1, queue.size)
        assertTrue(queue.offer(ByteArray(4)))
        assertEquals(10, queue.bytes)
    }

    @Test
    fun removingFreesTheSpace() {
        val queue = SendQueue(10)
        queue.offer(ByteArray(10))
        assertFalse(queue.offer(ByteArray(1)))
        queue.removeFirst()
        assertEquals(0, queue.bytes)
        assertTrue(queue.offer(ByteArray(10)))
    }

    @Test
    fun clearEmptiesTheQueue() {
        val queue = SendQueue(100)
        queue.offer(ByteArray(40))
        queue.offer(ByteArray(40))
        queue.clear()
        assertEquals(0, queue.bytes)
        assertTrue(queue.isEmpty)
    }
}

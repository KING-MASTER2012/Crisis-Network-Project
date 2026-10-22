package org.crisisnetwork.android.core.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandleAllocatorTest {
    @Test
    fun handlesStartAtOneAndNeverRepeat() {
        val allocator = HandleAllocator()
        val seen = HashSet<ULong>()
        repeat(1_000) { assertTrue(seen.add(allocator.next())) }
        assertEquals(1_000, seen.size)
        assertTrue(0uL !in seen)
    }

    @Test
    fun handlesIncreaseByOne() {
        val allocator = HandleAllocator()
        assertEquals(1uL, allocator.next())
        assertEquals(2uL, allocator.next())
        assertEquals(3uL, allocator.next())
    }
}

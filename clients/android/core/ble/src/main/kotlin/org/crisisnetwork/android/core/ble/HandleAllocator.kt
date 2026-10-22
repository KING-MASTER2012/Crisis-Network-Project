package org.crisisnetwork.android.core.ble

/**
 * Hands out the handles the core uses to identify a neighbour. A handle is **never reused** during
 * the life of the transport: a reconnect gets a new one, so that the core can never confuse a
 * returning device (or a different one that got the same radio address) with the old link.
 * Handle 0 is not used.
 */
internal class HandleAllocator(first: ULong = 1uL) {
    private var next: ULong = first

    fun next(): ULong {
        val handle = next
        check(handle != ULong.MAX_VALUE) { "handle space exhausted" }
        next = handle + 1uL
        return handle
    }
}

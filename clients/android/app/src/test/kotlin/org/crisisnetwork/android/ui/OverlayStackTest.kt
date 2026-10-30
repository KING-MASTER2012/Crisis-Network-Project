package org.crisisnetwork.android.ui

import org.crisisnetwork.android.ui.shell.Overlay
import org.crisisnetwork.android.ui.shell.OverlayStack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OverlayStackTest {
    private val alice = "aa11bb22"
    private val bob = "cc33dd44"

    @Test
    fun everyOverlayRoundTripsThroughItsCode() {
        val all = listOf(Overlay.Chat(alice), Overlay.Contact(bob), Overlay.MyCard, Overlay.Scan, Overlay.Paste, Overlay.NewChat, Overlay.Compose, Overlay.Network)
        for (overlay in all) assertEquals(overlay, Overlay.parse(overlay.encode()))
        assertEquals(all, OverlayStack.decode(OverlayStack.encode(all)))
        assertEquals(emptyList<Overlay>(), OverlayStack.decode(""))
    }

    @Test
    fun unknownOrBrokenCodesAreIgnored() {
        assertNull(Overlay.parse("settings"))
        assertNull(Overlay.parse("chat:"))
        assertNull(Overlay.parse("chat:xyz"))
        assertNull(Overlay.parse("chat:abc"))
        assertNull(Overlay.parse("contact:AA11"))
        assertEquals(listOf(Overlay.Scan), OverlayStack.decode("future-screen|scan|chat:zz"))
    }

    @Test
    fun backFromAContactOpenedInAChatReturnsToTheChat() {
        var stack = OverlayStack.push(emptyList(), Overlay.Chat(alice))
        stack = OverlayStack.push(stack, Overlay.Contact(alice))
        assertEquals(listOf(Overlay.Chat(alice), Overlay.Contact(alice)), stack)
        // "Send a message" in the contact goes back to the chat instead of opening a second one.
        stack = OverlayStack.toChat(stack, alice)
        assertEquals(listOf<Overlay>(Overlay.Chat(alice)), stack)
        assertEquals(emptyList<Overlay>(), OverlayStack.pop(stack))
        assertEquals(emptyList<Overlay>(), OverlayStack.pop(emptyList()))
    }

    @Test
    fun aChatFromTheContactListIsPushedOnce() {
        var stack = OverlayStack.push(emptyList(), Overlay.Contact(bob))
        stack = OverlayStack.toChat(stack, bob)
        assertEquals(listOf(Overlay.Contact(bob), Overlay.Chat(bob)), stack)
        // Pushing what is already on top does nothing.
        assertEquals(stack, OverlayStack.push(stack, Overlay.Chat(bob)))
    }
}

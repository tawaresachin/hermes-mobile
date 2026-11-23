package com.hermes.mobile.wake

import org.junit.Assert.assertEquals
import org.junit.Test

class WakeCoordinatorTest {
    @Test
    fun `disabled wake is always ignored`() {
        val c = WakeCoordinator()
        assertEquals(WakeDisposition.Ignore, c.decide(enabled = false, foreground = true))
        c.onSurfaceChanged(WakeSurface.Chat)
        assertEquals(WakeDisposition.Ignore, c.decide(enabled = false, foreground = true))
        assertEquals(WakeDisposition.Ignore, c.decide(enabled = false, foreground = false))
    }

    @Test
    fun `chat in front consumes the wake`() {
        val c = WakeCoordinator()
        c.onSurfaceChanged(WakeSurface.Chat)
        assertEquals(WakeDisposition.HandleInChat, c.decide(enabled = true, foreground = true))
        assertEquals(WakeDisposition.HandleInChat, c.decide(enabled = true, foreground = false))
    }

    @Test
    fun `foreground outside chat navigates to chat`() {
        val c = WakeCoordinator()
        assertEquals(WakeDisposition.OpenChat, c.decide(enabled = true, foreground = true))
    }

    @Test
    fun `background outside chat raises an alert`() {
        val c = WakeCoordinator()
        assertEquals(WakeDisposition.ShowAlert, c.decide(enabled = true, foreground = false))
    }

    @Test
    fun `surface changes are respected`() {
        val c = WakeCoordinator()
        assertEquals(WakeDisposition.OpenChat, c.decide(enabled = true, foreground = true))
        c.onSurfaceChanged(WakeSurface.Chat)
        assertEquals(WakeDisposition.HandleInChat, c.decide(enabled = true, foreground = true))
        c.onSurfaceChanged(WakeSurface.Elsewhere)
        assertEquals(WakeDisposition.OpenChat, c.decide(enabled = true, foreground = true))
    }
}

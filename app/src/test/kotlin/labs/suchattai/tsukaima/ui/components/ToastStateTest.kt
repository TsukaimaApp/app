package labs.suchattai.tsukaima.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToastStateTest {
    @Test
    fun showSetsCurrentAndDismissClearsIt() {
        val state = ToastState()
        assertNull(state.current)
        state.show("Copied")
        assertEquals("Copied", state.current)
        state.dismiss()
        assertNull(state.current)
    }

    @Test
    fun repeatedShowOfSameTextBumpsSerial() {
        val state = ToastState()
        state.show("Copied")
        val first = state.serial
        state.show("Copied")
        assertEquals(first + 1, state.serial)
    }
}

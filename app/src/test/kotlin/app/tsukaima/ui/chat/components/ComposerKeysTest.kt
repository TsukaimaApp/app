package app.tsukaima.ui.chat.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerKeysTest {
    @Test
    fun softKeyboardEnterInsertsANewline() {
        assertFalse(ComposerKeys.enterSends(deviceId = ComposerKeys.VIRTUAL_KEYBOARD, shiftPressed = false))
        assertFalse(ComposerKeys.enterSends(deviceId = ComposerKeys.VIRTUAL_KEYBOARD, shiftPressed = true))
    }

    @Test
    fun hardwareEnterSendsAndShiftEnterInsertsANewline() {
        assertTrue(ComposerKeys.enterSends(deviceId = 7, shiftPressed = false))
        assertFalse(ComposerKeys.enterSends(deviceId = 7, shiftPressed = true))
    }
}

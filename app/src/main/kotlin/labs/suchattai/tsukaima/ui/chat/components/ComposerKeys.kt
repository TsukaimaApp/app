package labs.suchattai.tsukaima.ui.chat.components

/**
 * What the composer does with an Enter key press. Only a physical keyboard sends on Enter (Shift+Enter inserts a
 * newline there); an on-screen keyboard's Enter always inserts a newline, because its key events come from the
 * virtual device and the Send button is the way to send. The soft keyboard's own Enter shows no "Send" action
 * either ([androidx.compose.ui.text.input.ImeAction.Default]), so a newline is always one tap away.
 */
internal object ComposerKeys {
    /** Android's `KeyCharacterMap.VIRTUAL_KEYBOARD` device id: events typed on the on-screen keyboard. */
    const val VIRTUAL_KEYBOARD = -1

    /**
     * True when an Enter press should send: it came from a physical keyboard ([deviceId] is not the virtual one)
     * and Shift was not held.
     */
    fun enterSends(deviceId: Int, shiftPressed: Boolean): Boolean = deviceId != VIRTUAL_KEYBOARD && !shiftPressed
}

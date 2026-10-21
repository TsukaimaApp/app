package labs.suchattai.tsukaima.ui.chat.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerActionTest {
    @Test fun emptyFieldOffersVoice() = assertEquals(ComposerAction.Voice, composerAction("", false, true, false, true))
    @Test fun textSwapsToSend() = assertEquals(ComposerAction.Send, composerAction("hi", false, true, false, true))
    @Test fun attachmentsSwapToSend() = assertEquals(ComposerAction.Send, composerAction("", true, true, false, true))
    @Test fun runningIsStop() = assertEquals(ComposerAction.Stop, composerAction("", false, true, true, true))
    @Test fun runningWithTextAlsoSendsWhenDone() =
        assertEquals(ComposerAction.StopAndSendWhenDone, composerAction("hi", false, true, true, true))
    @Test fun runningWithAttachmentsAlsoSendsWhenDone() =
        assertEquals(ComposerAction.StopAndSendWhenDone, composerAction(" ", true, true, true, false))
    @Test fun noVoiceHandlerIsSend() = assertEquals(ComposerAction.Send, composerAction("", false, true, false, false))
    @Test fun disabledIsSend() = assertEquals(ComposerAction.Send, composerAction("", false, false, false, true))
}

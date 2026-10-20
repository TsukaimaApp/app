package labs.suchattai.tsukaima.assist

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * A recogniser that recognises nothing. The assistant's XML must name a recognition service: Android 10/11 reset
 * an assistant without one (12+ ignore it). Voice mode uses the platform's own recogniser instead
 * ([labs.suchattai.tsukaima.voice.AndroidSpeechInput] skips this one); it is not selectable as the default.
 */
class TsukaimaRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback) {
        listener.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onStopListening(listener: Callback) = Unit

    override fun onCancel(listener: Callback) = Unit
}

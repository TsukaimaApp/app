package labs.suchattai.tsukaima.assist

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import labs.suchattai.tsukaima.voice.systemRecognizer

/**
 * A recogniser that forwards to the first system recogniser ([systemRecognizer]). The assistant's XML must name a
 * recognition service: Android 10/11 reset an assistant without one (12+ ignore it), and there making Tsukaima the
 * assistant also makes this service the device's default recogniser, so other apps' speech input arrives here.
 * Each [onStartListening] creates one delegate [SpeechRecognizer] bound to that system recogniser, passes the
 * caller's intent through unchanged and relays every callback ([Callback] has no relay for `onEvent`); the
 * delegate is destroyed on cancel, after results and after an error. No system recogniser →
 * [SpeechRecognizer.ERROR_CLIENT]. Voice mode itself never uses this service
 * ([labs.suchattai.tsukaima.voice.AndroidSpeechInput] skips it); it is not selectable as the default. Recognised
 * text is never logged.
 */
class TsukaimaRecognitionService : RecognitionService() {
    private val main = Handler(Looper.getMainLooper())

    /** The live delegate; touched on the main thread only. */
    private var delegate: SpeechRecognizer? = null

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback) = onMain {
        release()
        val target = systemRecognizer(this)
        if (target == null || target.packageName == packageName) {
            relay(listener) { error(SpeechRecognizer.ERROR_CLIENT) }
            return@onMain
        }
        start(target, recognizerIntent, listener)
    }

    override fun onStopListening(listener: Callback) = onMain { delegate?.stopListening() }

    override fun onCancel(listener: Callback) = onMain { release() }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun start(target: ComponentName, recognizerIntent: Intent?, listener: Callback) {
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this, target)
        delegate = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            /** Relays [call] while [recognizer] is still the live delegate; [end] destroys it afterwards. */
            private fun forward(end: Boolean = false, call: Callback.() -> Unit) {
                if (delegate !== recognizer) return
                relay(listener, call)
                if (end) release(recognizer)
            }

            override fun onReadyForSpeech(params: Bundle?) = forward { readyForSpeech(params) }
            override fun onBeginningOfSpeech() = forward { beginningOfSpeech() }
            override fun onRmsChanged(rmsdB: Float) = forward { rmsChanged(rmsdB) }
            override fun onBufferReceived(buffer: ByteArray?) = forward { bufferReceived(buffer) }
            override fun onEndOfSpeech() = forward { endOfSpeech() }
            override fun onPartialResults(partialResults: Bundle?) = forward { partialResults(partialResults) }
            override fun onResults(results: Bundle?) = forward(end = true) { results(results) }
            override fun onError(error: Int) = forward(end = true) { error(error) }
            // Callback has no event relay; vendor events stay with the delegate.
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        recognizer.startListening(recognizerIntent ?: Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH))
    }

    /** Calls [call] on the client's [listener]; a dead client drops the delegate. */
    private fun relay(listener: Callback, call: Callback.() -> Unit) {
        try {
            listener.call()
        } catch (e: RemoteException) {
            Log.w(TAG, "Recognition client gone", e)
            release()
        }
    }

    /** Destroys the live delegate, or only [only] if it is still the live one. */
    private fun release(only: SpeechRecognizer? = null) {
        val current = delegate ?: return
        if (only != null && only !== current) return
        delegate = null
        current.cancel()
        current.destroy()
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private companion object {
        const val TAG = "Tsukaima"
    }
}

package labs.suchattai.tsukaima.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import labs.suchattai.tsukaima.domain.voice.SpeechEvent
import labs.suchattai.tsukaima.domain.voice.SpeechInput

/**
 * [SpeechInput] on the platform [SpeechRecognizer]. Each [listen] run owns its own recogniser (created on Main,
 * destroyed when the flow ends or its collector cancels), so a new run may start right after an old one is
 * cancelled. The flow runs on `Dispatchers.Main.immediate` regardless of the collector, because
 * [SpeechRecognizer] must be created and used on the main thread. Recognised text is never logged.
 */
class AndroidSpeechInput(private val context: Context) : SpeechInput {
    override val available: Boolean
        get() = onDeviceAvailable() || recognizers().isNotEmpty()

    private fun onDeviceAvailable() =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /** Installed recognition services other than Tsukaima's own stub (`assist.TsukaimaRecognitionService`). */
    private fun recognizers(): List<ComponentName> =
        context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { it.serviceInfo }
            .filter { it.packageName != context.packageName }
            .map { ComponentName(it.packageName, it.name) }

    /**
     * On Android 10/11, making Tsukaima the device assistant also makes its stub recogniser the default one; skip it
     * there in favour of another installed recogniser. Null means "the system default" (always the case on 12+).
     */
    private fun explicitRecognizer(): ComponentName? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return null
        val current = Settings.Secure.getString(context.contentResolver, DEFAULT_RECOGNIZER_SETTING)
            ?.let(ComponentName::unflattenFromString)
        if (current?.packageName != context.packageName) return null
        return recognizers().firstOrNull()
    }

    override fun listen(): Flow<SpeechEvent> = callbackFlow {
        val explicit = explicitRecognizer()
        val recognizer = when {
            onDeviceAvailable() -> SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            explicit != null -> SpeechRecognizer.createSpeechRecognizer(context, explicit)
            else -> SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partialResults: Bundle?) {
                first(partialResults)?.let { trySend(SpeechEvent.Partial(it)) }
            }

            override fun onResults(results: Bundle?) {
                val text = first(results)
                trySend(
                    if (text != null) SpeechEvent.Final(text)
                    else SpeechEvent.Error("Nothing recognised", retry = true),
                )
                close()
            }

            override fun onError(error: Int) {
                val retry = when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> false
                    else -> true // no match, timeout, client, busy and the rest
                }
                if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    Log.w(TAG, "Speech recogniser error $error")
                }
                trySend(SpeechEvent.Error(describe(error), retry))
                close()
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        recognizer.startListening(intent)
        awaitClose {
            recognizer.cancel()
            recognizer.destroy()
        }
    }.flowOn(Dispatchers.Main.immediate)

    private fun first(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    private fun describe(error: Int) = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recogniser busy"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Language not supported for speech recognition"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs a network"
        else -> "Speech recognition failed"
    }

    private companion object {
        const val TAG = "Tsukaima"

        /** `Settings.Secure.VOICE_RECOGNITION_SERVICE` (hidden): the default recogniser, as a flattened component. */
        const val DEFAULT_RECOGNIZER_SETTING = "voice_recognition_service"
    }
}

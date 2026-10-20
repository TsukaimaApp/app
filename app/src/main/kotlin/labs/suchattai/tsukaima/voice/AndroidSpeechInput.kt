package labs.suchattai.tsukaima.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import labs.suchattai.tsukaima.domain.voice.RecognizerRouting
import labs.suchattai.tsukaima.domain.voice.SpeechEvent
import labs.suchattai.tsukaima.domain.voice.SpeechInput
import java.util.Locale

/**
 * [SpeechInput] on the platform [SpeechRecognizer]. Each [listen] run owns its own recogniser (created on Main,
 * destroyed when the flow ends or its collector cancels), so a new run may start right after an old one is
 * cancelled. The flow runs on `Dispatchers.Main.immediate` regardless of the collector, because
 * [SpeechRecognizer] must be created and used on the main thread. Recognised text is never logged.
 *
 * Routing ([RecognizerRouting]): the on-device recogniser (Android 12+) and the offline preference are used only for
 * the device's own language (the default, or an explicit tag with the same language), because on-device packs exist
 * for the device language only. An explicitly picked other language goes to the default, network-capable recogniser
 * with no offline preference; before Android 13 a missing on-device pack produces no language error, so the fallback
 * below could not catch it.
 *
 * For the device language the on-device recogniser is preferred, but [SpeechRecognizer.isOnDeviceRecognitionAvailable] does
 * not look at the language: without the language pack every on-device run fails with `ERROR_LANGUAGE_UNAVAILABLE` /
 * `ERROR_LANGUAGE_NOT_SUPPORTED`. The first such failure turns the on-device recogniser off for the rest of the
 * process ([preferOnDevice]) and is reported once as retryable, so the next run uses the default recogniser; only
 * when that one reports the language too is the error fatal.
 *
 * [language] is the voice language setting (a BCP-47 tag, null = the recogniser's default); it is read at each
 * [listen], so a change applies to the next run.
 */
class AndroidSpeechInput(
    private val context: Context,
    private val language: () -> String? = { null },
) : SpeechInput {
    override val available: Boolean
        get() = onDeviceAvailable() || if (stubIsDefault()) systemRecognizer(context) != null else recognizers(context).isNotEmpty()

    private fun onDeviceAvailable() = preferOnDevice &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /**
     * On Android 10/11, making Tsukaima the device assistant also makes its own (forwarding) recogniser the default
     * one (12+ keep the default apart from the assistant). Voice mode then goes to the system recogniser directly
     * instead of through the forwarder.
     */
    private fun stubIsDefault(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return false
        val current = Settings.Secure.getString(context.contentResolver, DEFAULT_RECOGNIZER_SETTING)
            ?.let(ComponentName::unflattenFromString)
        return current?.packageName == context.packageName
    }

    override fun listen(): Flow<SpeechEvent> {
        val tag = language()
        val choice = RecognizerRouting.choose(tag, Locale.getDefault().stripExtensions().toLanguageTag(), onDeviceAvailable())
        val explicit = if (!choice.onDevice && stubIsDefault()) {
            systemRecognizer(context) ?: return flowOf(SpeechEvent.Error(UNAVAILABLE, retry = false))
        } else {
            null
        }
        Log.i(TAG, "Speech recogniser: onDevice=${choice.onDevice} offline=${choice.preferOffline} language=${tag ?: "default"}")
        return recognize(choice.onDevice, choice.preferOffline, explicit, tag)
    }

    /** [explicit] null = the system default recogniser; [tag] null = its default language. */
    private fun recognize(
        onDevice: Boolean,
        preferOffline: Boolean,
        explicit: ComponentName?,
        tag: String?,
    ): Flow<SpeechEvent> = callbackFlow {
        val recognizer = when {
            onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> SpeechRecognizer.createOnDeviceSpeechRecognizer(context) // S: spelled out for lint
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
                    else SpeechEvent.Error("Nothing recognised", retry = true, silence = true),
                )
                close()
            }

            override fun onError(error: Int) {
                val language = when (error) {
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> true
                    else -> false
                }
                val retry = when {
                    // No language pack for the on-device recogniser: fall back to the default one from now on.
                    language && onDevice -> true.also { preferOnDevice = false }
                    language || error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> false
                    else -> true // no match, timeout, client, busy and the rest
                }
                val silence = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                if (!silence) Log.w(TAG, "Speech recogniser error $error")
                trySend(SpeechEvent.Error(describe(error), retry, silence))
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
        if (preferOffline) intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        if (tag != null) {
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
        }
        recognizer.startListening(intent)
        awaitClose {
            recognizer.cancel()
            recognizer.destroy()
        }
    }.flowOn(Dispatchers.Main.immediate)

    private fun first(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    private fun describe(error: Int) = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "Didn’t catch that"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recogniser busy"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone access is needed for voice mode"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Language not supported for speech recognition"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs a network"
        else -> "Speech recognition failed"
    }

    private companion object {
        const val TAG = "Tsukaima"

        /** `Settings.Secure.VOICE_RECOGNITION_SERVICE` (hidden): the default recogniser, as a flattened component. */
        const val DEFAULT_RECOGNIZER_SETTING = "voice_recognition_service"

        const val UNAVAILABLE = "Speech recognition isn’t available on this device"

        /** False once the on-device recogniser reported a missing language pack: the default one is used instead. */
        @Volatile
        var preferOnDevice = true
    }
}

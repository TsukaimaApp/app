package labs.suchattai.tsukaima.voice

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import labs.suchattai.tsukaima.domain.voice.SpeechEvent
import labs.suchattai.tsukaima.domain.voice.SpeechInput

/**
 * [SpeechInput] on the platform [SpeechRecognizer]. Each [listen] run owns its own recogniser (created on Main,
 * destroyed when the flow ends or its collector cancels), so a new run may start right after an old one is
 * cancelled. The flow runs on `Dispatchers.Main.immediate` regardless of the collector, because
 * [SpeechRecognizer] must be created and used on the main thread. Recognised text is never logged.
 *
 * On Android 12+ the on-device recogniser is preferred, but [SpeechRecognizer.isOnDeviceRecognitionAvailable] does
 * not look at the language: without the language pack every on-device run fails with `ERROR_LANGUAGE_UNAVAILABLE` /
 * `ERROR_LANGUAGE_NOT_SUPPORTED`. The first such failure turns the on-device recogniser off for the rest of the
 * process ([preferOnDevice]) and is reported once as retryable, so the next run uses the default recogniser; only
 * when that one reports the language too is the error fatal.
 */
class AndroidSpeechInput(private val context: Context) : SpeechInput {
    override val available: Boolean
        get() = onDeviceAvailable() || if (stubIsDefault()) systemRecognizer() != null else recognizers().isNotEmpty()

    private fun onDeviceAvailable() = preferOnDevice &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /** Installed recognition services other than Tsukaima's own stub (`assist.TsukaimaRecognitionService`). */
    private fun recognizers(): List<ServiceInfo> =
        context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { it.serviceInfo }
            .filter { it.packageName != context.packageName }

    /**
     * On Android 10/11, making Tsukaima the device assistant also makes its stub recogniser the default one (12+ keep
     * the default apart from the assistant).
     */
    private fun stubIsDefault(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return false
        val current = Settings.Secure.getString(context.contentResolver, DEFAULT_RECOGNIZER_SETTING)
            ?.let(ComponentName::unflattenFromString)
        return current?.packageName == context.packageName
    }

    /**
     * The recogniser to use instead of the stub: a system one only (preinstalled or its update), never an arbitrary
     * third-party app the user did not pick for the microphone. Null when there is none.
     */
    private fun systemRecognizer(): ComponentName? = recognizers()
        .firstOrNull { (it.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0 }
        ?.let { ComponentName(it.packageName, it.name) }

    override fun listen(): Flow<SpeechEvent> {
        val onDevice = onDeviceAvailable()
        val explicit = if (!onDevice && stubIsDefault()) {
            systemRecognizer() ?: return flowOf(SpeechEvent.Error(UNAVAILABLE, retry = false))
        } else {
            null
        }
        return recognize(onDevice, explicit)
    }

    /** [explicit] null = the system default recogniser. */
    private fun recognize(onDevice: Boolean, explicit: ComponentName?): Flow<SpeechEvent> = callbackFlow {
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

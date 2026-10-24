package app.tsukaima.voice.live

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * The phone's audio routing for a live voice session: [acquire] puts the [AudioManager] in `MODE_IN_COMMUNICATION`,
 * routes the call audio to a headset when one is connected (Bluetooth SCO / LE, wired, USB) and to the loudspeaker
 * otherwise, and takes `AUDIOFOCUS_GAIN` as voice communication; [release] gives everything back as it was.
 * [onFocusLost] runs (on the main thread) when another app takes the focus (`AUDIOFOCUS_LOSS` or
 * `AUDIOFOCUS_LOSS_TRANSIENT`, a phone call for instance). Both calls are idempotent; neither throws.
 */
class LiveAudioRoute(context: Context, private val onFocusLost: () -> Unit) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var held = false
    private var previousMode = AudioManager.MODE_NORMAL
    private var previousSpeakerphone = false
    private var focus: AudioFocusRequest? = null

    /** Enters communication mode, picks the output device and requests the audio focus. */
    @Synchronized
    fun acquire() {
        if (held) return
        held = true
        previousMode = audioManager.mode
        @Suppress("DEPRECATION")
        previousSpeakerphone = audioManager.isSpeakerphoneOn
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        route()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    onFocusLost()
                }
            }
            .build()
        // The session goes ahead even if the system refuses the focus (as AudioFocus does for speech).
        if (audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focus = request
    }

    /** Restores the previous device, speakerphone state and mode, and abandons the focus. */
    @Synchronized
    fun release() {
        if (!held) return
        held = false
        focus?.let { audioManager.abandonAudioFocusRequest(it) }
        focus = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = previousSpeakerphone
        }
        audioManager.mode = previousMode
    }

    private fun route() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            // Preferred over the loudspeaker, in this order.
            val headsets = listOf(
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_USB_HEADSET,
            )
            val device = headsets.firstNotNullOfOrNull { type -> devices.firstOrNull { it.type == type } }
                ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (device == null) {
                Log.w(TAG, "Live voice: no communication device to route to")
                return
            }
            val ok = try {
                audioManager.setCommunicationDevice(device)
            } catch (e: IllegalArgumentException) {
                false
            }
            if (!ok) Log.w(TAG, "Live voice: could not route to device type ${device.type}")
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = !audioManager.isWiredHeadsetOn && !audioManager.isBluetoothScoOn
        }
    }

    private companion object {
        const val TAG = "LiveAudioRoute"
    }
}

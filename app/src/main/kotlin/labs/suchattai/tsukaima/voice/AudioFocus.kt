package labs.suchattai.tsukaima.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Transient, ducking audio focus held while the app speaks, so music lowers instead of talking over the voice.
 * Not reference counted: [request] while held is a no-op, [release] when not held is a no-op.
 */
class AudioFocus(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var held: AudioFocusRequest? = null

    /** Asks for `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`; speech goes ahead even if the system refuses. */
    @Synchronized
    fun request() {
        if (held != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { }
            .build()
        if (audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) held = request
    }

    /** Gives the focus back. */
    @Synchronized
    fun release() {
        val request = held ?: return
        held = null
        audioManager.abandonAudioFocusRequest(request)
    }
}

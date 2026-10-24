package app.tsukaima.voice.live

import android.content.Context
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * The process-wide libwebrtc entry point: [PeerConnectionFactory.initialize] runs once, and one audio-only
 * [PeerConnectionFactory] (no video encoders or decoders) is built lazily on a [JavaAudioDeviceModule] that asks for
 * the hardware echo canceller and noise suppressor. The factory and its audio device module live as long as the
 * process; each live session creates and disposes only its own peer connection, source and tracks.
 */
object WebRtc {
    private var factory: PeerConnectionFactory? = null

    /** The shared factory, initialising libwebrtc on the first call. Thread-safe and idempotent. */
    @Synchronized
    fun factory(context: Context): PeerConnectionFactory {
        factory?.let { return it }
        val app = context.applicationContext
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions(),
        )
        val adm = JavaAudioDeviceModule.builder(app)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        return PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .createPeerConnectionFactory()
            .also { factory = it }
    }
}

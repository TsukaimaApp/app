package app.tsukaima.domain.livevoice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/** The live session could not start (mic, SDP/ICE, HTTP, or no `session.started` in time). */
class LiveStartException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The seam the live voice controller drives; the Android implementation speaks WebRTC. */
interface LiveTransport {
    /** Data-channel events, parsed; completes when the transport is torn down. */
    val events: Flow<LiveEvent>

    /** Remote audio is above the speaking threshold. */
    val speaking: StateFlow<Boolean>

    /** Mic → offer → ICE → POST → answer. Throws [LiveStartException]. */
    suspend fun start(history: List<LiveHistoryMessage>)

    /** Sends a client event; false when the channel is not open. */
    fun send(event: JsonObject): Boolean

    /** Mutes or unmutes the mic track (the controller sends the `session.input_audio.mute`/`unmute` event). */
    fun setMicMuted(muted: Boolean)

    /** Idempotent teardown (no `session.close`; the controller sends that). */
    fun close()
}

package app.tsukaima.voice.live

import android.content.Context
import app.tsukaima.data.api.HermesJson
import app.tsukaima.domain.livevoice.LiveEvent
import app.tsukaima.domain.livevoice.LiveHistoryMessage
import app.tsukaima.domain.livevoice.LiveStartException
import app.tsukaima.domain.livevoice.LiveTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.AudioTrackSink
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [LiveTransport] on libwebrtc (`stream-webrtc-android`), a port of the desktop's `VoiceLiveSession` transport
 * (`apps/desktop/src/lib/voice-live.ts`). [start] runs the desktop's order: mic track with AEC/AGC/NS → data channel
 * `oai-events` **before** the offer → offer → local description → ICE gathering (≤ 10 s; trickle is fine) → the
 * untouched offer SDP to [exchange] (the `POST …/voice-live/session`, which returns the answer SDP) → remote
 * description. No ICE servers: host candidates over the LAN or Tailscale.
 *
 * The remote audio plays through the audio device module ([WebRtc]); an [AudioTrackSink] on it feeds [speaking].
 * Data-channel messages arrive on libwebrtc's threads and are handed to the single [events] collector through an
 * unbounded channel; the observers hold no lock and run no coroutine code. The connection failing or dropping, the
 * data channel closing, or the audio focus going away emits one [LiveEvent.Closed] (`connection_lost` /
 * `audio_focus_lost`) and completes [events]; the controller then calls [close].
 *
 * The transport never sends an event of its own: the controller sends `session.input_audio.mute`/`unmute` and
 * `session.close`. One [start] per instance.
 *
 * Threads: [start] runs the blocking native work (libwebrtc's first initialisation, the connect sequence) on
 * [Dispatchers.IO]; [close] returns at once and disposes the native objects on [releaser]. Every native call in the
 * connect sequence is made under [lock] right after checking [closed], so a concurrent [close] either comes first
 * (the start throws) or waits for that call to be issued (the calls are asynchronous, so the wait is short).
 */
class WebRtcLiveTransport(
    context: Context,
    private val exchange: suspend (sdp: String, history: JsonArray?) -> String,
) : LiveTransport {
    private val appContext = context.applicationContext
    private val route = LiveAudioRoute(appContext) { lost(AUDIO_FOCUS_LOST) }
    private val channel = Channel<LiveEvent>(Channel.UNLIMITED)
    private val _speaking = MutableStateFlow(false)

    /** Guards the native objects below against [close] racing [start] or [send]; never taken by a callback. */
    private val lock = Any()
    private var closed = false
    private var startCalled = false
    private var peer: PeerConnection? = null
    private var source: AudioSource? = null
    private var micTrack: AudioTrack? = null
    private var dataChannel: DataChannel? = null

    /** Runs [releaseNative] for [close] off the caller's thread; its one thread starts on the first [close]. */
    private val releaser: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "live-voice-release") }

    /** Set from libwebrtc's threads (no lock: [close] may be blocking in `peer.close()` while they run). */
    @Volatile private var remoteTrack: AudioTrack? = null
    private val sink = SpeakingProbe { _speaking.value = it }

    /** `connection_lost` is reported only once [start] has finished; a failure before that is [start]'s throw. */
    @Volatile private var live = false
    private val closedEmitted = AtomicBoolean(false)
    private val gathering = MutableStateFlow(PeerConnection.IceGatheringState.NEW)
    private val connection = MutableStateFlow(PeerConnection.PeerConnectionState.NEW)

    override val events: Flow<LiveEvent> = channel.receiveAsFlow()
    override val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    override suspend fun start(history: List<LiveHistoryMessage>) {
        withContext(Dispatchers.IO) {
            try {
                connect(history)
            } catch (e: Throwable) {
                // Tear down what was created; events stay open until close() (the controller reports start_failed).
                closedEmitted.set(true)
                releaseNative()
                when (e) {
                    is CancellationException, is LiveStartException -> throw e
                    else -> throw LiveStartException(e.message ?: "Live voice start failed", e)
                }
            }
        }
        live = true
        // A drop between setRemoteDescription and here would otherwise go unreported.
        if (connection.value.dropped) lost(CONNECTION_LOST)
    }

    private suspend fun connect(history: List<LiveHistoryMessage>) {
        // The first call in the process loads and initialises libwebrtc: outside the lock, so close() never waits.
        val factory = WebRtc.factory(appContext)
        val peer = synchronized(lock) {
            if (closed) throw LiveStartException("Live voice transport closed")
            if (startCalled) throw LiveStartException("Live voice session already started")
            startCalled = true
            route.acquire()
            val config = PeerConnection.RTCConfiguration(emptyList()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }
            val peer = factory.createPeerConnection(config, peerObserver)
                ?: throw LiveStartException("Could not create the peer connection")
            this.peer = peer
            // getUserMedia({ audio: { autoGainControl, echoCancellation, noiseSuppression } }) on the desktop.
            val source = factory.createAudioSource(
                MediaConstraints().apply {
                    listOf("googEchoCancellation", "googAutoGainControl", "googNoiseSuppression", "googHighpassFilter")
                        .forEach { mandatory.add(MediaConstraints.KeyValuePair(it, "true")) }
                },
            )
            this.source = source
            val track = factory.createAudioTrack(MIC_TRACK_ID, source)
            micTrack = track
            peer.addTrack(track, listOf(STREAM_ID))
            // Register the data channel before the offer so its m-line is negotiated.
            val events = peer.createDataChannel(EVENTS_CHANNEL, DataChannel.Init())
                ?: throw LiveStartException("Could not create the data channel")
            events.registerObserver(EventsObserver(events))
            dataChannel = events
            peer
        }
        val offer = peer.awaitOffer(
            MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            },
        )
        peer.awaitSet(offer, local = true)
        awaitIceGathering()
        val sdp = whileOpen { peer.localDescription?.description }
            ?: throw LiveStartException("Missing local SDP offer")
        val historyJson = history.takeIf { it.isNotEmpty() }?.let { HistoryJson.encodeToJsonElement(it).jsonArray }
        // Byte-exact: the SDP goes out as libwebrtc wrote it (CRLF line ends, trailing CRLF included).
        val answer = try {
            exchange(sdp, historyJson)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LiveStartException) {
            throw e
        } catch (e: Exception) {
            throw LiveStartException(e.message ?: "Live voice session creation failed", e)
        }
        peer.awaitSet(SessionDescription(SessionDescription.Type.ANSWER, answer), local = false)
    }

    /** `waitForIceGathering`: complete, or [ICE_GATHER_TIMEOUT_MS] (then go on with what there is), or failed. */
    private suspend fun awaitIceGathering() {
        val failed = withTimeoutOrNull(ICE_GATHER_TIMEOUT_MS) {
            combine(gathering, connection) { g, c ->
                when {
                    c == PeerConnection.PeerConnectionState.FAILED -> true
                    g == PeerConnection.IceGatheringState.COMPLETE -> false
                    else -> null
                }
            }.first { it != null }
        }
        if (failed == true) throw LiveStartException("WebRTC connection failed")
    }

    /** [block] (a native call) under [lock], unless [close] came first: then the start fails. */
    private inline fun <T> whileOpen(block: () -> T): T = synchronized(lock) {
        if (closed) throw LiveStartException("Live voice transport closed")
        block()
    }

    /**
     * Never called after [close] (the controller guards with `ended`/`closing`): [releaseNative] holds [lock] through
     * the blocking `peer.close()`, so a late call here would wait for it on the caller's (main) thread.
     */
    override fun send(event: JsonObject): Boolean = synchronized(lock) {
        val events = dataChannel ?: return false
        if (closed || events.state() != DataChannel.State.OPEN) return false
        events.send(DataChannel.Buffer(ByteBuffer.wrap(event.toString().toByteArray(Charsets.UTF_8)), false))
    }

    /**
     * The track only: the controller sends the `session.input_audio.mute`/`unmute` event itself. Like [send], never
     * called after [close] (it would wait on [lock] through `peer.close()`).
     */
    override fun setMicMuted(muted: Boolean) {
        synchronized(lock) { micTrack?.setEnabled(!muted) }
    }

    /**
     * Returns at once: the audio route is given back here (cheap, and before any next session takes it), the native
     * objects are disposed on [releaser] (`peer.close()` blocks while libwebrtc winds down), which then shuts down.
     */
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        closedEmitted.set(true)
        route.release()
        _speaking.value = false
        releaser.execute(::releaseNative)
        releaser.shutdown()
        channel.close()
    }

    /**
     * Disposes the native objects in libwebrtc's order (data channel, peer — which disposes its senders, receivers
     * and the remote track — then our mic track and source), drops the speaking indicator and restores the audio
     * route. Idempotent; never called from a libwebrtc callback.
     */
    private fun releaseNative() {
        synchronized(lock) {
            remoteTrack?.let { track -> runCatching { track.removeSink(sink) } }
            remoteTrack = null
            dataChannel?.let { events ->
                events.unregisterObserver()
                events.close()
                events.dispose()
            }
            dataChannel = null
            peer?.let { peer ->
                peer.close()
                peer.dispose()
            }
            peer = null
            micTrack?.let { track ->
                track.setEnabled(false)
                track.dispose()
            }
            micTrack = null
            source?.dispose()
            source = null
            route.release()
        }
        _speaking.value = false
    }

    /** `finish('connection_lost' | 'audio_focus_lost')`: one Closed event, then [events] completes. */
    private fun lost(reason: String) {
        if (!closedEmitted.compareAndSet(false, true)) return
        channel.trySend(LiveEvent.Closed(reason, null))
        channel.close()
    }

    private inner class EventsObserver(private val events: DataChannel) : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit

        override fun onStateChange() {
            if (live && events.state() == DataChannel.State.CLOSED) lost(CONNECTION_LOST)
        }

        override fun onMessage(buffer: DataChannel.Buffer) {
            if (buffer.binary) return
            val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
            LiveEvent.parse(String(bytes, Charsets.UTF_8))?.let { channel.trySend(it) }
        }
    }

    private val peerObserver = object : PeerConnection.Observer {
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            connection.value = newState
            if (live && newState.dropped) lost(CONNECTION_LOST)
        }

        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
            gathering.value = newState
        }

        /** The desktop's `track` listener; playback is the ADM's, so only the speaking probe is armed here. */
        override fun onTrack(transceiver: RtpTransceiver) {
            val track = transceiver.receiver?.track() as? AudioTrack ?: return
            if (remoteTrack != null) return
            try {
                track.addSink(sink)
                remoteTrack = track
            } catch (e: RuntimeException) {
                // No sink → no speaking indicator; the conversation still works.
            }
        }

        override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceCandidate(candidate: IceCandidate) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(dataChannel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
    }

    /**
     * `armSpeakingProbe` on PCM instead of an analyser: the peak |sample| of each 100 ms window, loud above
     * [LOUD_PEAK] (the desktop's 6 of 128 on the byte scale, ≈ 1,536 of 32,767), speaking while loud or fewer than
     * [QUIET_WINDOWS] quiet windows since. Runs on libwebrtc's audio thread; [publish] must be thread-safe.
     */
    private class SpeakingProbe(private val publish: (Boolean) -> Unit) : AudioTrackSink {
        private var peak = 0
        private var frames = 0

        // The desktop starts at 0 quiet frames, which reads as "speaking" for the first 300 ms of silence; start
        // as already quiet instead so the indicator only lights on real audio.
        private var quietWindows = QUIET_WINDOWS
        private var lastSpeaking = false

        override fun onData(
            audioData: ByteBuffer,
            bitsPerSample: Int,
            sampleRate: Int,
            numberOfChannels: Int,
            numberOfFrames: Int,
            absoluteCaptureTimestampMs: Long,
        ) {
            if (bitsPerSample != 16 || sampleRate <= 0 || numberOfChannels <= 0) return
            val samples = audioData.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
            val count = minOf(samples.remaining(), numberOfFrames * numberOfChannels)
            for (i in 0 until count) {
                val magnitude = kotlin.math.abs(samples.get(i).toInt())
                if (magnitude > peak) peak = magnitude
            }
            frames += numberOfFrames
            if (frames < sampleRate / WINDOWS_PER_SECOND) return
            val loud = peak > LOUD_PEAK
            quietWindows = if (loud) 0 else quietWindows + 1
            val speaking = loud || quietWindows < QUIET_WINDOWS
            peak = 0
            frames = 0
            if (speaking != lastSpeaking) {
                lastSpeaking = speaking
                publish(speaking)
            }
        }
    }

    private companion object {
        const val ICE_GATHER_TIMEOUT_MS = 10_000L
        const val MIC_TRACK_ID = "tsukaima-mic"
        const val STREAM_ID = "tsukaima"
        const val EVENTS_CHANNEL = "oai-events"
        const val CONNECTION_LOST = "connection_lost"
        const val AUDIO_FOCUS_LOST = "audio_focus_lost"
        const val LOUD_PEAK = 1_536
        const val QUIET_WINDOWS = 4
        const val WINDOWS_PER_SECOND = 10

        /** The desktop's `connectionstatechange` → `failed` | `disconnected`. */
        val PeerConnection.PeerConnectionState.dropped: Boolean
            get() = this == PeerConnection.PeerConnectionState.FAILED ||
                this == PeerConnection.PeerConnectionState.DISCONNECTED

        /** HermesJson drops defaults; the vendor needs every history item's `"type": "message"`. */
        val HistoryJson = Json(HermesJson) { encodeDefaults = true }
    }

    private suspend fun PeerConnection.awaitOffer(constraints: MediaConstraints): SessionDescription =
        suspendCancellableCoroutine { cont ->
            val observer = object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) = cont.resume(sdp)
                override fun onCreateFailure(error: String?) =
                    cont.resumeWithException(LiveStartException("createOffer failed: $error"))
                override fun onSetSuccess() = Unit
                override fun onSetFailure(error: String?) = Unit
            }
            whileOpen { createOffer(observer, constraints) }
        }

    private suspend fun PeerConnection.awaitSet(description: SessionDescription, local: Boolean): Unit =
        suspendCancellableCoroutine { cont ->
            val observer = object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) = Unit
                override fun onCreateFailure(error: String?) = Unit
                override fun onSetSuccess() = cont.resume(Unit)
                override fun onSetFailure(error: String?) {
                    val which = if (local) "setLocalDescription" else "setRemoteDescription"
                    cont.resumeWithException(LiveStartException("$which failed: $error"))
                }
            }
            whileOpen {
                if (local) setLocalDescription(observer, description) else setRemoteDescription(observer, description)
            }
        }
}

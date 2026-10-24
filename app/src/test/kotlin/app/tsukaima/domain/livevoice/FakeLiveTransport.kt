package app.tsukaima.domain.livevoice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject

/**
 * A scripted live transport: the test [emit]s server events, sets [speaking], and reads what the controller [sent].
 * [startResult] is what [start] does (a failure throws its exception); [channelOpen] false makes [send] refuse.
 */
internal class FakeLiveTransport : LiveTransport {
    val eventFlow = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 64)
    override val events: Flow<LiveEvent> get() = eventFlow
    override val speaking = MutableStateFlow(false)

    var startResult: Result<Unit> = Result.success(Unit)
    var channelOpen = true
    val sent = mutableListOf<JsonObject>()
    var startedWith: List<LiveHistoryMessage>? = null
        private set
    var closed = false
        private set
    var muted = false
        private set

    fun emit(e: LiveEvent) = check(eventFlow.tryEmit(e)) { "event buffer full" }

    /** The `type` of every event sent, in order. */
    val sentTypes: List<String> get() = sent.map { it["type"].toString().trim('"') }

    override suspend fun start(history: List<LiveHistoryMessage>) {
        startedWith = history
        startResult.getOrThrow()
    }

    override fun send(event: JsonObject): Boolean {
        if (!channelOpen || closed) return false
        sent += event
        return true
    }

    override fun setMicMuted(muted: Boolean) {
        this.muted = muted
    }

    override fun close() {
        closed = true
    }
}

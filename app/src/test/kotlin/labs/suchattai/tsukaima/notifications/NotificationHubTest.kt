package labs.suchattai.tsukaima.notifications

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.plus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import labs.suchattai.tsukaima.data.HermesState
import labs.suchattai.tsukaima.data.api.CronJobDto
import labs.suchattai.tsukaima.data.api.PairingDto
import labs.suchattai.tsukaima.data.gateway.Frame
import labs.suchattai.tsukaima.data.gateway.RpcTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationHubTest {
    private class FakePoster : NotificationPoster {
        val posted = mutableListOf<NotificationSpec>()
        val cancelled = mutableListOf<Int>()
        override fun post(spec: NotificationSpec) {
            posted += spec
        }
        override fun cancel(id: Int) {
            cancelled += id
        }
    }

    private class FakeTransport(var live: Boolean = true) : RpcTransport {
        val responses = mutableListOf<Pair<String, JsonObject>>()
        override suspend fun request(method: String, params: JsonObject): JsonObject = JsonObject(emptyMap())
        override fun respond(id: String, result: JsonObject): Boolean {
            if (live) responses += id to result
            return live
        }
    }

    private fun TestScope.hub(
        requests: MutableSharedFlow<Frame.ServerRequest>,
        answered: AnsweredRequestIds,
        poster: FakePoster,
        job: Job,
        foreground: () -> Boolean,
    ) = NotificationHub(
        scope = this + job,
        serverRequests = requests,
        events = MutableSharedFlow(),
        hermes = MutableStateFlow(HermesState()),
        answered = answered,
        cronJobs = { Result.success(emptyList()) },
        pairing = { Result.success(PairingDto()) },
        poster = poster,
        foreground = foreground,
    )

    @Test
    fun openApprovalSeenInForegroundIsNotifiedOnBackground() = runTest(UnconfinedTestDispatcher()) {
        val requests = MutableSharedFlow<Frame.ServerRequest>(replay = 16)
        val answered = AnsweredRequestIds()
        val poster = FakePoster()
        var foreground = true
        val job = Job()
        val hub = hub(requests, answered, poster, job) { foreground }
        hub.start()
        requests.emit(approval("srq-1"))
        requests.emit(approval("srq-2"))
        answered.add("srq-2") // chat answered this one before the user left
        assertTrue("suppressed in the foreground", poster.posted.isEmpty())

        foreground = false
        hub.onBackground()
        assertEquals(listOf("srq-1".hashCode()), poster.posted.map { it.id })
        hub.onBackground() // a second hand-off does not repeat it
        assertEquals(1, poster.posted.size)

        // The background connection ends: the dead buttons go, and the next hand-off may notify again.
        hub.onConnectionEnded()
        assertTrue("srq-1".hashCode() in poster.cancelled)
        hub.onBackground()
        assertEquals(2, poster.posted.size)
        job.cancelAndJoin()
    }

    @Test
    fun respondNotEnqueuedReArmsAndReposts() = runTest(UnconfinedTestDispatcher()) {
        val requests = MutableSharedFlow<Frame.ServerRequest>(replay = 16)
        val answered = AnsweredRequestIds()
        val poster = FakePoster()
        val job = Job()
        val hub = hub(requests, answered, poster, job) { false }
        hub.start()
        requests.emit(approval("srq-1"))
        val transport = FakeTransport(live = false)
        val answerer = ApprovalAnswerer(transport, answered, { true }, hub::cancel, hub::repostApproval)

        assertEquals(ApprovalAnswerer.Outcome.NotSent, answerer.answer("srq-1", "deny"))
        assertFalse("claim released: it can be answered again", "srq-1" in answered)
        assertEquals(listOf("srq-1".hashCode(), "srq-1".hashCode()), poster.posted.map { it.id }) // re-posted
        assertEquals(poster.posted[0], poster.posted[1])

        transport.live = true
        assertEquals(ApprovalAnswerer.Outcome.Sent, answerer.answer("srq-1", "deny"))
        assertTrue("srq-1" in answered)
        assertEquals(2, poster.posted.size)
        job.cancelAndJoin()
    }

    private fun approval(id: String) = Frame.ServerRequest(id, "approval", buildJsonObject { put("command", "ls") })

    @Test
    fun answeredElsewhereCancelsNotificationAndBackgroundPollsCron() = runTest(UnconfinedTestDispatcher()) {
        val requests = MutableSharedFlow<Frame.ServerRequest>(replay = 16)
        val events = MutableSharedFlow<Frame.Event>()
        val hermes = MutableStateFlow(HermesState())
        val answered = AnsweredRequestIds()
        val poster = FakePoster()
        var foreground = false
        var cronStatus = "ok"
        val job = Job()
        val hub = NotificationHub(
            scope = this + job,
            serverRequests = requests,
            events = events,
            hermes = hermes,
            answered = answered,
            cronJobs = { Result.success(listOf(CronJobDto(id = "j", name = "backup", lastStatus = cronStatus))) },
            pairing = { Result.success(PairingDto()) },
            poster = poster,
            foreground = { foreground },
            pollMs = 60_000,
        )
        hub.start()
        requests.emit(approval("srq-1"))
        assertEquals(listOf("srq-1".hashCode()), poster.posted.map { it.id })

        assertTrue(answered.add("srq-1")) // chat answered it
        assertEquals(listOf("srq-1".hashCode()), poster.cancelled)

        hub.startBackground()
        runCurrent()
        cronStatus = "error"
        advanceTimeBy(60_001)
        assertEquals(ApprovalNotifier.cronId("j"), poster.posted.last().id)
        hub.stopBackground()

        foreground = true
        requests.emit(approval("srq-2"))
        assertEquals(2, poster.posted.size)
        job.cancelAndJoin()
    }

    @Test
    fun approvalAnsweredOnceBySrqId() {
        val transport = FakeTransport()
        val answered = AnsweredRequestIds()
        val cancelled = mutableListOf<Int>()
        var connected = true
        val answerer = ApprovalAnswerer(transport, answered, connected = { connected }, cancel = { cancelled += it })

        connected = false
        assertEquals(ApprovalAnswerer.Outcome.NotConnected, answerer.answer("srq-1", "once"))
        assertTrue(transport.responses.isEmpty())
        connected = true
        assertEquals(ApprovalAnswerer.Outcome.Invalid, answerer.answer("srq-1", "always"))
        assertEquals(ApprovalAnswerer.Outcome.Invalid, answerer.answer(null, "once"))
        assertEquals(ApprovalAnswerer.Outcome.Invalid, answerer.answer("pending-r1", "once"))

        assertEquals(ApprovalAnswerer.Outcome.Sent, answerer.answer("srq-1", "once"))
        assertEquals(ApprovalAnswerer.Outcome.AlreadyAnswered, answerer.answer("srq-1", "deny"))
        assertEquals(1, transport.responses.size)
        assertEquals("srq-1", transport.responses[0].first)
        assertEquals("\"once\"", transport.responses[0].second["choice"].toString())
        assertTrue("srq-1".hashCode() in cancelled)
    }
}

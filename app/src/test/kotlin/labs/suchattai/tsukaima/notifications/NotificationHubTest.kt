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

    private class FakeTransport : RpcTransport {
        val responses = mutableListOf<Pair<String, JsonObject>>()
        override suspend fun request(method: String, params: JsonObject): JsonObject = JsonObject(emptyMap())
        override fun respond(id: String, result: JsonObject) {
            responses += id to result
        }
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

package labs.suchattai.tsukaima.notifications

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import labs.suchattai.tsukaima.data.RecentIds
import labs.suchattai.tsukaima.data.api.CronJobDto
import labs.suchattai.tsukaima.data.gateway.Frame
import labs.suchattai.tsukaima.domain.chat.AgentStatus
import labs.suchattai.tsukaima.notifications.NotificationCommand.Cancel
import labs.suchattai.tsukaima.notifications.NotificationCommand.Post
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class NotificationCoordinatorTest {
    private val answered: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val c = NotificationCoordinator(answered)

    private fun approval(id: String, requestId: String = "r-$id") = Frame.ServerRequest(
        id, "approval",
        buildJsonObject {
            put("session_id", "live-1")
            put("request_id", requestId)
            put("command", "ls")
        },
    )

    private fun event(type: String, session: String = "live-1", payload: JsonObject = JsonObject(emptyMap())) =
        Frame.Event(type, session, null, payload)

    @Test
    fun alreadyAnsweredRequestNotNotified() {
        answered += "srq-1"
        assertNull(c.onServerRequest(approval("srq-1"), foreground = false))
        // A fresh one is notified once; the replay of the same id is not.
        assertNotNull(c.onServerRequest(approval("srq-2"), foreground = false))
        assertNull(c.onServerRequest(approval("srq-2"), foreground = false))
        // Other server requests are never notified.
        assertNull(c.onServerRequest(Frame.ServerRequest("srq-3", "vault.code", JsonObject(emptyMap())), foreground = false))
    }

    @Test
    fun foregroundSuppressesApprovalNotifications() {
        assertNull(c.onServerRequest(approval("srq-1"), foreground = true, visibleSession = "live-1"))
        val pairing = listOf(
            buildJsonObject {
                put("platform", "telegram")
                put("request_id", "p-1")
                put("user_name", "alice")
            },
        )
        assertTrue(c.onPairing(pairing, foreground = true).isEmpty())
        // Task-done only for turns that finish while backgrounded.
        assertTrue(c.onEvent(event("message.complete"), foreground = true).isEmpty())
        // Gateway alerts track the transition but stay quiet in the foreground.
        assertNull(c.onGatewayStatus(AgentStatus.Stopped, foreground = true))

        assertNotNull(c.onServerRequest(approval("srq-4"), foreground = false))
        assertEquals(1, c.onPairing(pairing, foreground = false).filterIsInstance<Post>().size)
    }

    @Test
    fun notifiedIdsAreBoundedLru() {
        val ids = RecentIds(capacity = 3)
        assertTrue(ids.add("a"))
        assertFalse(ids.add("a"))
        assertTrue(ids.add("b"))
        assertTrue(ids.add("c"))
        assertTrue(ids.add("d")) // evicts "a"
        assertTrue(ids.add("a"))
        assertFalse(ids.add("d"))
        assertEquals(3, ids.size)
        assertEquals(64, RecentIds().capacity)
    }

    @Test
    fun gatewayNotificationOnlyOnTransition() {
        assertNull(c.onGatewayStatus(null, foreground = false))
        val first = c.onGatewayStatus(AgentStatus.Offline, foreground = false)
        assertTrue(first is Post)
        assertEquals("Can’t reach Hermes", (first as Post).spec.title)
        assertNull(c.onGatewayStatus(AgentStatus.Offline, foreground = false)) // never repeatedly
        assertNull(c.onGatewayStatus(AgentStatus.Offline, foreground = false))
        assertTrue(c.onGatewayStatus(AgentStatus.Stopped, foreground = false) is Post)
        assertEquals(Cancel(ApprovalNotifier.GATEWAY_ID), c.onGatewayStatus(null, foreground = false))
        assertNull(c.onGatewayStatus(null, foreground = false))
        assertTrue(c.onGatewayStatus(AgentStatus.Stopped, foreground = false) is Post)
    }

    @Test
    fun cronFailureDetectedOnStatusChange() {
        fun job(id: String, status: String?, run: String? = "t1") =
            CronJobDto(id = id, name = "job $id", lastStatus = status, lastRunAt = run)

        // First poll seeds: existing failures are old news.
        assertTrue(c.onCronJobs(listOf(job("a", "ok"), job("b", "error"))).isEmpty())
        assertTrue(c.onCronJobs(listOf(job("a", "ok"), job("b", "error"))).isEmpty())
        val failed = c.onCronJobs(listOf(job("a", "FAILED", "t2"), job("b", "error")))
        assertEquals(listOf(ApprovalNotifier.cronId("a")), failed.map { it.id })
        assertTrue(failed.single().text.contains("job a"))
        // Unchanged failure: no repeat. Recovery then failure again: notified.
        assertTrue(c.onCronJobs(listOf(job("a", "FAILED", "t2"), job("b", "error"))).isEmpty())
        assertTrue(c.onCronJobs(listOf(job("a", "ok", "t3"), job("b", "error"))).isEmpty())
        assertEquals(1, c.onCronJobs(listOf(job("a", "Failure", "t4"), job("b", "error"))).size)
        // Reset (next background session) re-seeds.
        c.resetCron()
        assertTrue(c.onCronJobs(listOf(job("c", "error"))).isEmpty())
    }

    @Test
    fun cancelsWhenRequestCancelledOrApprovalWithdrawn() {
        c.onServerRequest(approval("srq-1", requestId = "r-a"), foreground = false)
        c.onServerRequest(approval("srq-2", requestId = "r-b"), foreground = false)
        assertEquals(
            listOf(Cancel("srq-1".hashCode())),
            c.onEvent(event("request.cancel", payload = buildJsonObject { put("id", "srq-1") }), foreground = false),
        )
        val withdrawn = event(
            "approval.cancelled",
            payload = JsonObject(mapOf("request_ids" to JsonArray(listOf(JsonPrimitive("r-b"))))),
        )
        assertEquals(listOf(Cancel("srq-2".hashCode())), c.onEvent(withdrawn, foreground = false))
        assertEquals(Cancel("srq-9".hashCode()), c.onAnswered("srq-9"))
    }

    @Test
    fun taskDoneUsesTitleFromSessionTitleEvent() {
        c.onEvent(event("session.title", payload = buildJsonObject {
            put("session_id", "stored-1")
            put("title", "Deploy blog")
        }), foreground = true)
        val out = c.onEvent(event("message.complete"), foreground = false)
        assertEquals("Deploy blog", (out.single() as Post).spec.title)
    }

    @Test
    fun errorCompletionNotNotifiedAsDone() {
        val failed = buildJsonObject {
            put("text", "")
            put("status", "error")
            put("error", "boom")
        }
        assertTrue(c.onEvent(event("message.complete", payload = failed), foreground = false).isEmpty())
        assertTrue(c.onEvent(event("error", payload = buildJsonObject { put("message", "boom") }), foreground = false).isEmpty())
        assertEquals(1, c.onEvent(event("message.complete"), foreground = false).size)
    }

    @Test
    fun approvalNotificationOpensTheSessionThatAsked() {
        val bound = NotificationCoordinator(answered, storedFor = { if (it == "live-1") "stored-1" else null })
        assertEquals("stored-1", bound.onServerRequest(approval("srq-1"), foreground = false)!!.sessionId)
        // Unknown to the chat binding: learned from the session's own title event (payload names the stored id).
        val learned = NotificationCoordinator(answered)
        learned.onEvent(event("session.title", payload = buildJsonObject {
            put("session_id", "stored-9")
            put("title", "t")
        }), foreground = true)
        assertEquals("stored-9", learned.onServerRequest(approval("srq-2"), foreground = false)!!.sessionId)
        assertNull(NotificationCoordinator(answered).onServerRequest(approval("srq-3"), foreground = false)!!.sessionId)
    }

    @Test
    fun foregroundApprovalForOtherSessionIsNotified() {
        // In the foreground, only the approval for the chat on screen is left to that chat.
        assertNull(c.onServerRequest(approval("srq-1"), foreground = true, visibleSession = "live-1"))
        assertNotNull(c.onServerRequest(approval("srq-2"), foreground = true, visibleSession = "live-other"))
        assertNotNull(c.onServerRequest(approval("srq-3"), foreground = true, visibleSession = null))
    }

    @Test
    fun pairingNotifiedOnceAndCancelledWhenGone() {
        val p1 = buildJsonObject {
            put("platform", "telegram")
            put("request_id", "p-1")
            put("user_name", "alice")
        }
        assertEquals(1, c.onPairing(listOf(p1), foreground = false).size)
        assertTrue(c.onPairing(listOf(p1), foreground = false).isEmpty())
        assertEquals(
            listOf(Cancel(ApprovalNotifier.pairingId("telegram", "p-1"))),
            c.onPairing(emptyList(), foreground = false),
        )
    }

    @Test
    fun connectionEndedCancelsPostedApprovalsAndAllowsRenotify() {
        assertNotNull(c.onServerRequest(approval("srq-1"), foreground = false))
        assertEquals(listOf(Cancel("srq-1".hashCode())), c.onConnectionEnded())
        assertTrue(c.onConnectionEnded().isEmpty())
        assertEquals(listOf("srq-1".hashCode()), c.onBackground(listOf(approval("srq-1"))).map { it.id })
        assertNotNull(c.approvalSpec("srq-1"))
    }

    @Test
    fun requestCancelledInForegroundNotReplayedOnBackground() {
        val req = approval("srq-1")
        assertNull(c.onServerRequest(req, foreground = true, visibleSession = "live-1"))
        c.onEvent(event("request.cancel", payload = buildJsonObject { put("id", "srq-1") }), foreground = true)
        assertTrue(c.onBackground(listOf(req)).isEmpty())
        assertNull(c.onServerRequest(req, foreground = false))
    }

    @Test
    fun approvalCancelledInForegroundNotReplayedOnBackground() {
        val req = approval("srq-2", requestId = "r-x")
        val other = approval("srq-3", requestId = "r-y")
        assertNull(c.onServerRequest(req, foreground = true, visibleSession = "live-1"))
        assertNull(c.onServerRequest(other, foreground = true, visibleSession = "live-1"))
        val withdrawn = event(
            "approval.cancelled",
            payload = JsonObject(mapOf("request_ids" to JsonArray(listOf(JsonPrimitive("r-x"))))),
        )
        c.onEvent(withdrawn, foreground = true)
        // Only the withdrawn one stays quiet; the still-open one is notified on the hand-off.
        assertEquals(listOf("srq-3".hashCode()), c.onBackground(listOf(req, other)).map { it.id })
    }
    private fun clarify(id: String) = Frame.ServerRequest(
        id, "clarify",
        buildJsonObject {
            put("session_id", "live-1")
            put("questions", JsonArray(listOf(buildJsonObject { put("qid", "q1"); put("question", "Colour?") })))
        },
    )

    @Test
    fun clarifyNotifiedLikeApprovals() {
        // The chat showing that session handles it in the foreground.
        assertNull(c.onServerRequest(clarify("srq-q"), foreground = true, visibleSession = "live-1"))
        val spec = c.onServerRequest(clarify("srq-q"), foreground = false)
        assertEquals("Hermes has a question", spec?.title)
        assertNull("once per id", c.onServerRequest(clarify("srq-q"), foreground = false))
        // Withdrawn → cancelled and never notified again.
        val cancel = c.onEvent(event("request.cancel", payload = buildJsonObject { put("id", "srq-r") }), foreground = false)
        assertEquals(listOf(Cancel("srq-r".hashCode())), cancel)
        assertNull(c.onServerRequest(clarify("srq-r"), foreground = false))
        // Answered from chat → its notification goes.
        assertEquals(Cancel("srq-q".hashCode()), c.onAnswered("srq-q"))
    }

    @Test
    fun maskedPromptsNotifiedOnceAndClosedByRequestCancel() {
        val sudo = Frame.ServerRequest("srq-u", "sudo", buildJsonObject { put("session_id", "live-1"); put("command", "sudo -v") })
        assertNull("the chat on screen shows it", c.onServerRequest(sudo, foreground = true, visibleSession = "live-1"))
        val spec = c.onServerRequest(sudo, foreground = false)
        assertEquals("Hermes needs a password", spec!!.title)
        assertNull("once per id", c.onServerRequest(sudo, foreground = false))
        val cancel = c.onEvent(event("request.cancel", payload = buildJsonObject { put("id", "srq-s") }), foreground = false)
        assertEquals(listOf(Cancel("srq-s".hashCode())), cancel)
        val secret = Frame.ServerRequest("srq-s", "secret", buildJsonObject { put("session_id", "live-1"); put("env_var", "K") })
        assertNull("withdrawn before it was seen: never notified", c.onServerRequest(secret, foreground = false))
    }
}

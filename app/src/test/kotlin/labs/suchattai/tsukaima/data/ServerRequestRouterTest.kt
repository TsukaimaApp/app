package labs.suchattai.tsukaima.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import labs.suchattai.tsukaima.data.api.HermesJson
import labs.suchattai.tsukaima.data.gateway.Frame
import labs.suchattai.tsukaima.data.gateway.RpcTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerRequestRouterTest {
    private class FakeTransport : RpcTransport {
        val results = mutableListOf<Pair<String, JsonObject>>()
        val errors = mutableListOf<Triple<String, Int, String>>()
        override suspend fun request(method: String, params: JsonObject): JsonObject = JsonObject(emptyMap())
        override fun respond(id: String, result: JsonObject): Boolean {
            results += id to result
            return true
        }
        override fun respondError(id: String, code: Int, message: String): Boolean {
            errors += Triple(id, code, message)
            return true
        }
    }

    private val transport = FakeTransport()
    private val router = ServerRequestRouter(transport)

    private fun req(id: String, method: String, params: JsonObject = JsonObject(emptyMap())) =
        Frame.ServerRequest(id, method, params)

    @Test
    fun clarifyForwardedNotAutoCancelled() {
        val params = buildJsonObject {
            put("session_id", "live-1")
            put("questions", HermesJson.parseToJsonElement("""[{"qid":"q1","question":"Colour?","choices":["red"]}]"""))
        }
        assertTrue(router.route(req("srq-c", "clarify", params)))
        assertTrue("the user answers it", transport.results.isEmpty())
        assertTrue(transport.errors.isEmpty())
    }

    @Test
    fun clarifyWithoutQuestionsCancelledLikeTheTui() {
        // No askable question: the TUI answers `{}` (cancel-all) at once instead of leaving the agent waiting.
        assertFalse(router.route(req("srq-e", "clarify", buildJsonObject { put("session_id", "live-1") })))
        assertEquals(listOf("srq-e" to JsonObject(emptyMap())), transport.results)
    }

    @Test
    fun previewDeclinedWithNotShown() {
        for (method in listOf("preview.read", "preview.act", "terminal.read", "window.read", "tour")) {
            assertFalse(method, router.route(req("srq-$method", method)))
        }
        assertEquals(5, transport.errors.size)
        assertTrue(transport.errors.all { it.second == ServerRequestRouter.NOT_SHOWN_CODE })
        assertEquals(4404, ServerRequestRouter.NOT_SHOWN_CODE)
        assertEquals("srq-tour", transport.errors.last().first)
        assertTrue(transport.results.isEmpty())
    }

    @Test
    fun secretAndSudoRejected() {
        assertFalse(router.route(req("srq-s", "secret")))
        assertFalse(router.route(req("srq-u", "sudo")))
        assertEquals(
            listOf(Triple("srq-s", -32601, "not supported on mobile"), Triple("srq-u", -32601, "not supported on mobile")),
            transport.errors,
        )
        assertTrue(transport.results.isEmpty())
    }

    @Test
    fun approvalForwardedUnanswered() {
        assertTrue(router.route(req("srq-a", "approval")))
        assertTrue(transport.results.isEmpty())
        assertTrue(transport.errors.isEmpty())
    }

    @Test
    fun withdrawnRequestsRecordedAsClosed() {
        router.route(req("srq-a", "approval", buildJsonObject { put("request_id", "r-a") }))
        router.route(req("srq-b", "approval", buildJsonObject { put("request_id", "r-b") }))
        router.onEvent(Frame.Event("request.cancel", "live-1", 1, buildJsonObject { put("id", "srq-a") }))
        router.onEvent(
            Frame.Event("approval.cancelled", "live-1", 2, JsonObject(mapOf("request_ids" to JsonArray(listOf(JsonPrimitive("r-b")))))),
        )
        assertTrue(router.closed.contains("srq-a"))
        assertTrue(router.closed.contains("srq-b"))
        assertFalse(router.closed.contains("srq-c"))
    }
}

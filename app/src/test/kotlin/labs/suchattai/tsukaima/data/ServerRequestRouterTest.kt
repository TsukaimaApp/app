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
    fun maskedPromptsForwardedUnanswered() {
        // sudo / secret / vault unlock wait for the masked card in chat (v0.4.0; v0.3.x answered -32601).
        assertTrue(router.route(req("srq-u", "sudo", buildJsonObject { put("command", "sudo -v") })))
        assertTrue(router.route(req("srq-s", "secret", buildJsonObject { put("env_var", "API_KEY"); put("prompt", "Key?") })))
        assertTrue(router.route(req("srq-v", "vault.unlock_prompt", buildJsonObject { put("backend", "bw"); put("display_name", "Bitwarden") })))
        assertTrue(transport.results.isEmpty())
        assertTrue(transport.errors.isEmpty())
    }

    @Test
    fun saveLoginDeclinedAsNotShownSoADesktopWindowCanStillAnswer() {
        assertFalse(router.route(req("srq-l", "vault.save_login", buildJsonObject { put("origin", "https://x"); put("site", "x") })))
        assertEquals(listOf(Triple("srq-l", 4404, ServerRequestRouter.VAULT_NOT_SHOWN_MESSAGE)), transport.errors)
        assertTrue(transport.results.isEmpty())
    }

    @Test
    fun oneTimeCodeAnsweredAsNoCodeEntered() {
        // Not 4404: as the only client, Hermes would pass its refusal JSON to the browser tool as the code.
        assertFalse(router.route(req("srq-c", "vault.code", buildJsonObject { put("site", "x") })))
        assertEquals(1, transport.results.size)
        assertEquals("srq-c", transport.results.single().first)
        assertEquals(buildJsonObject { put("value", "") }, transport.results.single().second)
        assertTrue(transport.errors.isEmpty())
    }

    @Test
    fun unknownMethodsRejected() {
        assertFalse(router.route(req("srq-x", "nope")))
        assertEquals(listOf(Triple("srq-x", -32601, "not supported on mobile")), transport.errors)
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

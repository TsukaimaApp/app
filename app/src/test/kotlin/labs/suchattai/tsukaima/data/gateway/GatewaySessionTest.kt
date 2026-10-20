package labs.suchattai.tsukaima.data.gateway

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import labs.suchattai.tsukaima.data.api.HermesJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class GatewaySessionTest {
    private class FakeTransport(var result: JsonObject = JsonObject(emptyMap())) : RpcTransport {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val responses = mutableListOf<Pair<String, JsonObject>>()
        override suspend fun request(method: String, params: JsonObject): JsonObject {
            calls += method to params
            return result
        }
        override fun respond(id: String, result: JsonObject): Boolean {
            responses += id to result
            return true
        }
    }

    private fun obj(s: String) = HermesJson.parseToJsonElement(s).jsonObject

    private val historyJson = """
        {"session_id":"d4fb0dea","stored_session_id":"20261020_101500_ab12cd","resumed":true,"running":false,
         "info":{"model":"m"},
         "messages":[
          {"role":"user","text":"what os?","timestamp":1760955300.5,"row_id":1},
          {"role":"assistant","text":"","reasoning":"I should run uname","timestamp":1760955301.0,"row_id":2},
          {"role":"tool","text":"","name":"terminal","context":"uname -a","args":{"command":"uname -a"},"tool_call_id":"call_1","row_id":3},
          {"role":"assistant","text":"Linux.","row_id":4}
         ]}
    """.trimIndent()

    @Test
    fun createSendsSourceAndCwd() = runBlocking {
        val t = FakeTransport(obj("""{"session_id":"s1","stored_session_id":"st1","info":{},"messages":[]}"""))
        val created = GatewaySession(t).create("/home/x", null)
        val (method, params) = t.calls.single()
        assertEquals("session.create", method)
        assertEquals("tsukaima", params["source"]!!.jsonPrimitive.content)
        assertEquals("/home/x", params["cwd"]!!.jsonPrimitive.content)
        assertFalse(params.containsKey("model"))
        assertEquals("s1", created.sessionId)
        assertEquals("st1", created.storedSessionId)
    }

    @Test
    fun resumeParsesMessages() = runBlocking {
        val t = FakeTransport(obj(historyJson))
        val r = GatewaySession(t).resume("20261020_101500_ab12cd")
        val (method, params) = t.calls.single()
        assertEquals("session.resume", method)
        assertEquals("20261020_101500_ab12cd", params["session_id"]!!.jsonPrimitive.content)
        assertEquals("tsukaima", params["source"]!!.jsonPrimitive.content)
        assertFalse(params["lazy"]!!.jsonPrimitive.boolean)
        assertFalse(params["defer_history"]!!.jsonPrimitive.boolean)
        assertEquals(4, r.messages.size)
        assertEquals("tool", r.messages[2].role)
        assertEquals("terminal", r.messages[2].name)
        assertEquals("uname -a", r.messages[2].context)
        assertEquals("I should run uname", r.messages[1].reasoning)
        assertEquals("", r.messages[1].text)
        assertEquals(3L, r.messages[2].rowId)
        assertFalse(r.running)
    }

    @Test
    fun respondApprovalEncodesChoice() {
        val t = FakeTransport()
        GatewaySession(t).respondApproval("srq-1", ApprovalChoice.deny)
        val (id, result) = t.responses.single()
        assertEquals("srq-1", id)
        assertEquals(obj("""{"choice":"deny"}"""), result)
    }

    @Test
    fun submitReturnsWithoutResultParsing() = runBlocking {
        val t = FakeTransport(obj("""{"status":"streaming","weird":[1,2]}"""))
        GatewaySession(t).submit("s1", "hi")
        val (method, params) = t.calls.single()
        assertEquals("prompt.submit", method)
        assertEquals(setOf("session_id", "text"), params.keys)
    }

    @Test
    fun missingSessionIdThrowsIOException() = runBlocking {
        val t = FakeTransport(obj("""{"stored_session_id":"st1","info":{},"messages":[]}"""))
        try {
            GatewaySession(t).create(null, null)
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("session.create"))
        }
    }

    @Test
    fun mostRecentNullable() = runBlocking {
        val t = FakeTransport(obj("""{"session_id":null}"""))
        assertNull(GatewaySession(t).mostRecent())
    }

    @Test
    fun createSendsProviderWhenGiven() = runBlocking {
        val t = FakeTransport(obj("""{"session_id":"s1","stored_session_id":"st1","info":{},"messages":[]}"""))
        GatewaySession(t).create(cwd = null, model = "deepseek-v4", provider = "melious")
        val params = t.calls.single().second
        assertEquals("deepseek-v4", params["model"]!!.jsonPrimitive.content)
        assertEquals("melious", params["provider"]!!.jsonPrimitive.content)
        assertFalse(params.containsKey("cwd"))
    }

    @Test
    fun approvalRespondUsesRestFallbackMethod() = runBlocking {
        val t = FakeTransport()
        GatewaySession(t).approvalRespond("d4fb0dea", "req-9", ApprovalChoice.once)
        val (method, params) = t.calls.single()
        assertEquals("approval.respond", method)
        assertEquals("d4fb0dea", params["session_id"]!!.jsonPrimitive.content)
        assertEquals("req-9", params["request_id"]!!.jsonPrimitive.content)
        assertEquals("once", params["choice"]!!.jsonPrimitive.content)
    }
}

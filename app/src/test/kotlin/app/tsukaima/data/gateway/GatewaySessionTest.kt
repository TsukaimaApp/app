package app.tsukaima.data.gateway

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.tsukaima.data.api.HermesJson
import app.tsukaima.domain.chat.ConnectionAnswers
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
        override fun respondError(id: String, code: Int, message: String): Boolean = true
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

    /** Every RPC [GatewaySession] can send, with throwaway arguments. */
    private suspend fun GatewaySession.callEveryRpc() {
        create("/w", "m")
        resume("st")
        history("s")
        mostRecent()
        submit("s", "hi")
        interrupt("s")
        setCwd("s", "/w")
        setTitle("s", "t")
        archive("st", true)
        delete("st")
        close("s")
        runCatching { modelOptions("s") }
        eventsSince("s", 0)
        approvalRespond("s", "r", ApprovalChoice.once)
        respondConnection("op", ConnectionAnswers.sessionOwner("s"), ConnectionAnswers.continueNow())
        wakeConnection(ConnectionAnswers.sessionOwner("s"), "op")
    }

    @Test
    fun rpcParamsIncludeProfileWhenNotDefault() = runBlocking {
        val t = FakeTransport(obj("""{"session_id":"s1","messages":[],"sessions":[]}"""))
        GatewaySession(t) { "research" }.callEveryRpc()
        assertEquals(16, t.calls.size)
        for ((method, params) in t.calls) {
            assertEquals(method, "research", params["profile"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun rpcParamsOmitProfileWhenDefault() = runBlocking {
        val t = FakeTransport(obj("""{"session_id":"s1","messages":[],"sessions":[]}"""))
        GatewaySession(t) { "default" }.callEveryRpc()
        GatewaySession(t).callEveryRpc()
        assertEquals(32, t.calls.size)
        for ((method, params) in t.calls) assertFalse(method, params.containsKey("profile"))
    }

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
    fun submitSendsOnlySessionAndTextByDefault() = runBlocking {
        val t = FakeTransport(obj("""{"status":"streaming","weird":[1,2]}"""))
        GatewaySession(t).submit("s1", "hi")
        val (method, params) = t.calls.single()
        assertEquals("prompt.submit", method)
        assertEquals(setOf("session_id", "text"), params.keys)
    }

    @Test
    fun submitAddsVoiceTurnAndInterruptedOnlyWhenSet() = runBlocking {
        val t = FakeTransport(obj("""{"status":"streaming"}"""))
        val s = GatewaySession(t)
        s.submit("s1", "hi", voice = true)
        s.submit("s1", "hi", voice = true, interrupted = true)
        s.submit("s1", "hi", interrupted = true)
        val (voice, both, onlyInterrupted) = t.calls.map { it.second }
        assertEquals(setOf("session_id", "text", "voice_turn"), voice.keys)
        assertEquals(JsonPrimitive(true), voice["voice_turn"])
        assertEquals(setOf("session_id", "text", "voice_turn", "interrupted"), both.keys)
        assertEquals(JsonPrimitive(true), both["interrupted"])
        assertEquals(setOf("session_id", "text", "interrupted"), onlyInterrupted.keys)
    }

    @Test
    fun submitAddsQueuedOnlyWhenSet() = runBlocking {
        val t = FakeTransport(obj("""{"status":"queued"}"""))
        assertEquals("queued", GatewaySession(t).submit("s1", "later", queued = true).status)
        val params = t.calls.single().second
        assertEquals(setOf("session_id", "text", "queued"), params.keys)
        assertEquals(JsonPrimitive(true), params["queued"])
    }

    @Test
    fun submitCarriesSurfaceAndVoiceContext() = runBlocking {
        val t = FakeTransport(obj("""{"status":"streaming"}"""))
        GatewaySession(t).submit("s1", "hello", surface = "voice-live", voiceContext = "User: hi")
        val params = t.calls.single().second
        assertEquals(setOf("session_id", "text", "surface", "voice_context"), params.keys)
        assertEquals("voice-live", params["surface"]!!.jsonPrimitive.content)
        assertEquals("User: hi", params["voice_context"]!!.jsonPrimitive.content)
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
    fun mostRecentNonPrimitiveIdIsNull() = runBlocking {
        assertNull(GatewaySession(FakeTransport(obj("""{"session_id":{"id":"x"}}"""))).mostRecent())
        assertNull(GatewaySession(FakeTransport(obj("""{"session_id":["x"]}"""))).mostRecent())
        assertEquals("st", GatewaySession(FakeTransport(obj("""{"session_id":"st"}"""))).mostRecent())
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

    @Test
    fun respondValueEncodesTheMaskedAnswer() {
        val t = FakeTransport()
        val session = GatewaySession(t)
        assertTrue(session.respondValue("srq-u", "pa\"ss"))
        session.respondValue("srq-s", "")
        assertEquals(listOf("srq-u" to obj("""{"value":"pa\"ss"}"""), "srq-s" to obj("""{"value":""}""")), t.responses)
    }

    @Test
    fun interruptReturnsTheServerStatus() = runBlocking {
        assertEquals("not_interrupted", GatewaySession(FakeTransport(obj("""{"status":"not_interrupted"}"""))).interrupt("s"))
        assertNull(GatewaySession(FakeTransport()).interrupt("s"))
    }

    @Test
    fun resumeDecodesTheInflightTurn() = runBlocking {
        val t = FakeTransport(obj("""{"session_id":"s1","messages":[],"running":true,"inflight":{"assistant":"1\n2","streaming":true,"user":"count"}}"""))
        val r = GatewaySession(t).resume("st")
        assertTrue(r.running)
        assertEquals("1\n2", r.inflight!!["assistant"]!!.jsonPrimitive.content)
    }

    @Test
    fun submitParsesTheAcceptedRow() = runBlocking {
        val t = FakeTransport(obj("""{"status":"streaming","user_row_id":42,"survivor_user_row_ids":[1,null]}"""))
        val r = GatewaySession(t).submit("s1", "hi")
        assertEquals(SubmitResult("streaming", 42L, listOf(1L, null)), r)
        t.result = obj("""{"status":"queued"}""")
        assertEquals(SubmitResult("queued", null), GatewaySession(t).submit("s1", "hi"))
    }

    @Test
    fun submitWithTruncationConfirmsTheCut() = runBlocking {
        val t = FakeTransport(obj("""{"status":"streaming"}"""))
        GatewaySession(t).submit("s1", "hi again", truncateBeforeRowId = 7L)
        val params = t.calls.single().second
        assertEquals(
            setOf("session_id", "text", "truncate_before_row_id", "confirm_truncate", "confirm_deep_truncate", "confirm_empty_truncate"),
            params.keys,
        )
        assertEquals(7L, params["truncate_before_row_id"]!!.jsonPrimitive.content.toLong())
        assertTrue(params["confirm_truncate"]!!.jsonPrimitive.boolean)
        assertTrue(params["confirm_deep_truncate"]!!.jsonPrimitive.boolean)
        assertTrue(params["confirm_empty_truncate"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun btwAndBackgroundReturnTheTaskId() = runBlocking {
        val t = FakeTransport(obj("""{"task_id":"btw_1"}"""))
        assertEquals("btw_1", GatewaySession(t).btw("s1", "what time is it?"))
        t.result = obj("""{"task_id":"bg_2"}""")
        assertEquals("bg_2", GatewaySession(t).background("s1", "index the repo"))
        assertEquals(listOf("prompt.btw", "prompt.background"), t.calls.map { it.first })
        assertEquals(obj("""{"session_id":"s1","text":"what time is it?"}"""), t.calls[0].second)
        assertEquals(obj("""{"session_id":"s1","text":"index the repo"}"""), t.calls[1].second)
        t.result = obj("""{}""")
        try {
            GatewaySession(t).btw("s1", "x")
            fail("a result without task_id is malformed")
        } catch (_: IOException) {
        }
    }

    @Test
    fun connectionRespondAndWake() = runBlocking {
        val t = FakeTransport(obj("""{"status":"ok","settled":true}"""))
        val session = GatewaySession(t)
        val owner = ConnectionAnswers.sessionOwner("d4fb0dea")

        assertEquals(true, session.respondConnection("op-1", owner, ConnectionAnswers.skip("linear")).getOrThrow())
        assertEquals(
            "connection.respond" to obj(
                """{"owner":{"type":"session","session_id":"d4fb0dea"},"op_id":"op-1",
                    "result":{"targets":[{"name":"linear","status":"skipped"}]}}""",
            ),
            t.calls.last(),
        )
        t.result = obj("""{"status":"ok"}""")
        assertEquals(false, session.respondConnection("op-1", owner, ConnectionAnswers.continueNow()).getOrThrow())
        assertTrue(session.wakeConnection(owner, "op-1").isSuccess)
        assertEquals(
            "connectors.operation.wake" to obj("""{"owner":{"type":"session","session_id":"d4fb0dea"},"op_id":"op-1"}"""),
            t.calls.last(),
        )
    }
}

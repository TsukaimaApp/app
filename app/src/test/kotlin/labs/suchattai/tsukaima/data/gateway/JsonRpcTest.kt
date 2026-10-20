package labs.suchattai.tsukaima.data.gateway

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private object HermesJsonForTest {
    fun parse(text: String): JsonObject = labs.suchattai.tsukaima.data.api.HermesJson.parseToJsonElement(text).jsonObject
}

class JsonRpcTest {
    @Test
    fun decodesEventFrame() {
        val line = """{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"d4fb0dea","seq":12,"payload":{"text":"Hello"}}}"""
        val f = JsonRpc.decodeFrames(line).single() as Frame.Event
        assertEquals("message.delta", f.type)
        assertEquals("d4fb0dea", f.sessionId)
        assertEquals(12L, f.seq)
        assertEquals("Hello", f.payload["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun encodesErrorResponse() {
        val frame = HermesJsonForTest.parse(JsonRpc.encodeError("srq-1", 4404, "not shown"))
        assertEquals("2.0", frame["jsonrpc"]!!.jsonPrimitive.content)
        assertEquals("srq-1", frame["id"]!!.jsonPrimitive.content)
        val error = frame["error"]!!.jsonObject
        assertEquals(4404, error["code"]!!.jsonPrimitive.content.toInt())
        assertEquals("not shown", error["message"]!!.jsonPrimitive.content)
        assertTrue("result" !in frame)
    }

    @Test
    fun decodesServerRequest() {
        val line = """{"jsonrpc":"2.0","id":"srq-abc","method":"approval","params":{"command":"rm -rf x"}}"""
        val f = JsonRpc.decodeFrames(line).single() as Frame.ServerRequest
        assertEquals("srq-abc", f.id)
        assertEquals("approval", f.method)
        assertEquals("rm -rf x", f.params["command"]!!.jsonPrimitive.content)
    }

    @Test
    fun decodesResponseWithError() {
        val line = """{"jsonrpc":"2.0","id":"t1","error":{"code":4000,"message":"unknown param"}}"""
        val f = JsonRpc.decodeFrames(line).single() as Frame.Response
        assertEquals("t1", f.id)
        assertEquals(null, f.result)
        assertEquals(4000, f.error!!.code)
        assertEquals("unknown param", f.error!!.message)
    }

    @Test
    fun decodesTwoNewlineDelimitedFrames() {
        val text = """{"jsonrpc":"2.0","id":"t1","result":{"ok":true}}""" + "\n\n" +
            """{"jsonrpc":"2.0","method":"event","params":{"type":"x","session_id":"","payload":{}}}""" + "\n"
        val frames = JsonRpc.decodeFrames(text)
        assertEquals(2, frames.size)
        assertTrue(frames[0] is Frame.Response)
        assertTrue(frames[1] is Frame.Event)
        assertEquals(null, (frames[1] as Frame.Event).seq)
    }

    @Test
    fun malformedBecomesUnknownWithoutThrowing() {
        val frames = JsonRpc.decodeFrames("not json\n[1,2]\n{\"foo\":1}")
        assertEquals(listOf(Frame.Unknown("not json"), Frame.Unknown("[1,2]"), Frame.Unknown("{\"foo\":1}")), frames)
    }

    @Test
    fun encodeRequestOmitsNullParams() {
        assertEquals(
            """{"jsonrpc":"2.0","id":"t1","method":"gateway.ping","params":{}}""",
            JsonRpc.encodeRequest("t1", "gateway.ping", null),
        )
        val withParams = JsonRpc.encodeRequest("t2", "prompt.submit", buildJsonObject { put("text", "hi") })
        assertEquals("hi", kotlinx.serialization.json.Json.parseToJsonElement(withParams).jsonObject["params"]!!.jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun encodeResultMatchesSpec() {
        assertEquals(
            """{"jsonrpc":"2.0","id":"srq-abc","result":{"choice":"once"}}""",
            JsonRpc.encodeResult("srq-abc", buildJsonObject { put("choice", "once") }),
        )
    }
}

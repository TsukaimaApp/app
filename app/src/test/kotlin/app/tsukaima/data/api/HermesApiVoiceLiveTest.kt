package app.tsukaima.data.api

import app.tsukaima.data.prefs.Settings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class HermesApiVoiceLiveTest {
    private lateinit var server: MockWebServer
    private var settings = Settings()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun api() = HermesApi(
        client = OkHttpClient(),
        baseUrl = { server.url("/").toString().trimEnd('/') },
        settings = { settings },
    )

    private fun json(body: String, code: Int = 200) = server.enqueue(
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build(),
    )

    @Test
    fun statusParsesTheServerShape() = runBlocking {
        json("""{"ok":true,"mode":"gpt-live","available":true,"reason":null,"model":"gpt-realtime","voice":"marin"}""")
        val s = api().voiceLiveStatus()
        assertEquals(VoiceLiveStatusDto(true, "gpt-live", true, null, "gpt-realtime", "marin"), s)
        val req = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("GET", req.method)
        assertEquals("/api/audio/voice-live/status", req.url.encodedPath)
        assertNull(req.url.queryParameter("profile"))
    }

    @Test
    fun statusCarriesTheProfileWhenNotDefault() = runBlocking {
        settings = Settings(profile = "work")
        json("""{"ok":true,"mode":"chained","available":false,"reason":"no key"}""")
        val s = api().voiceLiveStatus()
        assertEquals("chained", s.mode)
        assertFalse(s.available)
        assertEquals("work", server.takeRequest(1, TimeUnit.SECONDS)!!.url.queryParameter("profile"))
    }

    @Test
    fun sessionPostSendsSdpByteExactAndParsesAnswer() = runBlocking {
        json("""{"ok":true,"session":{"id":"s-1"},"transport":{"type":"webrtc","sdp":"v=0\r\nanswer\r\n"}}""")
        val sdp = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\n"
        val history = buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", "hi") }) }
        val r = api().voiceLiveSession(sdp, history)
        assertTrue(r.ok)
        assertEquals("s-1", r.session?.id)
        assertEquals("webrtc", r.transport?.type)
        assertEquals("v=0\r\nanswer\r\n", r.transport?.sdp)
        val req = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", req.method)
        assertEquals("/api/audio/voice-live/session", req.url.encodedPath)
        val body = HermesJson.parseToJsonElement(req.body!!.utf8()).jsonObject
        assertEquals(sdp, body["sdp"]!!.jsonPrimitive.content)
        assertEquals(history, body["history"])
    }

    @Test
    fun sessionPostOmitsHistoryWhenNull() = runBlocking {
        json("""{"ok":true,"session":{"id":"s-1"},"transport":{"type":"webrtc","sdp":"x"}}""")
        api().voiceLiveSession("v=0\r\n", null)
        val body = HermesJson.parseToJsonElement(server.takeRequest(1, TimeUnit.SECONDS)!!.body!!.utf8()).jsonObject
        assertEquals(setOf("sdp"), body.keys)
    }

    @Test
    fun sessionErrorsKeepDetail() = runBlocking {
        json("""{"detail":"OPENAI_API_KEY not configured"}""", code = 503)
        val e503 = runCatching { api().voiceLiveSession("v=0\r\n", null) }.exceptionOrNull() as ApiException
        assertEquals(503, e503.code)
        assertEquals("OPENAI_API_KEY not configured", e503.detail)
        json("""{"detail":"vendor rejected the offer"}""", code = 502)
        val e502 = assertThrows(ApiException::class.java) { runBlocking { api().voiceLiveSession("v=0\r\n", null) } }
        assertEquals(502, e502.code)
        assertEquals("vendor rejected the offer", e502.detail)
    }
}

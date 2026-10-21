package app.tsukaima.data.api

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CleartextGuardTest {
    private lateinit var server: MockWebServer
    private val asked = mutableListOf<HttpUrl>()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun client(trusted: Boolean): OkHttpClient {
        val guard = CleartextGuard { url -> asked += url; trusted }
        return OkHttpClient.Builder().addInterceptor(guard).addNetworkInterceptor(guard).build()
    }

    private fun get(client: OkHttpClient, url: HttpUrl) =
        client.newCall(Request.Builder().url(url).build()).execute().use { it.code }

    @Test
    fun plainHttpToUntrustedHostIsRefusedBeforeAnythingIsSent() {
        server.enqueue(MockResponse.Builder().code(200).build())
        val e = assertThrows(ApiException::class.java) { get(client(trusted = false), server.url("/api/status")) }
        assertEquals(CleartextGuard.MESSAGE, e.message)
        assertEquals(CleartextGuard.MESSAGE, e.userMessage())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun plainHttpToTrustedHostPasses() {
        server.enqueue(MockResponse.Builder().code(200).build())
        assertEquals(200, get(client(trusted = true), server.url("/api/status")))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun redirectToUntrustedHttpIsRefusedAtTheHop() {
        // A trusted first hop (application interceptor) redirecting to a host the network interceptor refuses.
        val target = server.url("/elsewhere")
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", target.toString()).build())
        val guard = CleartextGuard { url -> url.encodedPath != "/elsewhere" }
        val client = OkHttpClient.Builder().addInterceptor(guard).addNetworkInterceptor(guard).build()
        assertThrows(ApiException::class.java) { get(client, server.url("/start")) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun defaultPolicyFollowsTrustedHost() {
        assertTrue(CleartextGuard.refuses("http://example.com:9119".toHttpUrl()))
        assertFalse(CleartextGuard.refuses("https://example.com".toHttpUrl()))
        assertFalse(CleartextGuard.refuses("http://100.101.102.103:9119".toHttpUrl()))
        assertFalse(CleartextGuard.refuses("http://hermes:9119".toHttpUrl()))
        assertFalse(CleartextGuard.refuses("http://192.168.1.5".toHttpUrl()))
        assertTrue(CleartextGuard.refuses("http://8.8.8.8".toHttpUrl()))
        assertFalse(CleartextGuard.refuses("not a url")) // unparseable: someone else's error to report
    }

    @Test
    fun httpsIsNeverAskedAbout() {
        assertFalse(CleartextGuard.refuses("https://example.com".toHttpUrl()) { asked += it; false })
        assertTrue(asked.isEmpty())
    }
}

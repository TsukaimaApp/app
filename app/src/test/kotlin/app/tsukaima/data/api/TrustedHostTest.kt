package app.tsukaima.data.api

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class TrustedHostTest {
    private fun allows(url: String) = TrustedHost.allowsCleartext(url.toHttpUrl())

    @Test
    fun cleartextReplayTable() {
        val table = mapOf(
            "http://100.69.91.116:9119" to true, // tailscale CGNAT
            "http://hermes:9119" to true, // single-label
            "http://192.168.1.5" to true,
            "http://hermes.tailcb682b.ts.net" to true,
            "http://example.com" to false,
            "http://8.8.8.8" to false,
            "http://127.0.0.1:9119" to true,
            "http://localhost:9119" to true,
            "http://10.254.0.42" to true,
            "http://172.16.0.1" to true,
            "http://172.31.255.255" to true,
            "http://172.32.0.1" to false,
            "http://100.63.0.1" to false,
            "http://100.128.0.1" to false,
            "http://[::1]:9119" to true,
            "http://[2001:db8::1]" to false,
            "http://evil.ts.net.example.com" to false,
            "https://example.com" to true, // https always allowed
            "https://8.8.8.8" to true,
        )
        table.forEach { (url, expected) -> assertEquals(url, expected, allows(url)) }
    }
}

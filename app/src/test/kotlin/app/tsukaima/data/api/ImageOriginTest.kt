package app.tsukaima.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageOriginTest {
    private val base = "https://hermes.example.com"

    @Test
    fun sameOriginComparesSchemeHostAndPort() {
        assertTrue(ImageOrigin.sameOrigin("https://hermes.example.com/api/files/a.png", base))
        assertTrue("host case", ImageOrigin.sameOrigin("https://HERMES.Example.COM/a.png", base))
        assertTrue("default port", ImageOrigin.sameOrigin("https://hermes.example.com:443/a.png", base))
        assertTrue("default port in the base", ImageOrigin.sameOrigin("http://h/a.png", "http://h:80"))
        assertFalse("port mismatch", ImageOrigin.sameOrigin("https://hermes.example.com:8443/a.png", base))
        assertFalse("scheme mismatch", ImageOrigin.sameOrigin("http://hermes.example.com/a.png", base))
        assertFalse("subdomain", ImageOrigin.sameOrigin("https://img.hermes.example.com/a.png", base))
        assertFalse("suffix trick", ImageOrigin.sameOrigin("https://hermes.example.com.evil.net/a.png", base))
        assertFalse("userinfo trick", ImageOrigin.sameOrigin("https://hermes.example.com@evil.net/a.png", base))
        assertFalse("userinfo trick with port", ImageOrigin.sameOrigin("https://hermes.example.com:443@evil.net/a.png", base))
    }

    @Test
    fun unparseableIsNeverSameOrigin() {
        assertFalse(ImageOrigin.sameOrigin("https://hermes.example.com/a.png", ""))
        assertFalse(ImageOrigin.sameOrigin("https://hermes.example.com/a.png", "not a url"))
        assertFalse(ImageOrigin.sameOrigin("file:///etc/passwd", "file:///etc"))
        assertFalse(ImageOrigin.sameOrigin("", base))
    }

    @Test
    fun hostForTheCaption() {
        assertEquals("evil.net", ImageOrigin.host("https://hermes.example.com@evil.net/a.png"))
        assertEquals("tiles.example.org", ImageOrigin.host("https://Tiles.Example.org:8443/x.png"))
        assertNull(ImageOrigin.host("data:image/png;base64,AAAA"))
    }
}

package app.tsukaima.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerUrlTest {
    @Test
    fun addsHttpScheme() {
        assertEquals("http://hermes:9119", ServerUrl.normalize("hermes:9119"))
    }

    @Test
    fun stripsTrailingSlashAndPath() {
        assertEquals("http://hermes:9119", ServerUrl.normalize("http://hermes:9119/"))
        assertEquals("http://hermes:9119", ServerUrl.normalize("http://hermes:9119/login?x=1"))
    }

    @Test
    fun keepsHttpsAndPort() {
        assertEquals(
            "https://hermes.example.com:8443",
            ServerUrl.normalize("HTTPS://Hermes.Example.com:8443"),
        )
    }

    @Test
    fun rejectsEmptyHost() {
        assertNull(ServerUrl.normalize("http://"))
    }

    @Test
    fun dropsDefaultPorts() {
        assertEquals("http://hermes", ServerUrl.normalize("http://hermes:80"))
        assertEquals("https://hermes", ServerUrl.normalize("https://hermes:443/"))
    }

    @Test
    fun trimsWhitespaceAndRejectsBlank() {
        assertEquals("http://hermes:9119", ServerUrl.normalize("  hermes:9119  "))
        assertNull(ServerUrl.normalize("   "))
    }
}

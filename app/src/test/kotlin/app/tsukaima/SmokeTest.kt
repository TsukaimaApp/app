package app.tsukaima

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SmokeTest {
    @Test
    fun jsonRoundTrip() {
        assertEquals("""{"a":1}""", Json.encodeToString(mapOf("a" to 1)))
    }
}

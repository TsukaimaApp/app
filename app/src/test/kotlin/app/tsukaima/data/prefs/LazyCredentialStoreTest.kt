package app.tsukaima.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LazyCredentialStoreTest {
    @Test
    fun constructsDelegateLazilyAndOnce() {
        var built = 0
        val store = LazyCredentialStore(create = { built++; InMemoryCredentialStore() }, onFallback = {})
        assertEquals(0, built)
        store.save("pw", "tok", "http://hermes")
        assertEquals("pw", store.password())
        assertEquals("tok", store.accessToken())
        assertEquals(1, built)
    }

    @Test
    fun fallsBackToInMemoryWhenConstructionThrows() {
        var reported: Throwable? = null
        val store = LazyCredentialStore(create = { throw IllegalStateException("keystore broken") }, onFallback = { reported = it })
        assertNull(store.password())
        store.save("pw", null, null)
        assertEquals("pw", store.password())
        assertEquals("keystore broken", reported?.message)
        store.clear()
        assertNull(store.password())
    }
}

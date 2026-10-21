package app.tsukaima.domain.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class RecognizerRoutingTest {
    @Test fun defaultLanguageUsesOnDeviceAndOffline() {
        assertEquals(RecognizerChoice(onDevice = true, preferOffline = true), RecognizerRouting.choose(null, "pl-PL", true))
    }

    @Test fun deviceLanguagePickedExplicitlyUsesOnDeviceAndOffline() {
        assertEquals(RecognizerChoice(onDevice = true, preferOffline = true), RecognizerRouting.choose("pl-PL", "pl-PL", true))
    }

    @Test fun otherLanguageUsesDefaultRecogniserOnline() {
        assertEquals(RecognizerChoice(onDevice = false, preferOffline = false), RecognizerRouting.choose("en-US", "pl-PL", true))
    }

    @Test fun languageMatchIsCaseInsensitiveAndIgnoresRegion() {
        assertEquals(RecognizerChoice(onDevice = true, preferOffline = true), RecognizerRouting.choose("PL", "pl-PL", true))
        assertEquals(RecognizerChoice(onDevice = true, preferOffline = true), RecognizerRouting.choose("pl", "pl-PL", true))
    }

    @Test fun onDeviceUnavailableKeepsOfflinePreferenceForDeviceLanguage() {
        assertEquals(RecognizerChoice(onDevice = false, preferOffline = true), RecognizerRouting.choose(null, "pl-PL", false))
        assertEquals(RecognizerChoice(onDevice = false, preferOffline = true), RecognizerRouting.choose("pl-PL", "pl-PL", false))
        assertEquals(RecognizerChoice(onDevice = false, preferOffline = false), RecognizerRouting.choose("en-US", "pl-PL", false))
    }
}

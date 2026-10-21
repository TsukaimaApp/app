package app.tsukaima.notifications

import app.tsukaima.data.prefs.NotificationPrefs
import app.tsukaima.data.prefs.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundPolicyTest {
    @Test
    fun backgroundPolicyStartsServiceOnlyWhenConfiguredAndPermitted() {
        val s = Settings(serverUrl = "http://hermes:9119", username = "u", onboarded = true)
        fun start(
            settings: Settings? = s,
            hasPassword: Boolean = true,
            authRequired: Boolean = false,
            permitted: Boolean = true,
            foreground: Boolean = false,
        ) = BackgroundPolicy.shouldStartService(settings, hasPassword, authRequired, permitted, foreground)

        assertTrue(start())
        assertFalse(start(permitted = false))
        assertFalse(start(hasPassword = false))
        assertFalse(start(settings = s.copy(serverUrl = "")))
        assertFalse(start(settings = null))
        assertFalse(start(authRequired = true))
        assertFalse(start(foreground = true)) // the user came back before we decided
        val allOff = NotificationPrefs(approvals = false, pairing = false, replies = false, alerts = false)
        assertFalse(start(settings = s.copy(notifications = allOff))) // nothing would be posted
        assertTrue(start(settings = s.copy(notifications = allOff.copy(alerts = true))))
    }

    @Test
    fun permissionRequestedOnceAfterOnboardingOnTiramisu() {
        val done = Settings(onboarded = true)
        assertTrue(BackgroundPolicy.shouldRequestPermission(33, done, granted = false))
        assertFalse(BackgroundPolicy.shouldRequestPermission(32, done, granted = false))
        assertFalse(BackgroundPolicy.shouldRequestPermission(33, Settings(onboarded = false), granted = false))
        assertFalse(BackgroundPolicy.shouldRequestPermission(33, done.copy(askedNotifications = true), granted = false))
        assertFalse(BackgroundPolicy.shouldRequestPermission(33, done, granted = true))
        assertFalse(BackgroundPolicy.shouldRequestPermission(33, null, granted = false))
    }
}

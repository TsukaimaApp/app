package app.tsukaima.notifications

import app.tsukaima.data.prefs.Settings

/** Pure decisions about background operation; the Android glue only gathers the inputs. */
object BackgroundPolicy {
    /**
     * Whether to start [GatewayService] when the app goes to the background: only for a configured app
     * (server URL and stored password, no pending re-login) that may post notifications, and only if the
     * user has not already come back, and only while at least one notification kind is switched on (with every
     * kind off nothing would be posted, so a foreground service would only hold a socket open). Otherwise the
     * app just goes quiet in the background — except while a live voice session is open ([liveVoiceOpen]): its
     * delegations need Hermes, so the connection is handed off regardless.
     */
    fun shouldStartService(
        settings: Settings?,
        hasPassword: Boolean,
        authRequired: Boolean,
        notificationsPermitted: Boolean,
        foreground: Boolean,
        liveVoiceOpen: Boolean = false,
    ): Boolean =
        !foreground && (
            liveVoiceOpen || notificationsPermitted && !authRequired && hasPassword &&
                settings != null && settings.serverUrl.isNotEmpty() && settings.notifications.anyOn
            )

    /** Ask for `POST_NOTIFICATIONS` once, after onboarding, on API 33+, unless it is already granted. */
    fun shouldRequestPermission(sdkInt: Int, settings: Settings?, granted: Boolean): Boolean =
        sdkInt >= 33 && settings != null && settings.onboarded && !settings.askedNotifications && !granted
}

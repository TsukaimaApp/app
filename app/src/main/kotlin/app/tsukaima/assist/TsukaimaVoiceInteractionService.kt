package app.tsukaima.assist

import android.service.voice.VoiceInteractionService

/**
 * Makes Tsukaima selectable as the device's digital assistant (Settings → Default apps). It does nothing itself:
 * the system binds it while Tsukaima holds the assistant role and starts a session through
 * [TsukaimaSessionService] on each invocation (long-press home, corner swipe, assist button).
 */
class TsukaimaVoiceInteractionService : VoiceInteractionService()

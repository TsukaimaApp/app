package app.tsukaima.assist

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** Hosts the assistant sessions: one [TsukaimaVoiceSession] per invocation. */
class TsukaimaSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = TsukaimaVoiceSession(this)
}

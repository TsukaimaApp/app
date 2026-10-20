package labs.suchattai.tsukaima.assist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View

/**
 * An assistant invocation: has no window of its own; it starts [AssistActivity] (the voice sheet over the
 * current app) and finishes at once. The screen's assist data is neither requested nor read.
 */
class TsukaimaVoiceSession(context: Context) : VoiceInteractionSession(context) {
    override fun onCreateContentView(): View? = null

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        startAssistantActivity(Intent(context, AssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}

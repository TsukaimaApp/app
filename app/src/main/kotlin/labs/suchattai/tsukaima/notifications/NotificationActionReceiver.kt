package labs.suchattai.tsukaima.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.launch
import labs.suchattai.tsukaima.TsukaimaApp

/**
 * Handles notification buttons. Not exported: only the app's own immutable, explicit PendingIntents reach
 * it. Approvals go through [ApprovalAnswerer] (srq id + choice only); pairing approval calls the REST API.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as TsukaimaApp).container
        when (intent.action) {
            ApprovalNotifier.ACTION_APPROVE, ApprovalNotifier.ACTION_DENY -> {
                val outcome = container.approvalAnswerer.answer(
                    intent.getStringExtra(ApprovalNotifier.EXTRA_SRQ_ID),
                    intent.getStringExtra(ApprovalNotifier.EXTRA_CHOICE),
                )
                if (outcome == ApprovalAnswerer.Outcome.NotConnected || outcome == ApprovalAnswerer.Outcome.NotSent) {
                    Toast.makeText(context, "Not connected to Hermes; try again in a moment", Toast.LENGTH_SHORT).show()
                }
            }
            ApprovalNotifier.ACTION_PAIR_APPROVE -> {
                val platform = intent.getStringExtra(ApprovalNotifier.EXTRA_PLATFORM) ?: return
                val requestId = intent.getStringExtra(ApprovalNotifier.EXTRA_REQUEST_ID) ?: return
                val id = ApprovalNotifier.pairingId(platform, requestId)
                container.notifications.cancel(id) // gone at once: no second tap
                val pending = goAsync()
                container.appScope.launch {
                    try {
                        container.repository.approvePairing(platform, requestId).onFailure { e ->
                            Log.w(TAG, "pairing approval failed", e)
                            container.notifications.post(
                                NotificationSpec(
                                    Channels.PAIRING, id, "Pairing not approved",
                                    "Couldn’t approve the pairing request; open Tsukaima to retry",
                                    openScreen = ApprovalNotifier.SCREEN_SETTINGS,
                                ),
                            )
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "Tsukaima"
    }
}

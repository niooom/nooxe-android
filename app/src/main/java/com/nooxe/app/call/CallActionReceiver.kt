package com.nooxe.app.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** The "প্রত্যাখ্যান" button on the ringing notification. */
class CallActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_DECLINE = "com.nooxe.app.call.DECLINE"
        const val EXTRA_CALL_ID = "call_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DECLINE) return
        val callId = intent.getStringExtra(EXTRA_CALL_ID)
        val current = CallManager.data
        if (current == null || (!callId.isNullOrEmpty() && callId != current.callId)) {
            // A notification left over from an older call must never touch the
            // call that is ringing now.
            CallNotification.cancelIncoming(context)
            return
        }
        CallManager.decline(context)
    }
}

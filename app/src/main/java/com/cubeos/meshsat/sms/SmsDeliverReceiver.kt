package com.cubeos.meshsat.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * Required by Android to be a valid default SMS app candidate.
 * Receives SMS_DELIVER intents (only delivered when we are the default SMS app).
 * Delegates to the same logic as SmsReceiver.
 */
class SmsDeliverReceiver : BroadcastReceiver() {

    private val delegate = SmsReceiver()

    override fun onReceive(context: Context, intent: Intent) {
        // SMS_DELIVER uses the same extras as SMS_RECEIVED
        delegate.onReceive(context, intent)
    }
}

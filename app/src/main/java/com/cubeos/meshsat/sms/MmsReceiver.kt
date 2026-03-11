package com.cubeos.meshsat.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Required by Android to be a valid default SMS app candidate.
 * We don't handle MMS — this is a no-op stub to satisfy the requirement.
 */
class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // No-op: MeshSat does not process MMS
    }
}

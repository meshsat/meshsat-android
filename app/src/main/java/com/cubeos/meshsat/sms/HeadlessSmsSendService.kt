package com.cubeos.meshsat.sms

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Required by Android to be a valid default SMS app candidate.
 * Handles ACTION_RESPOND_VIA_MESSAGE (quick reply from notification).
 * Stub — we don't support quick-reply from system UI.
 */
class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}

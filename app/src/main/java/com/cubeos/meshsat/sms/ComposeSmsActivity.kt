package com.cubeos.meshsat.sms

import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * Required by Android to be a valid default SMS app candidate.
 * Responds to ACTION_SENDTO / SMSTO intents.
 * Minimal stub — just finishes immediately since MeshSat composes
 * messages through its own UI, not the system SMS compose flow.
 */
class ComposeSmsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Could open MeshSat Messages screen here in the future
        finish()
    }
}

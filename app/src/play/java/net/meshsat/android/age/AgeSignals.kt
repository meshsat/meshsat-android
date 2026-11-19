package net.meshsat.android.age

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.tasks.Task
import com.google.android.play.agesignals.AgeSignalsAccessRequest
import com.google.android.play.agesignals.AgeSignalsException
import com.google.android.play.agesignals.AgeSignalsManager
import com.google.android.play.agesignals.AgeSignalsManagerFactory
import com.google.android.play.agesignals.AgeSignalsRequest
import com.google.android.play.agesignals.ApplicableAgeFeaturesRequest
import com.google.android.play.agesignals.model.AgeSignalsStatus
import com.google.android.play.agesignals.model.ApplicableAgeFeature
import java.util.concurrent.Executor
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The Google Play edition asks Play for the user's age range at a start of the app, at most once
 * a day ([AgeSignalSchedule], MESHSAT-1908), through the Play Age Signals API.
 *
 * Play is asked first whether mandatory age sharing applies to this user
 * (`getApplicableAgeFeatures`). Only where it does is the signal requested: in those regions Play
 * shows no prompt in the app (users verify in the Play Store), while elsewhere a request would put
 * Play's "share your age?" prompt in front of every user, for nothing the app needs. The answer is
 * read and dropped; nothing in the app depends on it.
 */
object AgeSignals {
    /** True: this edition calls the Play Age Signals API. */
    const val INCLUDED = true

    private const val TAG = "AgeSignals"

    @Volatile private var triedMs = 0L
    @Volatile private var running = false

    fun onLaunch(activity: ComponentActivity) {
        val prefs = activity.getSharedPreferences(AgeSignalSchedule.PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (running || !AgeSignalSchedule.due(prefs.getLong(AgeSignalSchedule.KEY_DONE_MS, 0L), triedMs, now)) return
        running = true
        triedMs = now
        activity.lifecycleScope.launch {
            try {
                val outcome = try {
                    AgeSignalFlow(AgeSignalsManagerFactory.create(activity.applicationContext)).run(activity)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AgeSignalOutcome.Failed(null)
                }
                Log.i(TAG, "Age signal: ${outcome.describe()}")
                if (outcome.done) {
                    prefs.edit().putLong(AgeSignalSchedule.KEY_DONE_MS, System.currentTimeMillis()).apply()
                }
            } finally {
                running = false
            }
        }
    }
}

/** One ask, against any [AgeSignalsManager] (the tests hand it Play's FakeAgeSignalsManager). */
internal class AgeSignalFlow(private val manager: AgeSignalsManager) {
    suspend fun run(activity: Activity): AgeSignalOutcome = try {
        val features = manager.getApplicableAgeFeatures(ApplicableAgeFeaturesRequest.builder().build())
            .await().features()
        if (ApplicableAgeFeature.MANDATORY_SHARING !in features) {
            AgeSignalOutcome.NotRequired
        } else {
            val access = manager.requestAgeSignalsAccess(
                AgeSignalsAccessRequest.builder().setActivity(activity).build()
            ).await()
            when (access.ageSignalsStatus()) {
                AgeSignalsStatus.SHARED -> {
                    // The request the law asks for; the range itself is not kept.
                    manager.checkAgeSignals(AgeSignalsRequest.builder().build()).await()
                    AgeSignalOutcome.Shared
                }
                AgeSignalsStatus.VERIFICATION_REQUIRED -> AgeSignalOutcome.VerificationRequired
                else -> AgeSignalOutcome.NotShared
            }
        }
    } catch (e: AgeSignalsException) {
        AgeSignalOutcome.Failed(e.errorCode)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AgeSignalOutcome.Failed(null)
    }
}

/** Runs listeners on the thread that completes the task, so no main Looper is needed. */
private val direct = Executor { it.run() }

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener(direct) { task ->
        val error = task.exception
        when {
            error != null -> cont.resumeWithException(error)
            task.isCanceled -> cont.cancel()
            else -> cont.resume(task.result)
        }
    }
}

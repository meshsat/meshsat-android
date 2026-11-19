package net.meshsat.android.age

import android.app.Activity
import com.google.android.gms.tasks.Task
import com.google.android.play.agesignals.AgeSignalsAccessRequest
import com.google.android.play.agesignals.AgeSignalsAccessResult
import com.google.android.play.agesignals.AgeSignalsException
import com.google.android.play.agesignals.AgeSignalsManager
import com.google.android.play.agesignals.AgeSignalsRequest
import com.google.android.play.agesignals.AgeSignalsResult
import com.google.android.play.agesignals.ApplicableAgeFeaturesResult
import com.google.android.play.agesignals.model.AgeRangeSource
import com.google.android.play.agesignals.model.AgeSignalsErrorCode
import com.google.android.play.agesignals.model.AgeSignalsStatus
import com.google.android.play.agesignals.model.ApplicableAgeFeature
import com.google.android.play.agesignals.testing.FakeAgeSignalsManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The play edition's ask against Play's own FakeAgeSignalsManager (MESHSAT-1908): the signal is
 * requested only where Play says sharing is mandatory, so no other user ever sees Play's prompt.
 */
class AgeSignalFlowTest {
    /** Counts what the flow asks of Play. */
    private class Counting(val fake: FakeAgeSignalsManager = FakeAgeSignalsManager()) : AgeSignalsManager by fake {
        var accessRequests = 0
        var checks = 0
        override fun requestAgeSignalsAccess(request: AgeSignalsAccessRequest): Task<AgeSignalsAccessResult> {
            accessRequests++
            return fake.requestAgeSignalsAccess(request)
        }
        override fun checkAgeSignals(request: AgeSignalsRequest): Task<AgeSignalsResult> {
            checks++
            return fake.checkAgeSignals(request)
        }
    }

    private fun features(vararg f: Int) = ApplicableAgeFeaturesResult.builder().setFeatures(f.toSet()).build()
    private fun access(status: Int) = AgeSignalsAccessResult.builder().setAgeSignalsStatus(status).build()
    private val activity = Activity()

    @Test
    fun `where sharing is not mandatory nothing is requested and no prompt can show`() = runTest {
        val m = Counting().apply { fake.setNextApplicableAgeFeaturesResult(features()) }
        assertEquals(AgeSignalOutcome.NotRequired, AgeSignalFlow(m).run(activity))
        assertEquals(0, m.accessRequests)
        assertEquals(0, m.checks)
    }

    @Test
    fun `where it is mandatory and shared the signal is read`() = runTest {
        val m = Counting().apply {
            fake.setNextApplicableAgeFeaturesResult(features(ApplicableAgeFeature.MANDATORY_SHARING))
            fake.setNextAgeSignalsAccessResult(access(AgeSignalsStatus.SHARED))
            fake.setNextAgeSignalsResult(
                AgeSignalsResult.builder().setAgeRangeSource(AgeRangeSource.TIER_A).setAgeLower(18).build()
            )
        }
        assertEquals(AgeSignalOutcome.Shared, AgeSignalFlow(m).run(activity))
        assertEquals(1, m.accessRequests)
        assertEquals(1, m.checks)
    }

    @Test
    fun `not shared is an answer, and nothing more is read`() = runTest {
        val m = Counting().apply {
            fake.setNextApplicableAgeFeaturesResult(features(ApplicableAgeFeature.MANDATORY_SHARING))
            fake.setNextAgeSignalsAccessResult(access(AgeSignalsStatus.NOT_SHARED))
        }
        assertEquals(AgeSignalOutcome.NotShared, AgeSignalFlow(m).run(activity))
        assertEquals(0, m.checks)
    }

    @Test
    fun `verification required is an answer`() = runTest {
        val m = Counting().apply {
            fake.setNextApplicableAgeFeaturesResult(features(ApplicableAgeFeature.MANDATORY_SHARING))
            fake.setNextAgeSignalsAccessResult(access(AgeSignalsStatus.VERIFICATION_REQUIRED))
        }
        assertEquals(AgeSignalOutcome.VerificationRequired, AgeSignalFlow(m).run(activity))
        assertEquals(0, m.checks)
    }

    @Test
    fun `a Play error is a failure with its code, to be tried again`() = runTest {
        val m = Counting().apply {
            fake.setNextApplicableAgeFeaturesException(AgeSignalsException(AgeSignalsErrorCode.NETWORK_ERROR))
        }
        assertEquals(AgeSignalOutcome.Failed(AgeSignalsErrorCode.NETWORK_ERROR), AgeSignalFlow(m).run(activity))
        assertEquals(0, m.accessRequests)
    }

    @Test
    fun `an error on the request itself is a failure too`() = runTest {
        val m = Counting().apply {
            fake.setNextApplicableAgeFeaturesResult(features(ApplicableAgeFeature.MANDATORY_SHARING))
            fake.setNextRequestAgeSignalsAccessException(AgeSignalsException(AgeSignalsErrorCode.APP_NOT_OWNED))
        }
        assertEquals(AgeSignalOutcome.Failed(AgeSignalsErrorCode.APP_NOT_OWNED), AgeSignalFlow(m).run(activity))
        assertEquals(0, m.checks)
    }
}

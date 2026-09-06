package org.libremediaconverter.work

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.work.Constraints
import androidx.work.OutOfQuotaPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Both workers enqueue **expedited** work, and stay legal doing it.
 *
 * The two questions are separate and only one of them is about the flag.
 *
 *  - **Is it set.** `expedited` is `false` by default, so `assertTrue` here is what a deleted
 *    `setExpedited(...)` reddens. That mutation was run.
 *  - **Is it legal.** `WorkRequest.Builder.build()` refuses an expedited request that carries an
 *    initial delay or any constraint but network and storage — `require(workSpec.initialDelay <= 0)
 *    { "Expedited jobs cannot be delayed" }` in work-runtime 2.11.2. Neither `request` sets either
 *    today, so both `build()` calls pass and the `IllegalArgumentException` is a *future* hazard
 *    rather than a current one. The delay and constraints assertions below are what name it: add a
 *    delay to either builder and this class fails on the throw, in the same second, instead of the
 *    app failing to enqueue a conversion on a device.
 *
 * **The policy assertion bites less than it reads, and that is worth writing down rather than
 * leaving to be rediscovered.** `WorkSpec.outOfQuotaPolicy` *defaults* to
 * `RUN_AS_NON_EXPEDITED_WORK_REQUEST`, so it is already this value on a request that was never
 * expedited at all — deleting `setExpedited` does not redden it. What it does pin is the one
 * alternative: `DROP_WORK_REQUEST` throws a user's conversion away because an invisible quota ran
 * out, and that mutation *is* red here.
 *
 * The delay is not hypothetical either. Three tests deliberately build a delayed request to hold a
 * job in `ENQUEUED` — `NotificationCancelActionTest`, `ReattachOnLaunchTest` and
 * `CancelReachesWorkManagerTest` — and every one of them builds its own
 * `OneTimeWorkRequestBuilder` rather than adding a delay to what `request` returns. That is why
 * making these expedited broke none of them; the one that starts from `request` takes only
 * `base.workSpec.input` from it.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class ExpeditedRequestTest {

    @Test
    fun `a conversion is enqueued as expedited work`() {
        val spec = ConversionWorker.request(INPUT, DISPLAY_NAME, INPUT_BYTES).workSpec

        assertTrue("a conversion the user asked for has to be expedited work", spec.expedited)
        assertEquals(
            "a quota nobody can see is no reason to drop a conversion",
            OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST,
            spec.outOfQuotaPolicy,
        )
    }

    @Test
    fun `a join is enqueued as expedited work`() {
        val spec = ConcatWorker.request(listOf(INPUT, SECOND_INPUT), TOTAL_BYTES).workSpec

        assertTrue("a join the user asked for has to be expedited work", spec.expedited)
        assertEquals(
            "a quota nobody can see is no reason to drop a join",
            OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST,
            spec.outOfQuotaPolicy,
        )
    }

    /**
     * The two properties that keep `build()` from throwing, asserted on both requests at once
     * because the rule is WorkManager's rather than either worker's.
     */
    @Test
    fun `neither expedited request carries what would make it illegal`() {
        val requests = listOf(
            ConversionWorker.request(INPUT, DISPLAY_NAME, INPUT_BYTES).workSpec,
            ConcatWorker.request(listOf(INPUT, SECOND_INPUT), TOTAL_BYTES).workSpec,
        )

        requests.forEach { spec ->
            assertEquals(
                "expedited work cannot be delayed: ${spec.workerClassName}",
                0L,
                spec.initialDelay,
            )
            assertEquals(
                "expedited work takes only network and storage constraints: ${spec.workerClassName}",
                Constraints.NONE,
                spec.constraints,
            )
        }
    }

    private companion object {
        val INPUT: Uri = Uri.parse("file:///tmp/holiday.mp4")
        val SECOND_INPUT: Uri = Uri.parse("file:///tmp/holiday2.mp4")
        const val DISPLAY_NAME = "holiday.mp4"
        const val INPUT_BYTES = 1_024L
        const val TOTAL_BYTES = 2_048L
    }
}

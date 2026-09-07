package org.libremediaconverter.work

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.model.OutputFormat
import org.libremediaconverter.model.QualityTier
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The Cancel button in the notification shade actually cancels the job.
 *
 * `ConversionNotifications.build` attaches one action, wired to
 * `WorkManager.createCancelPendingIntent(id)`. Before this test `createCancelPendingIntent` had
 * **no references anywhere outside its own declaration** — no JVM test, no instrumented test
 * (#227).
 *
 * That matters more than an ordinary uncovered line. A conversion runs in a foreground service and
 * the user is invited to leave the app; once they do, this action is the only way to stop it. If
 * the `PendingIntent` carries the wrong id, the button does nothing, the notification stays, and
 * the job runs to completion — with no error, no log, and no screen to look at.
 *
 * ## Why this fires the intent rather than reading the shade
 *
 * The obvious version asks `NotificationManager.getActiveNotifications()` for id 1001 and taps what
 * it finds. That was rejected because it would be asserting something about notification
 * *visibility* rather than about cancellation — and because whether the shade holds the
 * notification at all is not this class's to know.
 *
 * **It used to say `POST_NOTIFICATIONS` is denied throughout, and since #268 that is no longer
 * true.** `SafPickerRoundTripTest` grants it in `@Before`, so that its Convert tap cannot open a
 * permission dialog, and a runtime grant cannot be undone in teardown without restarting the app's
 * process. The suite runs without Orchestrator, so whether this class sees the permission held
 * depends on class order — which is exactly the reading this test does not do, and the reason it
 * stays the right shape rather than a reason to change it.
 *
 * The `PendingIntent` is the subject; where it is read from is incidental. Building the
 * notification for a real, live work id and firing its action exercises exactly the thing that can
 * be wrong — a real `PendingIntent` dispatch reaching real `WorkManager` — and does it the same way
 * on every API level.
 *
 * ## Why the job is delayed rather than running
 *
 * A conversion of the committed 3 s fixture finishes in well under a second on an emulator
 * (`HardwareFallbackTest` completed one in 448 ms), so racing a cancel against a running job would
 * be flaky in the direction that fails. An initial delay keeps the job reliably `ENQUEUED`, which
 * is a state `cancelWorkById` acts on identically — what is under test is whether firing the action
 * reaches WorkManager with the right id, not which state it interrupts.
 *
 * *Mutation:* build the `PendingIntent` from `UUID.randomUUID()` instead of the request's id. The
 * notification looks identical and the job is never cancelled.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class NotificationCancelActionTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val workManager = WorkManager.getInstance(context)
    private lateinit var input: File

    @Before
    fun setUp() {
        input = File(context.cacheDir, "cancel_action_sample.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("sample_h264.mp4")
            .use { asset -> input.outputStream().use { asset.copyTo(it) } }
    }

    @After
    fun tearDown() {
        input.delete()
        File(context.cacheDir, "conversions").listFiles()?.forEach { it.delete() }
    }

    @Test
    fun theNotificationsCancelActionCancelsThatJob(): Unit = runBlocking {
        val request = ConversionWorker.request(
            inputUri = Uri.fromFile(input),
            displayName = input.name,
            sizeBytes = input.length(),
            spec = OutputFormat.MP4_H264.spec,
            quality = QualityTier.FAST,
        ).let { base ->
            // Rebuild with a delay so the job stays ENQUEUED for the whole test. See the KDoc.
            OneTimeWorkRequestBuilder<ConversionWorker>()
                .setInputData(base.workSpec.input)
                .setInitialDelay(1, TimeUnit.HOURS)
                .build()
        }
        workManager.enqueue(request).result.get()

        // The job is queued and waiting, which is the state the cancel has to interrupt.
        assertEquals(
            WorkInfo.State.ENQUEUED,
            withTimeout(TIMEOUT_MS) {
                workManager.getWorkInfoByIdFlow(request.id).first { it != null }
            }?.state,
        )

        val notification = ConversionNotifications(context)
            .build(request.id, title = input.name, percent = 0, indeterminate = true)
        val action = notification.actions?.firstOrNull()
        assertNotNull("the progress notification carries no action to cancel with", action)

        // The whole point: fire it the way the shade would, and see the job stop.
        action!!.actionIntent.send()

        val terminal = withTimeout(TIMEOUT_MS) {
            workManager.getWorkInfoByIdFlow(request.id).first { it != null && it.state.isFinished }
        }
        assertEquals(
            "firing the notification's Cancel action must cancel the job it was built for",
            WorkInfo.State.CANCELLED,
            terminal?.state,
        )
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }
}

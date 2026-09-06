package org.libremediaconverter.work

import android.app.Application
import android.app.Notification
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.work.Data
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.convert.ConcatJoiner
import org.libremediaconverter.convert.ConversionDependencies
import org.libremediaconverter.convert.installTestWorkManager
import org.libremediaconverter.ffmpeg.ConcatEngine
import org.libremediaconverter.model.ConcatStrategy
import org.libremediaconverter.model.DeviceCodecs
import org.libremediaconverter.model.EnginePreference
import org.libremediaconverter.model.InputProbe
import org.libremediaconverter.model.OutputFormat
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.UUID

/**
 * The first thing either worker posts is what its own `getForegroundInfo()` builds.
 *
 * **Both overrides were dead code until 2026-09-06, and #252 is where that was found** — the first
 * instrumented coverage read reported `ConversionWorker:342-346` and `ConcatWorker:132-136` among
 * the 32 lines *neither* suite reaches. The ticket's premise was that enqueueing expedited work
 * would make them live, since `getForegroundInfo()` is WorkManager's expedited-work hook.
 *
 * **That premise is false at this `minSdk`, which is the finding underneath the fix.**
 * `WorkForeground.kt:38` in work-runtime 2.11.2 opens `workForeground` with
 * `if (!spec.expedited || Build.VERSION.SDK_INT >= 31) return`, that function is the library's only
 * caller of `getForegroundInfoAsync()`, and `minSdk` is 33. So `setExpedited` alone would have left
 * both overrides exactly as cold as the read found them, and a test written to drive them through
 * WorkManager would be testing a code path no device this app supports can take — E1's failure
 * mode, where a test asserts and never reaches.
 *
 * What makes them live is a single-definition change instead. Each worker had **two** definitions
 * of one notification: the override, and an identical `ForegroundInfo` built inline in `doWork`.
 * `doWork` now posts the override's, so the copy nothing executed is gone and the one that remains
 * runs on every job.
 *
 * These tests are what hold that wiring. Each asserts the notification's *contents* against
 * constants rather than against `worker.getForegroundInfo()` — comparing the two would move
 * together under every mutation and stay green — and the mutations that redden them are named on
 * each test.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class ForegroundNotificationTest {

    private lateinit var app: Application
    private lateinit var updater: RecordingForegroundUpdater

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        updater = RecordingForegroundUpdater()
        ConversionDependencies.publisher = { AlwaysRoomPublisher(app) }
        ConversionDependencies.probe = { _, _ -> InputProbe() }
        ConversionDependencies.deviceCodecs = { DeviceCodecs.PERMISSIVE }
        ConversionDependencies.software = { WritingTranscoder }
        ConversionDependencies.concat = { WritingJoiner }
        // The notification carries a WorkManager cancel PendingIntent, so without this the worker
        // fails building the notification rather than on anything these tests are about.
        installTestWorkManager(app, Data.EMPTY)
    }

    @After
    fun tearDown() {
        ConversionDependencies.reset()
    }

    /**
     * Mutation that must go red, and did: inside `ConversionWorker.getForegroundInfo`, replace
     * `displayName()` with a literal, or `percent = 0` with anything else. Both are in the override's
     * own body, so a red here is proof `doWork` executes it rather than a copy of it.
     */
    @Test
    fun `a conversion's first foreground post is the one getForegroundInfo builds`() {
        runBlocking { conversionWorker().doWork() }

        val first = updater.infos.first()
        val extras = first.notification.extras
        assertEquals(
            "the notification has to name the file the user picked",
            DISPLAY_NAME,
            extras.getString(Notification.EXTRA_TITLE),
        )
        assertEquals("a conversion starts at zero", 0, extras.getInt(Notification.EXTRA_PROGRESS))
        assertTrue(
            "nothing is known about the length of the job yet, so the bar is indeterminate",
            extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE),
        )
        assertEquals(
            "the foreground service type is the regime's, not zero",
            ConversionForegroundType.current(),
            first.foregroundServiceType,
        )
    }

    /**
     * The count is the point.
     *
     * `getForegroundInfo` said `"Joining files"` and `doWork` said `"Joining N files"` — one
     * notification with two texts, and the one nothing ran was free to drift. Now there is one,
     * and it reads the input array itself so it can still answer before `doWork` has parsed
     * anything.
     *
     * Mutation that must go red, and did: replace the array read in `ConcatWorker.getForegroundInfo`
     * with a constant `0`, which yields `"Joining 0 files"`. Asserting merely that the title starts
     * with "Joining" would survive that, which is why the whole string is pinned.
     */
    @Test
    fun `a join's first foreground post counts the files it was given`() {
        runBlocking { joinWorker().doWork() }

        val first = updater.infos.first()
        assertEquals(
            "the notification has to say how many files are being joined",
            ConcatWorker.joiningTitle(INPUTS.size),
            first.notification.extras.getString(Notification.EXTRA_TITLE),
        )
        assertEquals(
            "the foreground service type is the regime's, not zero",
            ConversionForegroundType.current(),
            first.foregroundServiceType,
        )
    }

    /**
     * And the title is really the file's name rather than any string at all.
     *
     * [ConcatWorker.joiningTitle] is asserted above through the constant the worker itself uses, so
     * that assertion cannot catch the sentence being reworded — deliberately, since the wording is
     * not what the test is about. This one can: two files, two names, one worker each.
     */
    @Test
    fun `two conversions of differently named files post differently named notifications`() {
        runBlocking { conversionWorker(displayName = OTHER_NAME).doWork() }

        assertEquals(
            OTHER_NAME,
            updater.infos.first().notification.extras.getString(Notification.EXTRA_TITLE),
        )
    }

    private fun conversionWorker(displayName: String = DISPLAY_NAME) = TestListenableWorkerBuilder<ConversionWorker>(
        context = app,
        inputData = workDataOf(
            ConversionWorker.KEY_INPUT_URI to INPUT.toString(),
            ConversionWorker.KEY_DISPLAY_NAME to displayName,
            ConversionWorker.KEY_SIZE_BYTES to INPUT_BYTES,
            // FORCE_SOFTWARE is the one preference that decides without consulting the input,
            // and a file:// URI keeps the worker out of FFmpegKit's native SAF bridge.
            ConversionWorker.KEY_ENGINE_PREFERENCE to EnginePreference.FORCE_SOFTWARE.name,
        ),
        runAttemptCount = 0,
    ).setId(JOB_ID)
        .setForegroundUpdater(updater)
        .build()

    private fun joinWorker() = TestListenableWorkerBuilder<ConcatWorker>(
        context = app,
        inputData = workDataOf(
            ConcatWorker.KEY_INPUT_URIS to INPUTS.map(Uri::toString).toTypedArray(),
            ConcatWorker.KEY_TOTAL_BYTES to TOTAL_BYTES,
            ConcatWorker.KEY_FORMAT to OutputFormat.MP4_H264.name,
        ),
        runAttemptCount = 0,
    ).setId(JOB_ID)
        .setForegroundUpdater(updater)
        .build()

    private companion object {
        val INPUT: Uri = Uri.parse("file:///tmp/holiday.mp4")
        val INPUTS: List<Uri> = listOf(INPUT, Uri.parse("file:///tmp/holiday2.mp4"))
        const val DISPLAY_NAME = "holiday.mp4"
        const val OTHER_NAME = "birthday.mkv"
        const val INPUT_BYTES = 1_024L
        const val TOTAL_BYTES = 2_048L
        val JOB_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000252")
    }
}

/** A joiner that writes an output and reports a strategy; nothing here is about the engine. */
private object WritingJoiner : ConcatJoiner {
    override suspend fun join(inputs: List<Uri>, output: File, format: OutputFormat): ConcatEngine.Result {
        output.writeBytes(ByteArray(OUTPUT_BYTES))
        return ConcatEngine.Result(ConcatStrategy.STREAM_COPY, output)
    }

    private const val OUTPUT_BYTES = 512
}

package org.libremediaconverter.join

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.convert.MediaProbe
import org.libremediaconverter.convert.StagingNames
import org.libremediaconverter.ffmpeg.ConcatEngine
import org.libremediaconverter.ffmpeg.FFmpegEngine
import org.libremediaconverter.model.ConcatStrategy
import org.libremediaconverter.work.ConcatWorker
import java.io.File

/**
 * The join path, end to end on a device.
 *
 * The point of these tests is the strategy decision, not merely that a file appears.
 * FFmpeg's `concat` demuxer does not reliably reject mismatched inputs -- it can emit a
 * file whose later segments are garbled -- so "it produced output" is not evidence of
 * correctness. Each test therefore checks which strategy ran *and* that the result is
 * long enough to contain both inputs.
 */
@RunWith(AndroidJUnit4::class)
class ConcatEngineTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine = ConcatEngine(context)
    private val staged = mutableListOf<File>()

    private lateinit var clipA: File
    private lateinit var clipB: File
    private lateinit var clipMismatched: File

    @Before
    fun setUp() {
        clipA = copyAsset("clip_a.mp4")
        clipB = copyAsset("clip_b.mp4")
        clipMismatched = copyAsset("clip_c_mismatched.mp4")
    }

    @After
    fun tearDown() {
        (staged + listOf(clipA, clipB, clipMismatched)).forEach { it.delete() }
    }

    /**
     * Cancelling a *running* join actually stops the native session.
     *
     * The `FFmpegEngine` half of #224 landed first (PR #236); this is the same gap in
     * [ConcatEngine]. Before these two, no test on any source set had ever asked a real native
     * session to stop — every `cancel` in `app/src/androidTest` targets WorkManager entries that
     * are queued or already finished.
     *
     * ## Two things carried over from the conversion side, both measured there
     *
     * **The assertion is the session's return code.** A cancelled session ends with the cancel
     * code, a completed one does not. The alternative — checking the output file — is even less
     * available here than it was for conversions: [ConcatEngine] does not delete its output on
     * cancellation at all. Its `invokeOnCancellation` is `FFmpegKit.cancel(...)` and nothing else,
     * where [org.libremediaconverter.ffmpeg.FFmpegEngine]'s also deletes the partial. Whether that
     * asymmetry is deliberate is a separate question from this test, which is why this asserts the
     * thing that is true of both.
     *
     * **The cancel is triggered on [SessionState.RUNNING], not on progress.** `ConcatWorker`
     * publishes no progress at all, so there is no callback to hang it on even in principle — but
     * the conversion side established the deeper reason: the committed clips are 2 s at 320x240 and
     * the encode outruns a callback-triggered cancel.
     *
     * **And the attempt is retried**, for the reason the conversion side measured the hard way: on
     * a loaded runner the thread that observed `RUNNING` can be descheduled long enough for a short
     * encode to finish before it calls `cancel`, which failed two CI legs there. An attempt whose
     * session finished first has tested nothing, so it is a miss rather than a failure; only
     * exhausting [CANCEL_ATTEMPTS] fails, and with `FFmpegKit.cancel` removed every attempt misses,
     * so the mutation still bites.
     *
     * The inputs are deliberately the **mismatched** pair, so [ConcatStrategy.REENCODE] is chosen.
     * A stream copy of two short clips is close to instantaneous and would leave nothing to
     * interrupt; re-encoding is the case where a user would actually reach for Cancel.
     *
     * *Mutation:* drop `FFmpegKit.cancel(session.getSessionId())` from `ConcatEngine`'s
     * `invokeOnCancellation` — the session runs to completion and this fails.
     */
    @Test
    fun cancellingARunningJoinCancelsTheNativeSession(): Unit = runBlocking {
        val outcomes = mutableListOf<String>()

        repeat(CANCEL_ATTEMPTS) { attempt ->
            val before = FFmpegKit.listSessions().map { it.getSessionId() }.toSet()
            val out = output("cancelled_join_$attempt.mp4")

            val job = launch(Dispatchers.IO) {
                engine.join(
                    listOf(Uri.fromFile(clipA), Uri.fromFile(clipMismatched)),
                    out,
                    ConcatWorker.DEFAULT_FORMAT,
                )
            }

            val ours = withTimeout(TIMEOUT_MS) {
                var found: FFmpegSession? = null
                while (found == null) {
                    found = FFmpegKit.listSessions().firstOrNull { it.getSessionId() !in before }
                    if (found == null) delay(POLL_MS)
                }
                found
            }
            job.cancelAndJoin()
            withTimeout(TIMEOUT_MS) {
                while (ours.getState() == SessionState.RUNNING) delay(POLL_MS)
            }

            if (ReturnCode.isCancel(ours.getReturnCode())) return@runBlocking
            outcomes += "state=${ours.getState()} rc=${ours.getReturnCode()}"
        }

        fail(
            "never interrupted a running join in $CANCEL_ATTEMPTS attempts, so either every " +
                "encode finished first or cancellation does not reach it: $outcomes",
        )
    }

    private fun copyAsset(name: String): File {
        val out = File(context.cacheDir, name)
        InstrumentationRegistry.getInstrumentation().context.assets
            .open(name)
            .use { asset -> out.outputStream().use { asset.copyTo(it) } }
        return out
    }

    private fun output(name: String) = File(context.cacheDir, name).also {
        it.delete()
        staged += it
    }

    private fun durationMs(file: File): Long {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount)
                .map { extractor.getTrackFormat(it) }
                .filter { it.containsKey(MediaFormat.KEY_DURATION) }
                .maxOfOrNull { it.getLong(MediaFormat.KEY_DURATION) / 1000 } ?: 0L
        } finally {
            extractor.release()
        }
    }

    // --- the fast path ------------------------------------------------------

    @Test
    fun matchingClipsAreJoinedByStreamCopy(): Unit = runBlocking {
        val out = output("joined_matching.mp4")
        val result = engine.join(listOf(Uri.fromFile(clipA), Uri.fromFile(clipB)), out)

        assertEquals(
            "identical inputs should not need re-encoding",
            ConcatStrategy.STREAM_COPY,
            result.strategy,
        )
        assertTrue("no output produced", out.exists() && out.length() > 0)
        // Both 2 s inputs must be present, not just the first.
        assertTrue(
            "joined duration ${durationMs(out)}ms is too short to hold both clips",
            durationMs(out) >= 3_500,
        )
    }

    // --- the correctness path ----------------------------------------------

    @Test
    fun mismatchedClipsAreReEncodedRatherThanStreamCopied(): Unit = runBlocking {
        val out = output("joined_mismatched.mp4")
        val result = engine.join(listOf(Uri.fromFile(clipA), Uri.fromFile(clipMismatched)), out)

        // This is the case a naive implementation gets wrong: the demuxer would accept
        // these and produce a corrupt second half.
        assertEquals(
            "differing resolution must force a re-encode",
            ConcatStrategy.REENCODE,
            result.strategy,
        )
        assertTrue("no output produced", out.exists() && out.length() > 0)
        assertTrue(
            "joined duration ${durationMs(out)}ms is too short to hold both clips",
            durationMs(out) >= 3_500,
        )
    }

    @Test
    fun reEncodedOutputIsPlayableAndCarriesBothTracks(): Unit = runBlocking {
        val out = output("joined_playable.mp4")
        engine.join(listOf(Uri.fromFile(clipA), Uri.fromFile(clipMismatched)), out)

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(out.absolutePath)
            val mimes = (0 until extractor.trackCount).map {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty()
            }
            assertTrue("no video track in $mimes", mimes.any { it.startsWith("video/") })
            assertTrue("no audio track in $mimes", mimes.any { it.startsWith("audio/") })
        } finally {
            extractor.release()
        }
    }

    // --- guards -------------------------------------------------------------

    @Test
    fun joiningRefusesFewerThanTwoInputs() {
        val out = output("joined_single.mp4")
        val failure = runCatching {
            runBlocking { engine.join(listOf(Uri.fromFile(clipA)), out) }
        }.exceptionOrNull()
        assertTrue(
            "expected an IllegalArgumentException, got $failure",
            failure is IllegalArgumentException,
        )
    }

    /**
     * A failed join tells the user the return code and what FFmpeg said.
     *
     * **This is the device half of #203/#217**, whose PR closed by noting the join legs had not
     * been run. Running them would not have answered it: nothing on either source set drove a real
     * join *failure*, so the unified message was asserted only against values a JVM test hands to
     * `sessionOutcome` directly.
     *
     * What is device-only here is that the three reads behind that message work against a real
     * native session at all — `getReturnCode`, `getFailStackTrace` and `getAllLogsAsString`. If
     * the log tail came back null or empty on a device, the user would get `Joining failed (1): `
     * with nothing after the colon and every JVM test would still pass.
     *
     * **What this deliberately does not pin is the preference between the two detail sources.** On
     * an ordinary non-zero return code FFmpegKit reports no fail stack trace, so the stack-trace-
     * first rule and the log-tail-first rule produce the same text and no assertion here can tell
     * them apart. That ordering is [SessionOutcomeTest][org.libremediaconverter.ffmpeg.SessionOutcomeTest]'s
     * job, where both sources can be non-blank at once. Asserting it here would be a test whose
     * KDoc claims more than it checks — the `probeForConcat` mistake wave 3 caught.
     *
     * The failure is forced with an input that does not exist, which the concat demuxer rejects
     * the same way on every FFmpeg build, rather than with malformed media whose handling varies.
     */
    @Test
    fun aFailedJoinReportsTheReturnCodeAndWhatFFmpegSaid(): Unit = runBlocking {
        val missing = File(context.cacheDir, "no_such_clip.mp4").also { it.delete() }
        val out = output("joined_failure.mp4")

        val failure = runCatching {
            engine.join(listOf(Uri.fromFile(clipA), Uri.fromFile(missing)), out)
        }.exceptionOrNull()

        assertTrue(
            "a join over a missing input must fail, got $failure",
            failure is FFmpegEngine.FFmpegException,
        )
        val message = failure?.message.orEmpty()
        assertTrue(
            "the message must name the operation and carry the return code, was: '$message'",
            message.startsWith("Joining failed ("),
        )
        // The half a JVM test cannot reach: a real session actually produced detail to show.
        val detail = message.substringAfter("): ", "")
        assertTrue(
            "the message stopped at the return code and told the user nothing, was: '$message'",
            detail.isNotBlank(),
        )
    }

    @Test
    fun theListFileIsCleanedUpAfterJoining(): Unit = runBlocking {
        val out = output("joined_cleanup.mp4")
        engine.join(listOf(Uri.fromFile(clipA), Uri.fromFile(clipB)), out)
        // Asked of StagingNames rather than spelled out: the list file used to be the constant
        // concat_list.txt, and a literal here would have gone on passing vacuously once the name
        // moved -- it would be asserting that a file nothing creates does not exist.
        assertTrue(
            "the concat list file was left behind",
            !File(out.parentFile, StagingNames.concatListFor(out.name)).exists(),
        )
    }

    // --- the probe the planner depends on ----------------------------------

    @Test
    fun probeReadsThePropertiesTheStrategyDependsOn() {
        val a = MediaProbe.probeForConcat(context, Uri.fromFile(clipA))
        val mismatched = MediaProbe.probeForConcat(context, Uri.fromFile(clipMismatched))

        assertEquals("h264", a.videoCodec)
        assertEquals(320, a.width)
        assertEquals(240, a.height)

        assertEquals(640, mismatched.width)
        assertEquals(480, mismatched.height)
        assertTrue(
            "the probe must actually distinguish these clips, or the planner cannot",
            a.width != mismatched.width || a.height != mismatched.height,
        )
    }

    private companion object {
        /** Generous: it bounds a hang, and both waits here normally settle in well under a second. */
        const val TIMEOUT_MS = 30_000L
        const val POLL_MS = 50L

        /** See the conversion side: a miss is the loaded-runner case, not a defect. */
        const val CANCEL_ATTEMPTS = 5
    }
}

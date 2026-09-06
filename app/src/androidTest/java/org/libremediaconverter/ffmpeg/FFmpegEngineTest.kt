package org.libremediaconverter.ffmpeg

import android.media.MediaExtractor
import android.media.MediaFormat
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
import org.libremediaconverter.model.ConversionRequest
import org.libremediaconverter.model.OutputFormat
import org.libremediaconverter.model.QualityTier
import java.io.File

/**
 * Exercises the bundled FFmpeg on-device.
 *
 * These are the formats the app exists for that Media3 structurally cannot produce, so
 * they are also the ones with no other coverage. Each test asserts against the produced
 * file rather than the exit code, because FFmpeg will happily report success after
 * writing something unplayable if the arguments are wrong.
 */
@RunWith(AndroidJUnit4::class)
class FFmpegEngineTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine: org.libremediaconverter.convert.SoftwareTranscoder = FFmpegEngine()
    private lateinit var input: File
    private val outputs = mutableListOf<File>()

    @Before
    fun setUp() {
        input = File(context.cacheDir, "ffmpeg_sample.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("sample_h264.mp4")
            .use { asset -> input.outputStream().use { asset.copyTo(it) } }
    }

    @After
    fun tearDown() {
        input.delete()
        outputs.forEach { it.delete() }
    }

    private fun outputFor(name: String) = File(context.cacheDir, name).also {
        it.delete()
        outputs += it
    }

    private fun convert(format: OutputFormat, quality: QualityTier = QualityTier.BEST): File {
        val out = outputFor("out_${format.name.lowercase()}.${format.extension}")
        runBlocking {
            engine.run(
                request = ConversionRequest(spec = format.spec, quality = quality),
                inputPath = input.absolutePath,
                output = out,
                durationMs = 3_000,
            )
        }
        return out
    }

    private fun trackMimes(file: File): List<String> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).map {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty()
            }
        } finally {
            extractor.release()
        }
    }

    // --- the formats that justify bundling FFmpeg at all -------------------

    @Test
    fun encodesMp3WhichAndroidCannotDoAtAnyApiLevel() {
        val out = convert(OutputFormat.MP3)
        assertTrue("no MP3 produced", out.exists() && out.length() > 0)
        assertTrue(
            "expected an mpeg audio track, got ${trackMimes(out)}",
            trackMimes(out).any { it.contains("mp") && it.startsWith("audio/") },
        )
    }

    @Test
    fun encodesGifWithAGeneratedPalette() {
        val out = convert(OutputFormat.GIF)
        assertTrue("no GIF produced", out.exists() && out.length() > 0)
        // GIF87a / GIF89a magic. Proves a real GIF rather than a mislabelled file.
        val magic = out.inputStream().use { String(it.readNBytes(6)) }
        assertTrue("not a GIF: $magic", magic.startsWith("GIF"))
    }

    @Test
    fun encodesMatroskaWhichMedia3CannotMux() {
        val out = convert(OutputFormat.MKV_H264)
        assertTrue("no MKV produced", out.exists() && out.length() > 0)
        // EBML magic, the Matroska container header.
        val magic = out.inputStream().use { it.readNBytes(4) }
        assertEquals(0x1A.toByte(), magic[0])
        assertEquals(0x45.toByte(), magic[1])
        assertEquals(0xDF.toByte(), magic[2])
        assertEquals(0xA3.toByte(), magic[3])
    }

    @Test
    fun encodesFlacLosslessAudio() {
        val out = convert(OutputFormat.FLAC)
        assertTrue("no FLAC produced", out.exists() && out.length() > 0)
        // "fLaC", the native FLAC stream marker. Without this the test passed on any non-empty
        // file, so a builder arm emitting the wrong encoder into a .flac name shipped green
        // (#228) -- the same shape the five assertions above already guard against.
        val magic = out.inputStream().use { String(it.readNBytes(4), Charsets.US_ASCII) }
        assertEquals("fLaC", magic)
    }

    @Test
    fun encodesWav() {
        val out = convert(OutputFormat.WAV)
        assertTrue("no WAV produced", out.exists() && out.length() > 0)
        val magic = out.inputStream().use { String(it.readNBytes(4)) }
        assertEquals("RIFF", magic)
    }

    @Test
    fun encodesOpus() {
        val out = convert(OutputFormat.OPUS)
        assertTrue("no Opus produced", out.exists() && out.length() > 0)
        // OutputFormat.OPUS is Container.OGG, so the file is an Ogg stream: "OggS" (#228).
        // Deliberately the container marker rather than the codec -- it is what the other
        // container-level assertions in this class check, and it is four bytes at offset 0.
        val magic = out.inputStream().use { String(it.readNBytes(4), Charsets.US_ASCII) }
        assertEquals("OggS", magic)
    }

    /**
     * The percentage itself, which every other test in this class computes and none of them reads.
     *
     * `FFmpegEngine` derives progress as `stats.time / durationMs * 100`, and the statistics
     * callback runs on every conversion here — but every call site omits `onProgress`, so until
     * this test nothing on any source set had ever looked at the number (#229). #196 covered the
     * *worker's* progress lambda, and did it with a fake engine that reports whatever the test
     * tells it to; `ProgressNotificationTest` covers throttling the same way. The arithmetic was
     * the one part with no reader.
     *
     * ## Why the duration is deliberately wrong
     *
     * `sample_h264.mp4` is exactly 3.000 s, and this passes **30 s** as the duration. So the
     * conversion still encodes the whole clip, `stats.time` still climbs to about 3000 ms, and the
     * reported percentage tops out around **10** rather than 100.
     *
     * That is what makes the assertion bite. A range check alone is worthless here: replacing
     * `percent` with a constant `0` satisfies "every value is in 0..100" and "the values never go
     * backwards", and so does a list of `[0, 100]`. Pinning the *band* rejects every constant, and
     * — because the band is a tenth of the way up — it also rejects an implementation that ignores
     * `durationMs`, which would report ~100 for the same run.
     *
     * The bound is deliberately loose (5..25 for an expected 10). The last statistics callback can
     * land slightly before the final frame, so the peak is "about 3000 ms of a claimed 30 000",
     * not exactly it.
     */
    @Test
    fun progressIsReportedAsAFractionOfTheDurationItWasGiven() {
        val seen = mutableListOf<Int>()
        val out = outputFor("out_progress.mp4")
        runBlocking {
            engine.run(
                request = ConversionRequest(spec = OutputFormat.MP4_H264.spec, quality = QualityTier.BEST),
                inputPath = input.absolutePath,
                output = out,
                // Ten times the fixture's real 3 s. See the KDoc.
                durationMs = 30_000,
                onProgress = { percent -> seen += percent },
            )
        }

        assertTrue("the statistics callback never reported progress", seen.isNotEmpty())
        assertTrue("progress out of range: $seen", seen.all { it in 0..100 })
        assertEquals("progress went backwards: $seen", seen.sorted(), seen)
        // The band. Rejects any constant, and rejects ignoring durationMs (which would read ~100).
        val peak = seen.max()
        assertTrue(
            "3 s of media against a claimed 30 s should peak near 10%, got $peak from $seen",
            peak in 5..25,
        )
    }

    /**
     * Cancelling a *running* conversion actually stops the native session.
     *
     * Nothing on any source set did this before (#224). Every `cancel` in `app/src/androidTest` is
     * `WorkManager.cancelWorkById` against work that is **queued or already finished** — the two in
     * `ReattachOnLaunchTest` cancel a job carrying a one-hour initial delay, and one immediately
     * after enqueue. On the JVM, `WorkerCancellationTest` and `HardwareFallbackTest`'s cancellation
     * case drive a `SoftwareTranscoder` double that records the call. No test had ever asked a real
     * native session to stop. This is `docs/defect-audit.md` **D10**'s forcing condition.
     *
     * It is the one path where cancelling wrong is silently expensive rather than loudly broken: a
     * missed `FFmpegKit.cancel` leaves the native process encoding to completion while the UI says
     * the job is cancelled, and nothing reports the battery and thermal cost.
     *
     * ## Why the assertion is the session's return code, not the output file
     *
     * The obvious assertion — the partial output is gone — **cannot fail**, so it would have been a
     * vacuous test. `invokeOnCancellation` deletes the path, and on POSIX unlinking a file ffmpeg
     * still holds open leaves ffmpeg writing to the unlinked inode; the path stays gone whether or
     * not the cancel ever reached the session. Deleting `FFmpegKit.cancel` and keeping
     * `output.delete()` passes that check every time.
     *
     * What distinguishes them is the session's own verdict: a cancelled session ends with the
     * cancel return code, a completed one ends successfully. That is a fact about the session
     * rather than about timing, so it is read *after* waiting for the session to leave
     * [SessionState.RUNNING] rather than at a fixed delay.
     *
     * ## Why it cancels on RUNNING rather than on the first progress callback
     *
     * Measured. Cancelling from the first `onProgress` was tried first and **failed on a local API
     * 34 emulator with `state=COMPLETED rc=0`** — every committed fixture is 2-3 s at 320x240, and
     * the encode finishes before the first statistics callback has been delivered and acted on. The
     * progress callback proves the session is running, but arrives too late to interrupt anything.
     * `FFmpegKit.listSessions` shows the session [SessionState.RUNNING] far earlier.
     *
     * ## Why it retries, which is the part that took two attempts to get right
     *
     * Waiting for `RUNNING` is not on its own enough. With `MP4_H265` at [QualityTier.BEST] this
     * passed four consecutive local runs and all five CI legs, then failed on the API 34 and 35 legs
     * of the next PR with `state=COMPLETED rc=0`. Nothing had changed: on a loaded runner the thread
     * that observed `RUNNING` can be descheduled long enough for a short encode to finish before it
     * calls `cancel`. A longer timeout does not help — the wait already succeeded.
     *
     * Two changes together, because neither is sufficient:
     *
     *  - **A slower encode.** `WEBM_VP9` at `BEST` is the slowest thing this builder emits:
     *    `libvpx-vp9 -crf 31 -b:v 0`, with `-deadline realtime` added **only** on
     *    [QualityTier.FAST]. Probed on an API 34 emulator, that session is still `RUNNING` at 1 s
     *    and finished by 2 s, against well under a second for x265 `-preset medium`.
     *  - **Retrying the attempt.** An attempt whose session finished before the cancel landed has
     *    not tested anything, so it is not a failure — it is a miss, and it is retried. Only
     *    exhausting [CANCEL_ATTEMPTS] is a failure, and its message says which case it hit.
     *
     * That keeps the mutation honest: with `FFmpegKit.cancel` removed **every** attempt ends
     * `COMPLETED`, so the test still fails — it just takes [CANCEL_ATTEMPTS] tries to say so.
     *
     * The session is identified by diffing against the ids present before each attempt, because
     * this class has already produced eight of them by the time this executes.
     */
    @Test
    fun cancellingARunningConversionCancelsTheNativeSession(): Unit = runBlocking {
        val outcomes = mutableListOf<String>()

        repeat(CANCEL_ATTEMPTS) { attempt ->
            val before = FFmpegKit.listSessions().map { it.getSessionId() }.toSet()
            val out = outputFor("out_cancelled_$attempt.webm")

            val job = launch(Dispatchers.IO) {
                engine.run(
                    // The slowest target this builder emits -- see the KDoc. Not decoration:
                    // with a faster one this loses the race on a loaded CI runner.
                    request = ConversionRequest(spec = OutputFormat.WEBM_VP9.spec, quality = QualityTier.BEST),
                    inputPath = input.absolutePath,
                    output = out,
                    durationMs = 3_000,
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
            // The encode beat us to it. That attempt proved nothing either way, so try again.
            outcomes += "state=${ours.getState()} rc=${ours.getReturnCode()}"
        }

        fail(
            "never interrupted a running session in $CANCEL_ATTEMPTS attempts, so either every " +
                "encode finished first or cancellation does not reach it: $outcomes",
        )
    }

    // --- the quality tier the GPL licence was taken for --------------------

    @Test
    fun bestQualityProducesAPlayableH264File() {
        val out = convert(OutputFormat.MP4_H264, QualityTier.BEST)
        assertTrue("no MP4 produced", out.exists() && out.length() > 0)
        assertTrue(
            "expected an AVC track, got ${trackMimes(out)}",
            trackMimes(out).any { it == MediaFormat.MIMETYPE_VIDEO_AVC },
        )
    }

    @Test
    fun bestQualityProducesAPlayableH265File() {
        val out = convert(OutputFormat.MP4_H265, QualityTier.BEST)
        assertTrue("no MP4 produced", out.exists() && out.length() > 0)
        assertTrue(
            "expected an HEVC track, got ${trackMimes(out)}",
            trackMimes(out).any { it == MediaFormat.MIMETYPE_VIDEO_HEVC },
        )
    }

    @Test
    fun failureSurfacesAsAnExceptionRatherThanASilentEmptyFile() {
        val out = outputFor("nope.mp4")
        val failure = runCatching {
            runBlocking {
                engine.run(
                    request = ConversionRequest(spec = OutputFormat.MP4_H264.spec),
                    inputPath = "/does/not/exist.mp4",
                    output = out,
                    durationMs = 1_000,
                )
            }
        }.exceptionOrNull()
        assertTrue("expected an FFmpegException, got $failure", failure is FFmpegEngine.FFmpegException)
    }

    private companion object {
        /** Generous: it bounds a hang, and every wait here normally settles in well under a second. */
        const val TIMEOUT_MS = 30_000L
        const val POLL_MS = 50L

        /**
         * How many times to try to catch the session mid-encode.
         *
         * Each miss costs about the length of one VP9 encode -- a second or two -- and a miss is
         * the loaded-runner case rather than a defect. Five is enough that exhausting them means
         * cancellation is not reaching the session, which is what the failure message says.
         */
        const val CANCEL_ATTEMPTS = 5
    }
}

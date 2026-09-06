package org.libremediaconverter.convert

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.FailsOnEmulatorApi37
import org.libremediaconverter.model.AudioCodec
import org.libremediaconverter.model.AudioPlan
import org.libremediaconverter.model.Container
import org.libremediaconverter.model.ConversionRequest
import org.libremediaconverter.model.CopyPlanner
import org.libremediaconverter.model.InputKind
import org.libremediaconverter.model.InputProbe
import org.libremediaconverter.model.OutputFormat
import org.libremediaconverter.model.OutputSpec
import org.libremediaconverter.model.VideoCodec
import org.libremediaconverter.model.VideoPlan
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * End-to-end hardware transcode through [Media3Engine].
 *
 * This is the Phase 1 verification: it proves the MediaCodec pipeline actually runs on
 * a device, and — more importantly — that the engine can be driven from a thread with
 * no Looper of its own without tripping Transformer's single-thread requirement.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class Media3EngineTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var engine: Media3Engine
    private lateinit var input: File
    private lateinit var output: File

    @Before
    fun setUp() {
        engine = Media3Engine(context)
        input = File(context.cacheDir, "sample_h264.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("sample_h264.mp4")
            .use { asset -> input.outputStream().use { asset.copyTo(it) } }
        output = File(context.cacheDir, "out_hevc.mp4")
        output.delete()
    }

    @After
    fun tearDown() {
        engine.close()
        input.delete()
        output.delete()
    }

    @Test
    @FailsOnEmulatorApi37
    fun transcodesH264ToH265AndReportsProgress(): Unit = runBlocking {
        val seen = mutableListOf<Int>()

        engine.transcode(
            input = Uri.fromFile(input),
            output = output,
            request = ConversionRequest(OutputFormat.MP4_H265.spec),
        ) { percent -> seen += percent }

        assertTrue("export produced no file", output.exists())
        assertTrue("export produced an empty file", output.length() > 0)
        // Assert against the muxed file, not just the reported result: this is what
        // actually proves the output is HEVC rather than a silent fallback to H.264.
        assertEquals(MimeTypes.VIDEO_H265, videoMimeTypeOf(output))
        // Assert against the muxed file rather than the engine's own report: a result
        // object can claim success for a file that will not play.
        assertTrue("output has no duration", durationMsOf(output) > 0)
        // Deliberately NOT asserting that progress fired. Polling is on a 250 ms tick,
        // and a 3 s 320x240 clip can finish inside one tick on fast hardware, which
        // would make the assertion fail intermittently for no real defect.
        seen.forEach { assertTrue("progress out of range: $it", it in 0..100) }
    }

    /**
     * Regression guard for the audio-extraction bug.
     *
     * [OutputFormat.M4A_AAC] declares `VideoCodec.NONE`, and the router sends it to Media3. But
     * the engine used to build a bare `EditedMediaItem` and take a video MIME type that defaulted
     * to HEVC, so "extract the audio" transcoded the *video* to H.265 and wrote it to a file named
     * `.m4a`. Nothing failed; the output was simply not what was asked for.
     *
     * This is the test the suite was missing — [Media3EngineTest] had no audio-only case at all,
     * which is why the defect survived.
     */
    @Test
    fun audioOnlyExportDropsTheVideoTrack(): Unit = runBlocking {
        val audio = File(context.cacheDir, "out_audio.m4a")
        audio.delete()
        try {
            engine.transcode(Uri.fromFile(input), audio, ConversionRequest(OutputFormat.M4A_AAC.spec))

            assertTrue("export produced no file", audio.exists() && audio.length() > 0)
            val tracks = trackMimeTypesOf(audio)
            assertEquals("expected exactly one track, got $tracks", 1, tracks.size)
            assertEquals(MimeTypes.AUDIO_AAC, tracks.single())
            assertNull("an audio-only export must carry no video track", videoMimeTypeOf(audio))
            assertTrue("output has no duration", durationMsOf(audio) > 0)
        } finally {
            audio.delete()
        }
    }

    /**
     * Regression guard for the Transformer threading trap.
     *
     * Transformer binds to the Looper of the thread that built it, falling back to the
     * main Looper when that thread has none — and then throws IllegalStateException
     * when start() is called from elsewhere. A WorkManager Worker runs on exactly such
     * a Looper-less thread, so this test drives the engine from one to prove the
     * HandlerThread indirection holds before any of that lands in Phase 2.
     */
    @Test
    @FailsOnEmulatorApi37
    fun runsFromAThreadWithNoLooper() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            val task = pool.submit<Throwable?> {
                check(android.os.Looper.myLooper() == null) {
                    "precondition failed: this thread should have no Looper"
                }
                runCatching {
                    runBlocking { engine.transcode(Uri.fromFile(input), output) }
                }.exceptionOrNull()
            }
            val failure = task.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertTrue(
                "transcode from a Looper-less thread failed: $failure",
                failure == null,
            )
            assertTrue(output.exists() && output.length() > 0)
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Transformer.start() failing synchronously.
     *
     * Previously written off as Media3-internal and unreachable. It is not: an output
     * path whose parent directory does not exist makes start() fail, and the engine has
     * to surface that as a rejected suspension rather than hanging forever waiting for
     * a listener callback that will never come. A hang here would be far worse than an
     * exception, because the worker would sit holding a foreground service.
     */
    @Test
    fun anUnwritableOutputPathFailsInsteadOfHanging() {
        val impossible = File("/does/not/exist/nested/out.mp4")
        val failure = runCatching {
            runBlocking {
                withTimeout(30_000) {
                    engine.transcode(Uri.fromFile(input), impossible, ConversionRequest(OutputFormat.MP4_H265.spec)) {}
                }
            }
        }.exceptionOrNull()

        assertTrue(
            "an unwritable output must raise, not hang or silently pass; got $failure",
            failure != null && failure !is kotlinx.coroutines.TimeoutCancellationException,
        )
    }

    /**
     * The builders that used to throw where nothing could catch them.
     *
     * `EditedMediaItem.Builder` rejects a composition with both tracks removed —
     * checkState("Audio and video cannot both be removed") — and the engine builds it on its own
     * HandlerThread. That build sat *between* two narrow `runCatching` blocks, one around
     * `buildTransformer` and one around `start`, so the exception reached the thread's uncaught
     * handler and took the process with it while the continuation was never resumed.
     *
     * `ContainerCapabilities.validate` now refuses the spec that gets here from the picker; this
     * is the other half — the engine surviving a request that arrives without being validated.
     * Deliberately not `@FailsOnEmulatorApi37`: nothing here decodes or encodes, so no emulator
     * codec is involved. The builder refuses the input before any media is touched.
     */
    @Test
    fun aPlanThatRemovesBothTracksFailsInsteadOfKillingTheProcess() {
        val request = ConversionRequest(
            spec = OutputSpec(Container.MP4, VideoCodec.H265, AudioCodec.NONE),
            probe = InputProbe(
                videoCodec = null,
                audioCodec = "mp3",
                hasVideo = false,
                container = Container.MP3,
                kind = InputKind.AUDIO_ONLY,
            ),
        )
        // Asserted rather than assumed: ConversionRequest's default probe says hasVideo = true,
        // and with it this same spec plans to (Encode, Drop) and nothing throws at all — which
        // would make the whole test vacuous without a word of warning.
        val plan = CopyPlanner.plan(request.spec, request.probe)
        assertEquals(VideoPlan.Drop, plan.video)
        assertEquals(AudioPlan.Drop, plan.audio)

        val failure = runCatching {
            runBlocking {
                withTimeout(BUILDER_TIMEOUT_MS) {
                    engine.transcode(Uri.fromFile(input), output, request) {}
                }
            }
        }.exceptionOrNull()

        // Two assertions, and the second is not pedantry. withTimeout raises
        // TimeoutCancellationException, and `java.util.concurrent.CancellationException` *extends*
        // IllegalStateException — so testing only the type below would call an unresumed
        // continuation a pass. A hang is the other half of this defect and every bit as bad as the
        // crash: the worker would sit holding a foreground service forever.
        assertFalse(
            "the continuation was never resumed — the failure escaped instead of being reported: " +
                "$failure",
            failure is CancellationException,
        )
        assertTrue(
            "the builder's refusal must surface as a failed job, not a dead process; got $failure",
            failure is IllegalStateException,
        )
    }

    private fun durationMsOf(file: File): Long {
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

    private fun trackMimeTypesOf(file: File): List<String> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount)
                .map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
        } finally {
            extractor.release()
        }
    }

    /**
     * Cancelling a *running* export stops it, completing #224's third engine.
     *
     * The two FFmpeg engines were done first (`ad2a75d`, `d293646`); this is
     * `Media3Engine.transcode`'s `invokeOnCancellation`, which posts `transformer.cancel()` onto the
     * engine's own `HandlerThread` because `cancel()` has the same single-thread requirement as
     * `start()`.
     *
     * ## Why the assertion is the output file here, and was not for FFmpeg
     *
     * The FFmpeg side could not use the file: `invokeOnCancellation` unlinks it, and on POSIX ffmpeg
     * keeps writing to the unlinked inode, so the path stays gone whether or not the cancel landed.
     * It asserted the session's return code instead.
     *
     * `Media3Engine` deletes nothing — the partial is `ConversionWorker`'s to clean up — so the file
     * *is* the evidence. An export that was cancelled leaves no moov atom, so `MediaExtractor`
     * either finds no video track or refuses the file outright with
     * `IOException: Failed to instantiate extractor` — measured, and both mean interrupted. One
     * that ran to completion leaves a playable HEVC file, which is the only outcome treated as a
     * miss. The wait before
     * reading it is deliberately several times the length of the export, so a *non*-cancelled export
     * has certainly finished by then: the failure direction is "the file became valid", never "we
     * did not wait long enough".
     *
     * ## Why it retries
     *
     * Same reason as the other two, measured there: the committed fixture is 3 s at 320x240 and the
     * export outruns a naive cancel on a loaded runner. An attempt whose export finished before the
     * cancel landed has tested nothing, so it is a miss and is retried; only exhausting
     * [CANCEL_ATTEMPTS] fails. With `transformer.cancel()` removed every attempt produces a playable
     * file, so the mutation still bites — it just takes five tries to say so.
     *
     * Progress having been reported is what proves the export really started, so a miss is
     * distinguishable from an export that never ran at all — which matters on the API 37 image,
     * where the decoder is what fails.
     */
    @Test
    @FailsOnEmulatorApi37
    fun cancellingARunningExportStopsIt(): Unit = runBlocking {
        val outcomes = mutableListOf<String>()

        repeat(CANCEL_ATTEMPTS) { attempt ->
            val partial = File(context.cacheDir, "cancelled_export_$attempt.mp4").apply { delete() }

            val job = launch(Dispatchers.IO) {
                engine.transcode(
                    input = Uri.fromFile(input),
                    output = partial,
                    request = ConversionRequest(OutputFormat.MP4_H265.spec),
                )
            }

            // The muxer creating the file is proof the export really started, and it is the
            // earliest such proof available -- earlier than the first progress tick.
            withTimeout(TIMEOUT_MS) {
                while (!partial.exists() && job.isActive) delay(POLL_MS)
            }
            val started = partial.exists()
            job.cancelAndJoin()

            if (!started) {
                // The export failed before writing anything. That is not a cancellation result
                // either way, so it is not allowed to pass as one.
                outcomes += "attempt $attempt never produced an output file to cancel"
                return@repeat
            }

            // Several times the export's own length, so a cancel that did not land has certainly
            // finished. The failure direction is "the file became playable", never "too soon".
            delay(SETTLE_MS)

            // A cancelled export reports itself two ways and both mean the same thing: no video
            // track, or MediaExtractor refusing the file outright with "Failed to instantiate
            // extractor" because there is no moov atom to read. Only a *playable* file is a miss.
            val video = runCatching { videoMimeTypeOf(partial) }.getOrNull()
            partial.delete()
            if (video == null) return@runBlocking
            outcomes += "attempt $attempt produced a playable $video"
        }

        fail(
            "never interrupted a running export in $CANCEL_ATTEMPTS attempts, so either every " +
                "export finished first or cancellation does not reach the transformer: $outcomes",
        )
    }

    private fun videoMimeTypeOf(file: File): String? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) return mime
            }
            return null
        } finally {
            extractor.release()
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 120L

        /** Bounds the wait for the muxer to create the file; a hang here is a defect. */
        const val TIMEOUT_MS = 30_000L
        const val POLL_MS = 25L

        /**
         * How long to let a *failed* cancel finish. Several times the export's own length, so
         * "the file is not playable" cannot mean "not yet".
         */
        const val SETTLE_MS = 10_000L

        /** See the KDoc: a miss is the loaded-runner case, not a defect. */
        const val CANCEL_ATTEMPTS = 5

        /**
         * Short on purpose. Nothing is decoded or encoded on this path — the builder refuses the
         * input outright — so anything approaching this is a hang, which is what the test is
         * looking for.
         */
        const val BUILDER_TIMEOUT_MS = 30_000L
    }
}

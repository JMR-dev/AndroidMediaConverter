package org.libremediaconverter.ffmpeg

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
}

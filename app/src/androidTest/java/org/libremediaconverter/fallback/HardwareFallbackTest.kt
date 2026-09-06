package org.libremediaconverter.fallback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.codec.AndroidDeviceCodecs
import org.libremediaconverter.model.ConversionRequest
import org.libremediaconverter.model.ConversionRouter
import org.libremediaconverter.model.Engine
import org.libremediaconverter.model.OutputFormat
import org.libremediaconverter.model.QualityTier
import org.libremediaconverter.model.VideoCodec
import org.libremediaconverter.work.ConversionWorker
import java.io.File

/**
 * The runtime fallback, driven by a file Media3 genuinely cannot decode.
 *
 * The fixture is H.264 **High 4:4:4 Predictive**. Hardware AVC decoders implement High
 * 4:2:0, and Android's software `c2.google.avc.decoder` does not cover 4:4:4 either, so
 * Media3 fails partway through the export on every device.
 *
 * The static routing rules cannot predict that: the container is MP4, the codec reports
 * as "h264", and the device advertises AVC decode and encode. Everything looks viable
 * until the codec is configured. This is the one case that proves the runtime fallback
 * works, so it deliberately uses a **committed fixture** rather than media staged by
 * hand — a regression test that silently skips is worse than no test, because the count
 * still reads as coverage.
 *
 * ## Why this skips on emulators, and why that is the honest answer (#223)
 *
 * **This test used to pass everywhere while proving nothing.** Two independent facts stop the
 * fallback happening on an emulator, and both were measured rather than reasoned:
 *
 *  1. **The router never sends the job to Media3.** A Fast MP4/H.265 job goes to the hardware path
 *     only when `device.canEncode(H265)`, and emulators expose no hardware encoder — every leg of
 *     run `34004304566` logged
 *     `Routing sample_h264_444.mp4 -> ... via FFMPEG (NO_HARDWARE_ENCODER)`. The whole test
 *     finished in 448 ms, which is not long enough to fail an export and then re-encode.
 *  2. **Forcing it to Media3 does not help either, which is the part that settles it.** Pinning
 *     `ConversionDependencies.deviceCodecs` to [DeviceCodecs.PERMISSIVE] — the trick
 *     [ForcedFailureTest] uses — makes the router choose Media3, and the export then *succeeds*.
 *     Measured on a local API 34 emulator: `MediaCodecInfo` logs
 *     `NoSupport [codec.profileLevel, avc1.F4000C, video/avc]` for **both**
 *     `c2.goldfish.h264.decoder` and `c2.android.avc.decoder`, and ExoPlayer allocates the
 *     goldfish decoder anyway, which decodes the file regardless of the profile it declares.
 *     `c2.android.hevc.encoder` then encodes the result and the job reports `MEDIA3`.
 *
 * So the class KDoc above — "Media3 fails partway through the export on every device" — **is not
 * true of the emulator images**, and no amount of routing pressure makes this fixture force a
 * fallback there. The emulator cannot answer this question, so the test says so out loud instead
 * of passing.
 *
 * That is why the gate is [assumeTrue] on the *production* premise (`canEncode(H265)`) rather than
 * a pinned profile: pinning would also swap in software codecs, which is not the path a real
 * device takes and is what made the forced run succeed. **This is now the third permanent skip**;
 * the other two are [org.libremediaconverter.bench.RealMediaBenchmark]'s.
 *
 * `ForcedFailureTest.hardwareFailureFallsBackToSoftware` still covers the fallback *wiring* on
 * every leg, with an `ExplodingHardware` double. What only a device with a real hardware encoder
 * can show is two real engines disagreeing about a real file, and that is what this is for.
 *
 * ## Why the assertion is a pair
 *
 * `KEY_ENGINE_USED` is `FFMPEG` whether the fallback fired **or** the router went straight there,
 * so asserting it alone would not have caught any of the above. The premise is asserted
 * separately: [ConversionRouter.route] chooses `MEDIA3` for this request on this device. Static
 * routing wanted hardware, the runtime result was software — together, and only together, that is
 * the fallback.
 *
 * The fixture was produced with x264, which the host toolchain cannot do (Fedora's
 * ffmpeg ships openh264, which is Constrained Baseline only):
 *
 *   ffmpeg -f lavfi -i testsrc=duration=3:size=320x240:rate=15 \
 *          -f lavfi -i sine=frequency=440:duration=3 \
 *          -c:v libx264 -profile:v high444 -pix_fmt yuv444p -preset ultrafast \
 *          -c:a aac -shortest sample_h264_444.mp4
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class HardwareFallbackTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val workManager = WorkManager.getInstance(context)
    private lateinit var input: File

    @Before
    fun setUp() {
        input = File(context.cacheDir, SAMPLE)
        InstrumentationRegistry.getInstrumentation().context.assets
            .open(SAMPLE)
            .use { asset -> input.outputStream().use { asset.copyTo(it) } }
    }

    @After
    fun tearDown() {
        input.delete()
        File(context.cacheDir, "conversions").listFiles()?.forEach { it.delete() }
    }

    @Test
    fun aFileMedia3CannotDecodeStillConvertsViaFfmpeg(): Unit = runBlocking {
        // See "Why this skips on emulators" on the class. Without a real hardware encoder the
        // router never chooses Media3, and forcing it makes the export succeed instead of fail --
        // so there is no fallback to observe and a green run would mean nothing.
        assumeTrue(
            "no hardware HEVC encoder, so the router cannot choose Media3 and there is no " +
                "fallback to exercise",
            AndroidDeviceCodecs.get().canEncode(VideoCodec.H265),
        )

        val request = ConversionWorker.request(
            inputUri = Uri.fromFile(input),
            displayName = SAMPLE,
            sizeBytes = input.length(),
            spec = OutputFormat.MP4_H265.spec,
            // Fast deliberately: this is the tier the router sends to Media3, so it is
            // the tier where the fallback has to rescue the conversion.
            quality = QualityTier.FAST,
        )
        // The premise, asserted rather than assumed: this request is one the router wants to send
        // to hardware on this device. Without it the test is green whether the fallback fired or
        // the job never went near Media3, which is exactly how #223 stayed invisible.
        val decision = ConversionRouter.route(
            ConversionRequest(OutputFormat.MP4_H265.spec, quality = QualityTier.FAST),
            AndroidDeviceCodecs.get(),
        )
        assertEquals(
            "this test only means something if the router sends this job to Media3",
            Engine.MEDIA3,
            decision.engine,
        )

        workManager.enqueue(request).result.get()

        val terminal = withTimeout(TIMEOUT_MS) {
            workManager.getWorkInfoByIdFlow(request.id).first { it != null && it.state.isFinished }
        }

        val error = terminal?.outputData?.getString(ConversionWorker.KEY_ERROR)
        assertEquals(
            "a file Media3 cannot decode must still convert, but failed with: $error",
            WorkInfo.State.SUCCEEDED,
            terminal?.state,
        )

        // The outcome. Paired with the routing assertion above this is the fallback and nothing
        // else: hardware was chosen, software is what ran.
        assertEquals(
            "the router chose Media3, so a successful job must have fallen back to FFmpeg",
            Engine.FFMPEG.name,
            terminal?.outputData?.getString(ConversionWorker.KEY_ENGINE_USED),
        )

        val out = File(terminal!!.outputData.getString(ConversionWorker.KEY_OUTPUT_PATH)!!)
        assertTrue("no output produced", out.exists() && out.length() > 0)
        out.delete()
    }

    private companion object {
        const val SAMPLE = "sample_h264_444.mp4"
        const val TIMEOUT_MS = 600_000L
    }
}

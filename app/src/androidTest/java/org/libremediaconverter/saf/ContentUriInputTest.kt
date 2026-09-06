package org.libremediaconverter.saf

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
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.ffmpeg.ConcatEngine
import org.libremediaconverter.model.Engine
import org.libremediaconverter.model.OutputFormat
import org.libremediaconverter.model.QualityTier
import org.libremediaconverter.work.ConversionWorker
import java.io.File

/**
 * A `content://` input reaching FFmpeg successfully, which nothing had ever driven (#225).
 *
 * `FFmpegKitConfig.getSafParameterForRead` stands between a SAF grant and the native process, and
 * it is on **every real user conversion**. Every passing convert and join test in this suite hands
 * the worker a `Uri.fromFile(...)`, which takes the `uri.path` arm instead — so the bridge was
 * exercised only on its failure side, by `UnopenableUriTest` naming an authority that does not
 * exist. That proves the error message, not the bridge.
 *
 * ## Why a plain provider rather than the documents one
 *
 * [FixtureDocumentsProvider] cannot be reached from the app, measured three ways on an API 34
 * emulator (#226): a `DOCUMENTS_PROVIDER` declared without `MANAGE_DOCUMENTS` is refused at install
 * — *"Provider must be protected by MANAGE_DOCUMENTS"*; instrumentation runs in the **target app's
 * process**, so `Instrumentation.getContext()` still carries the app's uid and is denied; and
 * `adoptShellPermissionIdentity(MANAGE_DOCUMENTS)` is denied identically. The denial names the only
 * way in: *"you obtain access using ACTION_OPEN_DOCUMENT or related APIs"*.
 *
 * The bridge does not need one. It opens a descriptor through the resolver and hands FFmpeg a
 * `saf:` path, so any readable `content://` URI exercises it — and [FixtureContentProvider] is an
 * ordinary provider, which may be exported without a permission. The whole class is headless: no
 * DocumentsUI, and none of the flake #190 records.
 *
 * ## Why MP3
 *
 * The bridge lives on the FFmpeg arm, and MP3 is the format the router sends there unconditionally
 * — no platform encoder exists at any API level, so `ConversionWorkerTest.routesAnMp3JobToFfmpeg…`
 * relies on the same fact. Choosing a video target would make the engine depend on the device's
 * codecs, and #223 is what that costs.
 *
 * *Mutation:* make `getSafParameterForRead` return `uri.toString()`. FFmpeg cannot open it and both
 * tests fail; nothing else in either suite notices.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class ContentUriInputTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val workManager = WorkManager.getInstance(context)

    @After
    fun tearDown() {
        File(context.cacheDir, "conversions").listFiles()?.forEach { it.delete() }
    }

    @Test
    fun aContentUriInputConvertsThroughTheSafBridge(): Unit = runBlocking {
        val input = FixtureContentProvider.uriFor(SAMPLE)
        val request = ConversionWorker.request(
            inputUri = input,
            displayName = SAMPLE,
            sizeBytes = 0L,
            spec = OutputFormat.MP3.spec,
            quality = QualityTier.FAST,
        )
        workManager.enqueue(request).result.get()

        val terminal = withTimeout(TIMEOUT_MS) {
            workManager.getWorkInfoByIdFlow(request.id).first { it != null && it.state.isFinished }
        }

        val error = terminal?.outputData?.getString(ConversionWorker.KEY_ERROR)
        assertEquals(
            "a content:// input must convert, but failed with: $error",
            WorkInfo.State.SUCCEEDED,
            terminal?.state,
        )
        // The bridge is on the FFmpeg arm only, so this is part of the claim rather than colour.
        assertEquals(Engine.FFMPEG.name, terminal?.outputData?.getString(ConversionWorker.KEY_ENGINE_USED))

        val out = File(terminal!!.outputData.getString(ConversionWorker.KEY_OUTPUT_PATH)!!)
        assertTrue("no output produced from a content:// input", out.exists() && out.length() > 0)
        out.delete()
    }

    /**
     * The same bridge on the join path, which has its own copy of the call (`ConcatEngine:36`).
     *
     * Driven through the engine rather than `ConcatWorker` because the engine is where the branch
     * is; the worker adds a foreground service and nothing else this is about.
     */
    @Test
    fun contentUriInputsJoinThroughTheSafBridge(): Unit = runBlocking {
        val out = File(context.cacheDir, "joined_from_content.mp4").apply { delete() }
        val result = ConcatEngine(context).join(
            listOf(FixtureContentProvider.uriFor(CLIP_A), FixtureContentProvider.uriFor(CLIP_B)),
            out,
            OutputFormat.MP4_H264,
        )

        assertTrue("no output produced from content:// inputs", result.output.length() > 0)
        out.delete()
    }

    private companion object {
        const val SAMPLE = "sample_h264.mp4"
        const val CLIP_A = "clip_a.mp4"
        const val CLIP_B = "clip_b.mp4"
        const val TIMEOUT_MS = 300_000L
    }
}

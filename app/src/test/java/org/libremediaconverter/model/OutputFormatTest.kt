package org.libremediaconverter.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the format table itself.
 *
 * The router, the FFmpeg command builder and the output filename all read from these
 * entries, so a wrong extension or a mislabelled codec propagates everywhere.
 */
class OutputFormatTest {

    @Test
    fun `every format has a non-empty label and extension`() {
        OutputFormat.entries.forEach {
            assertTrue("${it.name} has no label", it.label.isNotBlank())
            assertTrue("${it.name} has no extension", it.extension.isNotBlank())
            assertFalse("${it.name} extension should not include a dot", it.extension.startsWith("."))
            assertTrue("${it.name} has no mime type", it.mimeType.contains('/'))
        }
    }

    @Test
    fun `audio only formats carry no video codec`() {
        OutputFormat.entries.filter { it.isAudioOnly }.forEach {
            assertEquals("${it.name} should have no video codec", VideoCodec.NONE, it.videoCodec)
        }
    }

    @Test
    fun `image outputs carry neither video nor audio codecs`() {
        OutputFormat.entries.filter { it.isImageOutput }.forEach {
            assertEquals(VideoCodec.NONE, it.videoCodec)
            assertEquals(AudioCodec.NONE, it.audioCodec)
            assertFalse("${it.name} is not audio-only", it.isAudioOnly)
        }
    }

    @Test
    fun `extensions match their containers`() {
        assertEquals("mp4", OutputFormat.MP4_H264.extension)
        assertEquals("mkv", OutputFormat.MKV_H264.extension)
        assertEquals("mp3", OutputFormat.MP3.extension)
        assertEquals("gif", OutputFormat.GIF.extension)
        assertEquals("wav", OutputFormat.WAV.extension)
    }

    @Test
    fun `video formats are not misreported as audio only`() {
        listOf(OutputFormat.MP4_H264, OutputFormat.MP4_H265, OutputFormat.MKV_H265, OutputFormat.WEBM_VP9)
            .forEach { assertFalse("${it.name} should not be audio-only", it.isAudioOnly) }
    }

    @Test
    fun `quality tiers describe themselves for the UI`() {
        QualityTier.entries.forEach {
            assertTrue(it.label.isNotBlank())
            assertTrue(it.description.isNotBlank())
        }
    }

    // --- containers are now load-bearing ------------------------------------

    /**
     * Extension and MIME type moved from the preset onto the container.
     *
     * They used to be per-preset literals, which is why `OutputFormat.FLAC` could declare
     * `Container.MKV` with extension `flac` and nobody noticed — nothing read the container. Now
     * `-f`, the filename and the SAF create-document MIME all derive from it.
     */
    @Test
    fun `every container names an extension, a mime type and an ffmpeg muxer`() {
        Container.entries.forEach { container ->
            listOf(true, false).forEach { hasVideo ->
                // Every audio codec, because the extension now varies by one — see the Ogg pair
                // below. A container that answered blank for a codec it carries would be a
                // filename with no extension at all.
                AudioCodec.entries.forEach { audioCodec ->
                    val ext = container.extensionFor(hasVideo, audioCodec)
                    assertTrue("$container/$audioCodec has no extension", ext.isNotBlank())
                    assertFalse("$container/$audioCodec extension has a dot", ext.startsWith("."))
                }
                assertTrue(
                    "$container has no mime type",
                    container.mimeTypeFor(hasVideo).contains('/'),
                )
            }
            assertTrue("$container names no muxer", container.ffmpegFormat.isNotBlank())
        }
    }

    @Test
    fun `audio-only variants of a container get their own extension`() {
        assertEquals("mp4", Container.MP4.extensionFor(hasVideo = true, audioCodec = AudioCodec.AAC))
        assertEquals("m4a", Container.MP4.extensionFor(hasVideo = false, audioCodec = AudioCodec.AAC))
        assertEquals("mkv", Container.MKV.extensionFor(hasVideo = true, audioCodec = AudioCodec.AAC))
        assertEquals("mka", Container.MKV.extensionFor(hasVideo = false, audioCodec = AudioCodec.AAC))
    }

    /**
     * The second thing the extension depends on, and the reason [Container.extensionFor] takes a
     * codec at all.
     *
     * One Ogg stream holds Vorbis or Opus, and the two are named differently: RFC 7845 §9 asks for
     * `.opus` on an Ogg carrying Opus alone, while a Vorbis one is a plain `.ogg`. The container
     * declared `opus` for every Ogg until [OutputFormat.OGG_VORBIS] existed, which would have
     * shipped a Vorbis file called `.opus` — the same shape as the `FLAC` preset that once
     * declared Matroska with a `.flac` extension, which is the regression guarded above.
     *
     * Both halves are asserted. Pinning only the Vorbis one would pass just as well if the
     * override map were deleted and every Ogg went back to a single extension, which is the
     * mutation that has to fail.
     */
    @Test
    fun `Ogg names its file after the codec in it, not after the container`() {
        assertEquals("opus", OutputFormat.OPUS.extension)
        assertEquals("ogg", OutputFormat.OGG_VORBIS.extension)
        // The MIME type does not split the same way: audio/ogg is correct for both, so the SAF
        // create-document contract sees one type for the two formats.
        assertEquals("audio/ogg", OutputFormat.OPUS.mimeType)
        assertEquals("audio/ogg", OutputFormat.OGG_VORBIS.mimeType)
    }

    /** Regression guard: FLAC used to be declared as Matroska with a `.flac` extension. */
    @Test
    fun `FLAC is its own container, not Matroska`() {
        assertEquals(Container.FLAC, OutputFormat.FLAC.container)
        assertEquals("flac", OutputFormat.FLAC.extension)
        assertEquals("flac", Container.FLAC.ffmpegFormat)
    }

    @Test
    fun `the remux presets copy both tracks`() {
        listOf(OutputFormat.REMUX_MP4, OutputFormat.REMUX_MKV).forEach {
            assertEquals(VideoCodec.COPY, it.videoCodec)
            assertEquals(AudioCodec.COPY, it.audioCodec)
            assertTrue("${it.name} should be a pure remux", it.spec.isPureRemux)
        }
    }

    @Test
    fun `a spec that encodes anything is not a remux`() {
        assertFalse(OutputFormat.MP4_H265.spec.isPureRemux)
        assertFalse(
            OutputSpec(Container.MP4, VideoCodec.COPY, AudioCodec.AAC).isPureRemux,
        )
        assertFalse(
            "dropping both tracks copies nothing",
            OutputSpec(Container.MP4, VideoCodec.NONE, AudioCodec.NONE).isPureRemux,
        )
    }
}

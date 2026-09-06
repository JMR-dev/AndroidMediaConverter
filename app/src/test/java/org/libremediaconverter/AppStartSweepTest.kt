package org.libremediaconverter

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.convert.StagingSweep
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * That process start actually sweeps.
 *
 * [StagingSweepTest][org.libremediaconverter.convert.StagingSweepTest] pins the age rule and
 * `OutputPublisherStagingTest` pins the sweep against a real filesystem; neither says anything
 * about whether anything calls it, and deleting the one line that does left the whole suite green.
 * That line is the only reason this Application class exists, and it is the backstop for every leak
 * `discardStaged` cannot reach — a process reclaimed before a save, a worker that failed before its
 * output ever became a `Converted` state, a `reset()` whose delete was cancelled with the Activity.
 *
 * `onCreate()` is called again rather than a second Application being built: it is what the
 * framework calls at process start, and the scope it launches on is already there.
 *
 * **What this class stopped covering in #159, deliberately.** It used to open by asserting that
 * `RuntimeEnvironment.getApplication()` is a [LibreMediaConverterApp] — that the manifest's
 * `android:name` points here, so the sweep is code that actually runs. That assertion cannot exist
 * on the JVM any more: `robolectric.properties` now names [TestLibreMediaConverterApp] for the
 * whole suite, and an `application=` override replaces the manifest rather than being checked
 * against it — `applicationInfo.className` reports the override too, measured. So the manifest is
 * not merely unasserted here, it is unobservable from this source set, and a rewritten version of
 * that test would have asserted the override against itself. **The manifest link is a device-only
 * guarantee now**, and it was traded knowingly for the race that override fixes. The cast in
 * [setUp] still fails if [TestLibreMediaConverterApp] stops extending the real class, which is a
 * smaller claim than the one withdrawn.
 */
@RunWith(RobolectricTestRunner::class)
class AppStartSweepTest {

    private lateinit var app: LibreMediaConverterApp
    private lateinit var stagingDir: File

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication() as LibreMediaConverterApp
        stagingDir = File(app.cacheDir, "conversions").apply { mkdirs() }
        stagingDir.listFiles()?.forEach { it.delete() }
    }

    /**
     * The property the whole substitution exists for, asserted directly rather than waited on.
     *
     * #159 is not "the sweep is slow", it is "the sweep is still running while some later test
     * reads the directory". [TestLibreMediaConverterApp] answers that by finishing the sweep before
     * `onCreate()` returns, and this is the only place that claim is checked -- every other test in
     * the suite benefits from it silently and would go back to racing without saying why.
     *
     * Deterministic in the direction that matters: `Dispatchers.Unconfined` runs a `launch` whose
     * body never suspends to completion inline, so this cannot flake green-to-red. Putting the test
     * app back on `Dispatchers.IO` makes it a race that the assertion loses essentially every time,
     * which is what a six-run suite comparison could not show -- at the rate #159 was observed at,
     * a clean six-run arm is a coin flip.
     */
    @Test
    fun `the sweep is finished before onCreate returns`() {
        app.onCreate()

        val sweep = app.startupSweep
        assertNotNull("onCreate() started no sweep", sweep)
        assertTrue(
            "the JVM suite's sweep outlived onCreate(), so it is in flight during test bodies again",
            sweep?.isCompleted == true,
        )
    }

    @Test
    fun `process start collects an abandoned staged file and leaves a live one alone`() {
        val abandoned = stagedFile("abandoned.mp4")
        val live = stagedFile("live.mp4")
        // Set explicitly. Relying on a file being written "long enough ago" is not something a test
        // can arrange, and the grace period is a day.
        assertTrue(
            abandoned.setLastModified(System.currentTimeMillis() - StagingSweep.GRACE_PERIOD_MS - ONE_MINUTE_MS),
        )

        // Both files are still here on the way in. The Application was already constructed once
        // before this test ran, so without this the sweep that call started could be the one that
        // collected the file, and the assertion below would be about the wrong process start.
        assertTrue(abandoned.exists() && live.exists())

        app.onCreate()

        // Joined rather than polled. `onCreate` publishes the sweep it started, so this waits for
        // that exact sweep -- where a timed poll could not tell "swept" from "not started yet", and
        // answered the second case by failing after ten seconds.
        val sweep = app.startupSweep
        assertNotNull("onCreate() started no sweep to wait for", sweep)
        runBlocking { sweep?.join() }

        assertTrue("process start left ${abandoned.name} in staging; nothing swept it", !abandoned.exists())
        // The other half, and the one that says the sweep is a sweep rather than a
        // `clearStaging()`: the directory is shared by the convert tab, the join tab and
        // ConcatEngine's list file, so deleting everything could take a file from a running job.
        assertTrue("a file written moments ago belongs to a live job", live.exists())
    }

    private fun stagedFile(name: String): File = File(stagingDir, name).apply { writeBytes(ByteArray(4096)) }

    private companion object {
        const val ONE_MINUTE_MS = 60L * 1000
    }
}

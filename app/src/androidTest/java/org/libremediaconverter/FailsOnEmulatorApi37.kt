package org.libremediaconverter

/**
 * Marks an instrumented test that cannot be run on the `android-37.x` **emulator** system images.
 *
 * This is a marker, not a skip. Nothing reads it except CI, and CI reads it twice — once with
 * `notAnnotation` to build the gating API 37 leg, and once with `annotation` to build the advisory
 * one — so a test carrying it runs in exactly one of the two and can never fall through both.
 * That is the whole reason there is one annotation rather than a pair of test lists: two lists
 * drift, and the drift is silent in both directions (a test that runs nowhere reads as green).
 *
 * **"Cannot be run" covers two things, and it said only the first until 2026-09-05.** Three of the
 * four carriers simply fail: two Media3 transcodes die in the image's own `c2.goldfish.h264
 * .decoder`, and the SAF rotation test takes the framework down with it. The fourth —
 * `SafPickerRoundTripTest.pickingAFileThroughTheSystemPickerFillsInTheFileCard` — **passes about
 * half the time and aborts `system_server` every time**, which is worse for a gating leg than an
 * honest failure: it fails the leg from the teardown, with no failing test to point at (#108).
 * The wording was widened rather than the test excused; that test's own KDoc has the four-run
 * measurement.
 *
 * It says only what has been measured: **on the emulator, at API 37.** The same tests pass on a
 * physical Pixel 10 Pro XL at API 37 and at API 33–36 on the same runner under the same renderer,
 * so this must never be read as "this test is allowed to fail at API 37" — only as "the API 37
 * emulator image cannot currently answer this one". `docs/api-37-emulator-crash.md` has the
 * measurements and the one bullet in them that is still inference.
 *
 * Removing it is the goal, and the trigger is written down: a new API 37.x system image, or an
 * ATD image for 37. Delete the annotation from the tests, and the advisory job goes empty and
 * the gating one grows by four.
 *
 * **How many tests carry it is committed below**, as [FAILS_ON_EMULATOR_API37_BASELINE], and the
 * advisory job checks the run against it. Adding or removing a marker means changing that number
 * in the same diff.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class FailsOnEmulatorApi37

/**
 * How many tests carry [FailsOnEmulatorApi37] — the advisory API 37 job's committed baseline.
 *
 * **No Kotlin reads this, and it is not stray config.** `.github/scripts/e2e-report-shape.sh`
 * parses it out of this file by name, with a line-anchored pattern, and the advisory job compares
 * the run it just did against it: this many tests should start, and all of them should fail.
 * Deleting it, renaming it, or indenting it into a class stops the comparison — the report would
 * keep printing with nothing to compare to, so it announces that it could not read the baseline
 * rather than falling quiet. If you see that notice, this line is what it means.
 *
 * **One number, both checks, and that is what the marker means.** A test carrying it cannot be run
 * on this image, so the count is simultaneously how many the advisory leg runs and how many fail.
 * A *smaller* failure count is the interesting direction: it means one of them now passes, which
 * is the trigger the KDoc above names for deleting the annotation.
 *
 * **The fourth carrier is the one to read that sentence carefully for.**
 * `pickingAFileThroughTheSystemPickerFillsInTheFileCard` was marked on 2026-09-05 for aborting
 * `system_server` rather than for failing (#108), and on the gating leg it passed two runs of
 * four. The count still holds on the advisory leg, and that was **measured rather than assumed**:
 * `api37-debug.yml` run 34008889182, dispatched with this annotation as its filter, reports
 * `expected: 4, received: 4, failed: 4` (and `completed cleanly: no`, which is this job's normal).
 * It fails there because it runs alongside the rotation test, which takes the framework down first
 * — so the reason this line did not have to become two numbers is a property of the advisory leg,
 * not of the test. If it ever reports three failures out of four, read that as this test having
 * got lucky rather than as an image that improved.
 *
 * So: adding or removing a [FailsOnEmulatorApi37] means changing this number, in this file, in
 * the same diff. The report says so on the run itself if you forget — it prints the tree's own
 * `grep` count beside this one.
 *
 * Why a baseline at all (#83): that job is `continue-on-error` and red on every PR by design, so
 * a red X cannot distinguish the known failures from the known failures plus a new one. Counting
 * failures alone does not fix it either — the run is usually truncated by an
 * `INSTRUMENTATION_ABORTED`, so the count is a number taken from a partial run. The report
 * records the truncation next to the counts for that reason.
 */
const val FAILS_ON_EMULATOR_API37_BASELINE = 4

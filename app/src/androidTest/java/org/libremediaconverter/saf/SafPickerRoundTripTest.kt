package org.libremediaconverter.saf

import android.Manifest
import android.app.UiAutomation
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.work.WorkManager
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.FailsOnEmulatorApi37
import org.libremediaconverter.MainActivity
import org.libremediaconverter.convert.ConversionDependencies
import org.libremediaconverter.convert.OutputPublisher
import org.libremediaconverter.ui.TestTags
import java.io.File
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

/**
 * Choosing a file, through the real system picker, and still having it after a rotation.
 *
 * Two defects, and neither is reachable from anywhere else in this repo.
 *
 * **The picker is opened with a filter, and a filter can hide the user's file.** `ConverterScreen`
 * launches `ActivityResultContracts.OpenDocument` with a MIME array; DocumentsUI hides every root
 * and every document that array does not match. Narrow it and the app still compiles, still
 * renders, still passes every JVM test — and the user taps "Choose file" and is shown an empty
 * picker. Nothing in either source set drove SAF **as a picker** before this: the only SAF coverage
 * is the publish side, in `OutputPublisherPublishTest`, against hand-written `ContentProvider`
 * fakes. The launcher wiring, the filter, and the read grant that comes back had never been
 * executed by a test.
 *
 * **The picked file has to survive a rotation.** `MainActivity` declares no `configChanges`, so
 * every rotation destroys and recreates it, and `ConversionViewModel` holds the picked file in a
 * plain `MutableStateFlow` with no `SavedStateHandle` behind it. The only thing that carries it
 * across is the retained `ViewModelStore` the Activity gets from resolving the ViewModel through
 * `LocalViewModelStoreOwner`. Scope it to the composition instead and the file is gone.
 *
 * ### Why these two are one test class
 *
 * A rotation test alone has no bite of its own. `AppRootRestorationTest` already catches
 * `rememberSaveable` -> `remember` on the JVM, and a second test whose only mutation is one an
 * existing test catches is the vacuous test this whole decomposition exists to prevent. So the
 * rotation here runs **from a real picked input**, which is a state no JVM test can produce:
 * `AppRootRestorationTest` injects a stub `content` lambda specifically to avoid standing up
 * either ViewModel, and `StateRestorationTester` saves into an in-memory map rather than a
 * `Bundle`.
 *
 * ### #93: what actually failed was reading the screen, not the picker
 *
 * Ninety minutes after this class landed it started failing on gating legs at API 33, 34, 35 and
 * 37 — on diffs that were two KDoc comments, a MIME lookup table and a README paragraph (#93).
 * Every failure named the fixture root, so it read as a root-discovery race, and the ticket was
 * filed on that reading. It was not one, and it was not the `StaleObjectException` #80 had fixed
 * an hour earlier either.
 *
 * **DocumentsUI was fine.** On the API 34 leg of run 32806342548 its own
 * `ProvidersAccess: Matched roots` names
 * `content://org.libremediaconverter.test.fixtures/root/lmc-r38-root` five times inside the sixty
 * seconds the test spent failing, `ActivityTaskManager` logged the `PickActivity` as `Displayed`,
 * and the provider process started on cue.
 *
 * **This process could not read any window at all.** Two counts settle it. Across that whole leg
 * UiAutomator logged `Retrieving node with selector` 1095 times and `Node not found with selector`
 * 1095 times — not one selector ever matched, from the first query of the run. The green leg of
 * the same job asked 7 times and found 5. `UiDevice.getWindowRoots` builds its search set from
 * `UiAutomation.getWindows()` and, on API 21 and up, from nothing else; an empty list there makes
 * every selector unfindable and says nothing whatever about SAF. The corroborating detail is that
 * `By.desc("Show roots")` — the toolbar button, present on that screen whether the roots list is
 * stale or not — was also not found, 28 s after the picker was displayed.
 *
 * **A fresh picker is not the repair, and this was measured rather than assumed.** The same leg
 * opened a *second* `PickActivity` for the second test, in the same DocumentsUI process
 * (pid 3299), and read exactly as little from it. So whatever was broken outlived one window.
 * [requireAReadableScreen] is the part aimed at that: it asks whether this process can see the
 * app's own window *before* the picker is opened, and [rebuildUiAutomation] tears the connection
 * down and builds another if it cannot.
 *
 * **The check has since caught the real thing, in CI, and the connection rebuild did not repair
 * it.** Run 32811493607, API 35 and API 37 legs, both tests, 12 s each instead of 60:
 *
 * ```
 * java.lang.AssertionError: UiAutomator cannot see this app's own window, so it could not have
 *   seen the picker's either. This is not a SAF failure.
 *     at SafPickerRoundTripTest.requireAReadableScreen
 * ```
 *
 * That is the diagnosis this class could not previously give, and it moves the question off SAF
 * for good.
 *
 * ### What the window list said, and why nothing here can fix it
 *
 * [describeWindows] was added to that failure so the next occurrence would close the question
 * rather than reopen it. It did — on the API 34 leg of run 32812248131 and again, character for
 * character, on the API 33 leg of run 32812892103:
 *
 * ```
 * ... Waking the device, dismissing the keyguard and rebuilding the UiAutomation connection all
 * failed to make it readable. What it could see: com.android.systemui[type=3], android[type=3]
 * ```
 *
 * `type=3` is `AccessibilityWindowInfo.TYPE_SYSTEM`. The list is **not** empty — it holds the
 * system windows and **not one `TYPE_APPLICATION` window**, on a device where the framework had
 * already logged `Displayed org.libremediaconverter/.MainActivity`. So the application layer
 * never reaches accessibility on those boots, and every selector in this class, the picker's and
 * the app's alike, is unfindable for the whole instrumentation run.
 *
 * Three CI runs on this branch caught the fault, at API 33, 34, 35 and 37, and every one of them
 * printed that same list. It is not one level's quirk.
 *
 * ### And that list is what identified the occluder
 *
 * `android[type=3]` is `system_server`, and what it was holding is in the same logcat, minutes
 * before this class ever ran:
 *
 * ```
 * ANR in com.google.android.apps.nexuslauncher (com.google.android.apps.nexuslauncher/.NexusLauncherActivity)
 * Reason: Input dispatching timed out (Application does not have a focused window)
 * Window{4ed8414 u0 Application Not Responding: com.google.android.apps.nexuslauncher}
 * ```
 *
 * **The launcher ANRs on a loaded runner emulator, and the dialog it leaves behind never goes
 * away.** It is opaque and fullscreen, so `AccessibilityWindowManager` drops every application
 * window beneath it — which is how the app can be `Displayed` and unreadable at once, the
 * contradiction that made #93 look like a SAF bug for six PRs. It is present on both legs
 * examined, at API 33 and 34, at the failure timestamp.
 *
 * So [dismissASystemErrorDialog] is tried first, and it is the remedy with a mechanism behind it.
 * The other two are kept behind it and are **measured as not the cause**: [unlockTheDevice] (the
 * keyguard theory, from `KeyguardViewMediator` reporting an unprovisioned device — dismissing it
 * changed nothing) and [rebuildUiAutomation]. A second `PickActivity` is not a remedy for this
 * either, and that was measured too: the first failing leg opened one and read as little from it.
 *
 * **What is honest about the dialog remedy: it has been shown to do no harm, not to work.** It
 * was forced on with no dialog present and the suite stayed green, which is the way a blind
 * `click()` could have broken a healthy run. Dismissing a real ANR dialog has not been observed,
 * because the fault has never been reproduced locally — not on six warm runs, not on cold
 * full-suite runs at API 34 and 35 on freshly created AVDs under `swangle_indirect` at two cores,
 * not under host load. If it recurs, the message now names the dialog and the window list, so the
 * next step is a measurement rather than another theory.
 *
 * ### The whole pick is retried, which is a separate and smaller claim
 *
 * [pickTheFixture] also backs out and asks for another picker when the walk comes up short. That
 * is not the answer to the paragraph above; it is the answer to a picker whose *lists* were built
 * before their data arrived, which is a real thing DocumentsUI does and which
 * [tapPickerNode]'s re-find cannot reach either — it re-acquires a handle inside the one picker.
 *
 * One API 37 run failed a step deeper than the rest: the root appeared and
 * `[TEXT='\Qlmc-r38-fixture.mp4\E']` did not. **That shape has not been reproduced or
 * diagnosed.** It is covered here only because a fresh pick re-walks from Recent, and that is
 * worth writing down rather than letting the retry read as a fix for something nobody measured.
 *
 * ### The mutations, and what they printed
 *
 * Both were run, not asserted. Narrowing the wildcard array `ConverterScreen.kt` passes to
 * `pickInput.launch` — to `arrayOf("application/x-lmc-no-such-type")` — empties the picker of the
 * fixture root entirely, and both tests fail on the assertion that names it. **Re-run after the
 * #93 retry landed**, because a retry that tolerated an absent root would have made this mutation
 * vacuous, which is the one thing that must not happen here:
 *
 * ```
 * java.lang.AssertionError: the system picker never showed BySelector [TEXT='\QLMC R38 fixtures\E'],
 *   in 3 separate pickers (the last one left org.libremediaconverter in front)
 *     at org.libremediaconverter.saf.SafPickerRoundTripTest.pickTheFixture(SafPickerRoundTripTest.kt:268)
 * ```
 *
 * The root is absent from all three pickers, so all three report it, and the cost of saying so is
 * bounded: 126 s and 127 s for the two tests, against the 1200 s wrapper timeout in
 * `.github/scripts/e2e-run.sh`. The clause about what was left in front is not decoration either
 * — it is what says the retry really did get back to the app between attempts rather than tapping
 * behind a picker that never closed.
 *
 * **That mutation only shows the retry failing correctly.** Showing it *recovering* needs a
 * failure that goes away, so one was injected: a field making the first
 * [walkThePickerToTheFixture] of each test return a selector nothing matches. Both tests then
 * passed, with `ActivityTaskManager` logging four `OPEN_DOCUMENT` starts for the two of them —
 * two pickers each. That is the run which says the reopened pick completes: that
 * `pickInput.launch` is not refused from the re-resumed Activity, and that the second test's
 * reopen, which lands in the last-accessed stack rather than on Recent, still walks to the file.
 * Making the ViewModel composition-scoped leaves the picker test alone and fails
 * [thePickedInputSurvivesARealRotation], with `:app:testDebugUnitTest` still BUILD SUCCESSFUL —
 * which is the divergence this ticket was filed to establish, and which was doubted on it. It is
 * `viewModel()` -> `viewModel(viewModelStoreOwner = remember { <a plain ViewModelStoreOwner> })`,
 * **plus** `factory = ViewModelProvider.AndroidViewModelFactory()` and a `MutableCreationExtras`
 * carrying `APPLICATION_KEY`. The factory half is not decoration: an owner that is not a
 * `HasDefaultViewModelProviderFactory` contributes no creation extras, and the default factory
 * cannot construct an `AndroidViewModel` without them — so the owner swap alone crashes on
 * construction instead of demonstrating the scope. The PR body quotes both failures verbatim.
 *
 * ### It has to be an unlocked emulator
 *
 * The Pixel 10 Pro XL is secure-locked and cannot be unlocked from a shell, so the picker cannot be
 * driven there at all. That is why this gap survived as long as it did.
 * `tools/local-emulator/run-e2e.sh` runs API 33-36 on the development host, and both tests pass
 * there: **59 / 0 / 0 / 2 at API 33 and again at API 36**, whole suite, 2026-08-24.
 * (Since #223 the skip column reads 3 on an emulator — `HardwareFallbackTest` now announces
 * that it cannot run without a hardware HEVC encoder rather than passing vacuously.)
 *
 * ### Why only the rotation test carries [FailsOnEmulatorApi37]
 *
 * This class is the first thing in the suite that touches system UI, and the android-37.x images
 * are where that stops being free: surfaceflinger aborts inside the guest's Gralloc5 mapper, init
 * SIGKILLs zygote with it, and the framework restarts underneath the run. Disabling SystemUI --
 * the deviation the API 37 leg already makes -- removes the *idle* trigger, not this one.
 *
 * The marker is on one method and not on the class, because that is what was measured, one method
 * per fresh emulator, on `android-37.0` under `swangle_indirect`:
 *
 * ```
 * thePickedInputSurvivesARealRotation            INSTRUMENTATION_ABORTED: System has crashed.
 *                                               Expected 1 tests, received 0
 * pickingAFileThroughTheSystemPickerFillsInTheFileCard                              PASSED
 * ```
 *
 * A rotation rebuilds every surface on screen at once, which the mapper does not survive; merely
 * starting DocumentsUI does not.
 *
 * **The first version of this said the class, and it was wrong.** The picker test had failed at
 * API 37 too -- with a `StaleObjectException` that turned out to be this file's own bug rather
 * than the image's, and which CI then reproduced deterministically at API 33, 34 and 35. Fixing
 * it ([tapPickerNode]) and re-measuring is what separated the two. An annotation is a claim about
 * an image, and a broken test makes every image look broken; **re-measure after fixing a test
 * before deciding what the platform did.**
 *
 * The annotation says only that, and CI reads it twice, so the rotation test runs on the advisory
 * API 37 leg and not the gating one. **Do not read it as "a rotation is allowed to lose the
 * file".** That is what API 33 through 36 are for, and they answer it.
 */
@UnstableApi
/**
 * Reads what SAF handed back, then publishes for real.
 *
 * The premise `OutputPublisher.destinationIsKnownEmpty` depends on has only ever been asserted
 * against a fake built to match it — `OutputPublisherPublishTest` writes `ByteArray(0)` into
 * `FakeSafProvider` before each case, under a comment stating this is how `CreateDocument` behaves.
 * This records what stock DocumentsUI actually produced, at the moment `publish` sees it and before
 * a byte is written, and then lets the real copy proceed. See #226.
 */
private class RecordingPublisher(private val app: Context) : OutputPublisher(app) {

    override fun publish(staged: File, destination: Uri) {
        seenDestination = destination
        seenIsDocumentUri = DocumentsContract.isDocumentUri(app, destination)
        seenSizeBefore = app.contentResolver
            .query(destination, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { row ->
                val column = row.getColumnIndex(OpenableColumns.SIZE)
                if (column >= 0 && row.moveToFirst() && !row.isNull(column)) row.getLong(column) else null
            }
        // Read before the copy: the ViewModel deletes the staged file once publish returns.
        savedBytes = staged.readBytes()
        super.publish(staged, destination)
    }

    /**
     * Refuses the write when [failOpen] is set, which is the forcing condition for #250.
     *
     * Returning null rather than throwing is deliberate: it is the arm `publish`'s
     * `?: error("Could not open destination for writing")` exists for, and `openDestination`'s
     * own KDoc says a provider that is present and declines is the half no fake can produce on
     * demand. The size probe in `publish` has already run by the time this is reached, so
     * `destinationWasEmpty` is true and `deletePartialOutput` is reached with the document
     * genuinely empty — which is the whole point.
     */
    override fun openDestination(destination: Uri): OutputStream? =
        if (failOpen) null else super.openDestination(destination)

    companion object {
        var savedBytes: ByteArray = ByteArray(0)
        var seenDestination: Uri? = null
        var seenIsDocumentUri: Boolean? = null
        var seenSizeBefore: Long? = null

        /**
         * Makes the next `publish` refuse to open its destination.
         *
         * A flag rather than a second publisher because `ConversionDependencies.publisher` is one
         * seam and there is no orchestrator: every test in this process shares the instance the
         * `init` block installed. [reset] clears it in teardown, so a test that sets it cannot
         * leak a refusing publisher into the next class.
         */
        var failOpen: Boolean = false

        fun reset() {
            savedBytes = ByteArray(0)
            seenDestination = null
            seenIsDocumentUri = null
            seenSizeBefore = null
            failOpen = false
        }
    }
}

@RunWith(AndroidJUnit4::class)
class SafPickerRoundTripTest {

    /**
     * Installs [RecordingPublisher] before the Activity exists.
     *
     * `ConversionViewModel` resolves its publisher through `ConversionDependencies` **at
     * construction**, and the Compose rule launches `MainActivity` as part of the rule chain —
     * which wraps `@Before`, so `@Before` is already too late. JUnit constructs the test instance
     * before it evaluates the rules, so an initialiser is early enough, and it needs no
     * `@BeforeClass` (this class's companion is private, and JUnit wants a public static there).
     *
     * Harmless for the other two tests: neither saves, so `publish` is never called and the
     * subclass behaves exactly like `OutputPublisher`. `restoreOrientation` puts the seam back.
     */
    init {
        RecordingPublisher.reset()
        ConversionDependencies.publisher = { RecordingPublisher(it) }
    }

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    private val device: UiDevice =
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /** The app under test, whose own window is what [requireAReadableScreen] asks for. */
    private val appPackage: String =
        InstrumentationRegistry.getInstrumentation().targetContext.packageName

    /** Set by the one test that rotates, read by [restoreOrientation]. See its KDoc. */
    private var rotated = false

    /** Counts [MainActivity] creations from the moment [watchForRecreation] is called. */
    private val recreations = AtomicInteger()

    /**
     * Holds `POST_NOTIFICATIONS`, so tapping Convert cannot open a window this test has to fight.
     *
     * ## What this replaces, and why the replacement is not a smaller wait
     *
     * Until #268 the tap was followed by `dismissThePermissionDialog`, which waited for
     * `com.google.android.permissioncontroller` to appear and pressed back on it. That is a
     * *foreign, focused window* in the middle of the one step this class most needs to be
     * deterministic, and it is what mechanism B of #268 was: on the API 35 leg of run 34146936252
     * the tap landed — `START u0 {act=android.content.pm.action.REQUEST_PERMISSIONS ...
     * GrantPermissionsActivity}` at 17:38:30.516 — back was pressed at 17:38:32.479, and no
     * `ConversionWorker` was ever enqueued in the five minutes that followed. A back press goes to
     * whichever window holds *input* focus, and `Until.hasObject` answers about the accessibility
     * tree, which can carry the dialog's nodes before it has the focus; a back that arrives one
     * window early lands on `MainActivity` and finishes it, which is a screen no `waitUntil` can
     * wait for the return of.
     *
     * ## Why holding the permission removes the window rather than making it less likely
     *
     * `ConverterScreen` wires Convert to `requestNotifications.launch(POST_NOTIFICATIONS)`, and
     * `ActivityResultContracts.RequestPermission.getSynchronousResult` returns
     * `SynchronousResult(true)` — *without starting anything* — when
     * `checkSelfPermission` already answers `PERMISSION_GRANTED`. So with the permission held there
     * is no `GrantPermissionsActivity`, no foreign window, no back press, and nothing this test
     * injects can finish the Activity. That is the whole chain, and [holdTheNotificationPermission]
     * asserts its one premise rather than assuming it.
     *
     * ## The KDoc this contradicts, and the measurement that settles it
     *
     * `convertToTheDefaultFormat` used to say granting "was tried first and did not take —
     * `GrantPermissionsActivity` appeared anyway". Re-measured on 2026-09-07, API 34 on this host,
     * six consecutive runs of this class: logcat carries **zero**
     * `act=android.content.pm.action.REQUEST_PERMISSIONS` starts and zero `GrantPermissionsActivity`
     * across all six, and exactly two `WM-SystemJobScheduler: Scheduling work ID` lines per run —
     * one for each test that converts, so neither Convert tap was lost. Whatever the earlier
     * attempt did, a `pm grant` issued before the tap does take. The assertion below is what keeps
     * that from going quietly stale.
     *
     * ## Two consequences, both deliberate
     *
     * The grant is **not** undone in teardown: revoking a runtime permission restarts the app's
     * process, which would take the rest of the instrumentation run with it. The suite runs without
     * Orchestrator, so every class that converts *after* this one now does so with notifications
     * permitted. That is benign — `ConversionNotifications` builds its channel at
     * `IMPORTANCE_LOW`, so nothing heads-up over the screen — but it is a real change to the
     * device state the rest of the run sees, and `NotificationCancelActionTest`'s KDoc is updated
     * with it.
     *
     * And this class no longer takes the denial path. It never asserted anything about it — the
     * permission is setup for a test whose subject is SAF — and nothing is lost by it: the
     * callback `ConverterScreen` registers is `{ viewModel.convert() }`, which **ignores its
     * boolean**, so "converts whichever way the answer goes" is the shape of the code rather than a
     * branch a test has to choose. `StaleLauncherResultTest` is what pins that callback path.
     */
    @Before
    fun holdTheNotificationPermission() {
        device.executeShellCommand("pm grant $appPackage ${Manifest.permission.POST_NOTIFICATIONS}")
        assertEquals(
            "POST_NOTIFICATIONS is not held, so tapping Convert would open a permission dialog " +
                "and this class's determinism argument does not hold -- see the KDoc above",
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS),
        )
    }

    /**
     * Counts a rotation's recreation without asking the Activity anything.
     *
     * Deliberately not `composeRule.activity`, which resolves through `scenario.onActivity` and so
     * blocks on the main thread. Polling *that* across a recreation is a plausible reading of the
     * 20-minute wedges in #122, which would make the obvious barrier the bug it is meant to fix.
     * The runner's lifecycle monitor is a callback: reading the counter touches no looper.
     */
    private val recreationWatcher = ActivityLifecycleCallback { activity, stage ->
        if (activity is MainActivity && stage == Stage.CREATED) recreations.incrementAndGet()
    }

    /**
     * Leave the device the way it was found — and only if this test moved it.
     *
     * Two things are deliberate here, and both are about the *other* tests on the device rather
     * than about these two.
     *
     * The flag, because this runs after every test in the class, not only the one that rotated. An
     * unconditional restore issues a WindowManager rotation request after the picker test as well,
     * which has nothing to undo; JUnit does not promise method order, so that is an interaction
     * between two tests that no single-class run would ever show. Tracked as a flag rather than
     * read back off `isNaturalOrientation`, because a device whose *natural* orientation is
     * landscape would answer that question the wrong way round.
     *
     * And `unfreezeRotation`, because `setOrientationNatural` does not merely rotate: it freezes
     * the rotation there. A run that stopped after it would hand the next test a device that
     * cannot rotate at all.
     */
    @After
    fun restoreOrientation() {
        // The suite runs without Android Test Orchestrator, so a swapped seam outlives the class.
        ConversionDependencies.reset()
        RecordingPublisher.reset()
        clearFinishedWork()
        ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(recreationWatcher)
        if (!rotated) return
        device.setOrientationNatural()
        device.unfreezeRotation()
        device.waitForIdle()
    }

    /**
     * **Marked for API 37 because of what it does to the image, not because it fails there.**
     *
     * This is the one place the marker's KDoc phrase "cannot pass on this image" does not fit, and
     * the distinction is worth keeping rather than smoothing over. Across the four gating API 37
     * runs whose logcats were read on 2026-09-05 — 34006456986, 34001744574, 34001377499 and the
     * green 34002313300 — the leg carries exactly two `hasReadColorBufferDma` aborts before the
     * suite starts (both `surfaceflinger`, during boot and the SystemUI disable) and then exactly
     * **one** during it. Every time, that one is `system_server` on the `TaskSnapshotPer` thread,
     * and every time it lands inside this test's window. No other test in the gating set reaches
     * the mapper at all.
     *
     * So this test kills the framework on that image whether it passes or not, and whether the leg
     * goes red is luck: 34001377499 passed it and lost the leg anyway (`failed: 0`, teardown
     * broken), 34002313300 passed it 0.6 s after the abort and went green. That is #108, and it is
     * why the leg was failing on unrelated PRs.
     *
     * `docs/api-37-emulator-crash.md` measured this test on 2026-08-24, recorded "passes, 4 aborts
     * in the window", and concluded that a rotation reaches the mapper where starting DocumentsUI
     * does not. The aborts were seen; what was not drawn out is that they are this test's own and
     * are not intermittent.
     *
     * The marker is what routes it off the gating leg and into the advisory job beside its
     * rotation sibling. **It is not a statement about the picker**: the same test passes on API
     * 33–36 on the same runner and on the Pixel 10 Pro XL, which is where API 37's answer comes
     * from.
     */
    @Test
    @FailsOnEmulatorApi37
    fun pickingAFileThroughTheSystemPickerFillsInTheFileCard() {
        pickTheFixture()

        composeRule.onNodeWithTag(TestTags.Converter.FILE_CARD_NAME)
            .assertTextEquals(FixtureDocumentsProvider.FIXTURE_DISPLAY_NAME)

        // Not the same assertion twice. The name above comes from a metadata query, which a URI
        // with no read grant answers just as well; this line only appears once something has
        // opened the file and read its header. It is what says the picker handed back a URI the
        // app can actually USE -- delete grantUriPermissions from the fixture's manifest entry and
        // the name still arrives while this goes red.
        //
        // The whole "Container: MP4" and not "MP4": DetailRow renders the label and the value as
        // one semantics node.
        awaitNode(TestTags.Converter.detailRow(CONTAINER_LABEL))
        composeRule.onNodeWithTag(TestTags.Converter.detailRow(CONTAINER_LABEL))
            .assertTextEquals("$CONTAINER_LABEL: MP4")
    }

    @Test
    @FailsOnEmulatorApi37
    fun thePickedInputSurvivesARealRotation() {
        pickTheFixture()
        // The identity hash rather than the Activity itself, so nothing here keeps a destroyed
        // Activity reachable across the recreation it is being used to detect.
        val before = System.identityHashCode(composeRule.activity)
        watchForRecreation()

        device.setOrientationLandscape()
        rotated = true
        awaitRecreation()
        composeRule.waitForIdle()

        // Two guards before the assertion that matters, because both of the ways this test could
        // pass while proving nothing are silent ones.
        //
        // A device that ignored the rotation request would leave the app exactly as it was, and
        // "the file is still there" would then be a statement about a screen nothing happened to.
        assertNotEquals(
            "the device did not actually rotate, so nothing below is about a rotation",
            NATURAL_ROTATION,
            device.displayRotation,
        )
        // And a rotation that did NOT recreate the Activity -- a configChanges attribute added to
        // the manifest, an aspect-ratio or orientation lock -- would make this a recomposition
        // test. The retained ViewModelStore is only interesting because the Activity around it
        // really was destroyed and rebuilt.
        assertNotEquals(
            "the rotation did not recreate MainActivity, so the retained ViewModelStore was never used",
            before,
            System.identityHashCode(composeRule.activity),
        )

        awaitNode(TestTags.Converter.FILE_CARD_NAME)
        composeRule.onNodeWithTag(TestTags.Converter.FILE_CARD_NAME)
            .assertTextEquals(FixtureDocumentsProvider.FIXTURE_DISPLAY_NAME)
    }

    // --- driving the picker ---------------------------------------------------------------

    /**
     * Taps "Choose file", walks the system picker to the fixture, and returns once the app has it.
     *
     * Everything between the first tap and the last belongs to `com.google.android.documentsui`,
     * which is why UiAutomator is here at all: Compose's matchers stop at this process's
     * composition and Espresso's at its view hierarchy, and the picker is neither.
     *
     * **What is retried here is the whole pick.** [tapPickerNode]'s re-find re-acquires a handle
     * to a node inside the picker that is already open, so it cannot reach a list that was built
     * before its data arrived. Backing out and tapping "Choose file" again gets a *second*
     * `PickActivity`, which rebuilds every list in it — and is what a user does when a picker
     * comes up wrong. It is **not** the answer to the unreadable-screen failure in the class
     * KDoc; [requireAReadableScreen], one line above, is the part aimed at that.
     *
     * The first attempt keeps the full [PICKER_TIMEOUT_MS]; the later ones use
     * [REOPENED_TIMEOUT_MS], because by then the picker's process, its provider and its root cache
     * are all warm and the only thing being waited on is one screen. That is what keeps the cost
     * of a genuinely absent root bounded — see the class KDoc.
     */
    /**
     * The save side of SAF, end to end, against a document stock DocumentsUI created (#226).
     *
     * ## What this settles
     *
     * `publish` deletes a destination it could not write to — `docs/defect-audit.md` **D4**'s fix,
     * so a failed save does not leave a truncated file at the name the user chose — but only when
     * that destination was **positively zero bytes** first. `destinationIsKnownEmpty` is careful
     * that "I could not tell" never authorises a delete, which is right, and which makes the
     * precondition load-bearing.
     *
     * Until now that precondition was asserted only against a fake built to match it:
     * `OutputPublisherPublishTest` writes `ByteArray(0)` into `FakeSafProvider` before each case,
     * under a comment stating this is how `CreateDocument` behaves. **If it is false in production,
     * D4's fix is inert and every existing test still passes.** [RecordingPublisher] reads what SAF
     * actually handed over, at the moment `publish` sees it and before a byte is written.
     *
     * ## Why it has to go through the app, and through the picker
     *
     * Through the **picker** because a `DocumentsProvider` cannot be reached any other way —
     * measured three ways and recorded as **E7** in `docs/e2e-read-findings.md`: an unprotected one
     * is refused at install, instrumentation carries the app's uid so the test APK's own identity
     * is no help, and shell identity is denied too, each denial naming `ACTION_OPEN_DOCUMENT`.
     *
     * Through the **app** because the same constraint sinks the obvious alternative. A host
     * Activity in this source set that owns a `CreateDocument` launcher cannot be started:
     * `ActivityScenario` refuses with *"Intent in process org.libremediaconverter resolved to
     * different process org.libremediaconverter.test"*. Instrumentation runs in the target app's
     * process, so the only Activity available to drive is the app's own — which is also the more
     * faithful thing to drive.
     *
     * ## The conversion is setup, not subject
     *
     * Save is only offered on `Converted`, so the test converts first, at the screen's default
     * `MP4_H265` / `FAST`. That is **not** codec-independent, and this KDoc claimed the opposite
     * until 2026-09-06: an earlier draft used MP3 for exactly that reason, and the format had to
     * move for a different constraint the picker imposes — [convertToTheDefaultFormat] has it.
     * `MP4_H265` at `FAST` reaches `ConversionRouter`'s `canEncode(H265)` gate, so it runs on
     * FFmpeg on the emulators (no hardware H265) and on Media3 on the Pixel.
     *
     * **That is tolerable here, and #223 is the reason it needs saying.** There, the routing
     * decided whether the *subject* was reached, so a route to FFmpeg made the test pass while
     * proving nothing. Here the conversion is setup: if it goes the other way and fails, this test
     * fails loudly on the setup rather than quietly on the assertion. The subject is what `publish`
     * was handed, which the engine that produced the file does not touch.
     *
     * ## Why it carries [FailsOnEmulatorApi37]
     *
     * By inheritance, not measurement. It opens the same picker as
     * [pickingAFileThroughTheSystemPickerFillsInTheFileCard], which was marked for aborting
     * `system_server` from the task-snapshot path (#108), and then a second DocumentsUI dialog on
     * top of it. It has never been observed at API 37 either way: the rotation test truncates the
     * advisory run first, so all four advisory runs at this baseline report
     * `expected: 6, received: 4` without reaching either picker test. Marking it was the conservative choice and it is
     * recorded as unmeasured in `FailsOnEmulatorApi37.kt` rather than dressed up as a measurement.
     */
    @Test
    @FailsOnEmulatorApi37
    fun aSaveWritesToTheDocumentTheSystemPickerCreated() {
        pickTheFixture()
        convertToTheDefaultFormat()

        saveThroughTheSystemPicker()

        val destination = RecordingPublisher.seenDestination
        assertNotNull("publish was never reached, so nothing was saved", destination)
        assertTrue(
            "SAF handed back something that is not a document URI, so publish's cleanup can " +
                "never run and D4's fix is inert: $destination",
            RecordingPublisher.seenIsDocumentUri == true,
        )
        assertEquals(
            "SAF handed back a document that is not positively empty, so " +
                "destinationIsKnownEmpty answers false and a failed save keeps its partial file",
            0L,
            RecordingPublisher.seenSizeBefore,
        )

        // And the bytes really arrived, which only the failure side was covered for on a device.
        val staged = File(context.cacheDir, "conversions")
        assertArrayEquals(
            "the destination did not receive what was staged",
            RecordingPublisher.savedBytes,
            context.contentResolver.openInputStream(destination!!)!!.use { it.readBytes() },
        )
        assertTrue("staging should be empty after a successful save", staged.listFiles().isNullOrEmpty())
    }

    /**
     * The other half of D4 (#250): a save that fails deletes the document it could not write.
     *
     * ## Why this is separate from the test above
     *
     * #226 proved the *premise* — SAF hands back a document reporting exactly zero bytes, so
     * `destinationIsKnownEmpty` can answer true — and then drove the success path, where the
     * `catch` is never entered. So `deletePartialOutput` had still never run against a real
     * `DocumentsProvider`; its only assertions were `OutputPublisherPublishTest`'s, against
     * `FakeSafProvider` under Robolectric. That is the same "asserted only against a fake built to
     * match it" shape #226 was filed to break, one layer down.
     *
     * ## The forcing condition, and why it is a returned null
     *
     * [RecordingPublisher.failOpen] makes `openDestination` return null. `publish` turns that into
     * `error("Could not open destination for writing")` **after** its size probe has already run,
     * so the `catch` is reached with `destinationWasEmpty == true` on a document DocumentsUI
     * created seconds earlier. Nothing is simulated: the URI, the grant, the provider and the
     * delete are all real.
     *
     * Null rather than a throw because `openDestination`'s KDoc says a provider that is present
     * and declines is the half no fake can produce on demand — so this is also the first time that
     * arm has been taken against a live provider rather than a stub.
     *
     * ## The oracle, and why it is not a recorder inside the provider
     *
     * The obvious assertion — have the provider record what `deleteDocument` was called with, and
     * read it back — **cannot work here, and finding that out is half of what this test cost.**
     * `FixtureDocumentsProvider` is declared by the test APK and runs in
     * `org.libremediaconverter.test`; instrumentation runs in the app's process. A `static` in the
     * provider is therefore a different object from the one a test can see, and the accessor #226
     * left behind read empty on every run. That is E7's process wall from a third side, after
     * `ACTION_OPEN_DOCUMENT` and `ActivityScenario`.
     *
     * So the oracle is the document, which does cross the boundary because the app holds a URI
     * grant for it. **This is still the path rather than the artefact**, because the two
     * assertions are read together: the size query above proves the document *existed and was
     * empty* moments earlier, and a `content://` document that no longer answers a query is one
     * something deleted. Nothing else in the app deletes SAF documents.
     *
     * The staged file is asserted to **survive**, which is the deliberate other half of that
     * `catch`: a failed save may leave the staged copy as the only copy of an hour of transcoding,
     * so `ConversionViewModel` keeps it and puts "Try saving again" on screen.
     */
    @Test
    @FailsOnEmulatorApi37
    fun aFailedSaveDeletesTheDocumentItCouldNotWrite() {
        pickTheFixture()
        convertToTheDefaultFormat()

        RecordingPublisher.failOpen = true
        saveThroughTheSystemPicker(settlesOn = TestTags.RETRY_SAVE)

        val destination = RecordingPublisher.seenDestination
        assertNotNull("publish was never reached, so the delete arm was not exercised", destination)
        assertEquals(
            "the document was not positively empty, so publish would refuse to delete it",
            0L,
            RecordingPublisher.seenSizeBefore,
        )
        assertFalse(
            "publish did not delete the document it could not write: $destination",
            documentStillExists(destination!!),
        )

        // The staged copy is kept on purpose -- see ConversionViewModel.save's onFailure.
        val staged = File(context.cacheDir, "conversions")
        assertTrue(
            "a failed save must not delete the staged file; it may be the only copy",
            staged.listFiles()?.isNotEmpty() == true,
        )
    }

    /**
     * Leaves nothing for the next test's launch to reattach to.
     *
     * **In teardown rather than at the end of a test, and that placement is the point.**
     * `aFailedSaveDeletesTheDocumentItCouldNotWrite` proves that a failed save *keeps* its staged
     * file — deliberately, since it may be the only copy — so it ends with a finished job and a
     * live staged file, which is exactly what the app reattaches to on the next launch. Its
     * sibling then opened on `Converted` with no "Choose file" to tap: measured, as a 30 s timeout
     * on `converter.chooseFile` in a test that had nothing wrong with it.
     *
     * The first fix tapped "Start over" at the end of the test body. That works until the test
     * fails, and then it does not run at all — measured too, on the mutation run that proved this
     * suite bites: one real failure became two, and the second looked like an unrelated flake.
     * **One cause must produce one red test**, so the cleanup belongs where it runs either way.
     *
     * **`pruneWork` and not `cancelAllWork`, on design grounds and not on a measurement.** Only
     * finished work records need to go — that is all the next launch reattaches to — and
     * `cancelAllWork` additionally cancels live work, which is a wider blast radius than teardown
     * in a shared process needs. `pruneWork` cannot touch a job that has not run yet.
     *
     * `cancelAllWork` was **suspected** of causing an API 35 red here and did not cause it; see
     * [CONVERSION_TIMEOUT_MS], which did. A local API 35 run with `cancelAllWork` passed, and the
     * logcat showed the conversion encoding rather than cancelled. The narrower call is kept
     * because it is the right one, not because it fixed anything.
     */
    private fun clearFinishedWork() {
        WorkManager.getInstance(context).pruneWork()
        File(context.cacheDir, "conversions").listFiles()?.forEach { it.delete() }
    }

    /** Whether [destination] still answers a metadata query. A deleted document does not. */
    private fun documentStillExists(destination: Uri): Boolean = runCatching {
        context.contentResolver
            .query(destination, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { it.moveToFirst() } ?: false
    }.getOrDefault(false)

    /**
     * Runs the conversion, leaving the screen on `Converted`.
     *
     * **The format is left at its default, and that is a constraint rather than laziness.**
     * `ConverterScreen` registers `CreateDocument` with the *output's* MIME type, and
     * [FixtureDocumentsProvider] advertises `Root.COLUMN_MIME_TYPES` of `video/mp4` — deliberately,
     * so the picker's MIME filter has a mutation with a shape. DocumentsUI honours that on the save
     * side too: choosing MP3 makes the destination type `audio/mpeg`, and the fixture root is then
     * filtered out of the save dialog entirely. Measured, as *"the create-document dialog never
     * showed LMC R38 fixtures"*. The default `MP4_H265` produces `video/mp4` and the root is
     * offered.
     *
     * **The notification permission is held rather than dismissed**, which is #268's mechanism B
     * and is argued in [holdTheNotificationPermission]. The short version: `RequestPermission`
     * starts no Activity at all when the permission is already granted, so the tap below is
     * followed by no foreign window.
     *
     * **Both taps scroll first.** On `Ready` the screen carries a file card, five pickers and then
     * the button, so Convert is below the fold on a phone. `performClick` on an off-screen node
     * dispatches at a position that hits nothing and throws nothing, and `assertIsEnabled` passes
     * either way — the first version of this sat waiting for a `Converted` that could never come.
     */
    private fun convertToTheDefaultFormat() {
        awaitTheProbeHavingLanded()

        composeRule.onNodeWithTag(TestTags.Converter.CONVERT)
            .performScrollTo()
            .assertIsEnabled()
            .performClick()

        requireTheTapToHaveStartedTheJob()
        awaitNode(TestTags.SAVE_FILE, CONVERSION_TIMEOUT_MS)
    }

    /**
     * Blocks until the pick's probe has been rendered, so no relayout can straddle the next tap.
     *
     * **This is #268's mechanism A, and the argument is that it becomes impossible rather than
     * unlikely.** `ConversionViewModel.onInputPicked` writes `_state` exactly twice: once with the
     * name and size as soon as the metadata query returns, and once more with the probe filled in.
     * The second write is what grows the file card, which moves everything below it — including the
     * Convert button. Compose's injection computes the target's centre from the semantics node and
     * dispatches the touch afterwards; a relayout in that gap hit-tests the stationary coordinate
     * against the *new* layout, so the down and the up land on whatever moved into the button's old
     * place. Nothing throws. Measured on the two failing gating legs as the gap between the pick's
     * FFprobe closing and the tap: 319 ms and 421 ms passed, 46 ms, 98 ms and 124 ms did not.
     *
     * A detail row can only be composed from that second write, because `FileCard` renders the rows
     * exclusively under `input.probe != null`. So once one exists, both of `onInputPicked`'s writes
     * have landed and been laid out, and every `_state` write still in flight is either landed or
     * superseded.
     *
     * `reattach` has **three** outcomes here, not two. It returns on its `_state.value !is Idle`
     * guard; or it finds nothing; or — because `pruneWork()` is async and can leave a finished job
     * unpruned — it passes that guard and starts an `observe()`. This paragraph used to name only
     * the first two, which was wrong rather than merely incomplete: the third is a live coroutine
     * with writes ahead of it.
     *
     * It is still harmless, and by a different mechanism than the guard. `reattach` reads
     * `ownership.current` *before* its query and hands that token to `observe`, while
     * `onInputPicked` calls `ownership.claim()` synchronously on the pick — so by the time a
     * detail row exists the observation is superseded, and every emission returns at
     * `stillHeldBy` before it writes. Outside that path `observe` is not started until
     * `convert()` runs. **The card cannot change height again before the tap**, which is a
     * different claim from waiting longer.
     *
     * The `Container` row specifically, rather than a new "probing finished" tag in `main`, because
     * this fixture is an MP4 video and that row is already what
     * [pickingAFileThroughTheSystemPickerFillsInTheFileCard] waits on and asserts. It is a
     * *presence* wait, which cannot be satisfied by a composition that is momentarily absent — an
     * absence wait can, and that would tap into nothing.
     *
     * **Not in [pickTheFixture].** The rotation test does not tap a Compose affordance in this
     * window at all, and the picker test already makes this exact wait its own assertion. Putting
     * it here keeps a broken read grant reddening one test with the message that explains it.
     */
    private fun awaitTheProbeHavingLanded() {
        awaitNode(TestTags.Converter.detailRow(CONTAINER_LABEL))
    }

    /**
     * Fails fast if the Convert tap started nothing, instead of waiting out the conversion budget.
     *
     * **A diagnostic, not the synchronisation** — [awaitTheProbeHavingLanded] is what makes the tap
     * land, and this cannot rescue a tap that did not. It exists because of what a lost tap used to
     * look like: `ComposeTimeoutException`, 300000 ms for `action.saveFile`, five minutes after a
     * screen that had never left `Ready`, which names the save affordance and says nothing about
     * the tap two steps earlier. Every #268 failure was read from logcat rather than from the
     * message, and this is the message it should have had.
     *
     * The condition is monotonic and needs no budget of its own: `convert()` sets `Converting`
     * synchronously, and `Ready` is the only state that renders a Convert button, so once the tag
     * is gone it stays gone. [APP_TIMEOUT_MS] rather than a new constant, because "the app should
     * have reacted by now" is exactly what that number already means here.
     */
    private fun requireTheTapToHaveStartedTheJob() {
        val tag = TestTags.Converter.CONVERT
        try {
            composeRule.waitUntil("the Convert tap left the Ready screen", APP_TIMEOUT_MS) {
                // A composition that is momentarily absent throws, and must read as "not yet"
                // rather than as "the button is gone" -- see awaitNode.
                runCatching { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
                    .getOrDefault(false)
            }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError(
                "the Convert tap did not start a conversion: $tag is still on screen " +
                    "${APP_TIMEOUT_MS}ms after it was clicked, so the screen never left Ready",
                timeout,
            )
        }
    }

    /**
     * Taps Save and drives the create-document dialog into the fixture root.
     *
     * Retried whole, for the reason [pickTheFixture] documents: a dialog that came up unreadable
     * cannot be recovered from inside, and a fresh one is the only answer.
     */
    private fun saveThroughTheSystemPicker(settlesOn: String = TestTags.Converter.CONVERT_ANOTHER) {
        var missing: BySelector? = null
        repeat(PICK_ATTEMPTS) { attempt ->
            requireAReadableScreen()
            composeRule.onNodeWithTag(TestTags.SAVE_FILE).performClick()
            missing = walkTheSaveDialog(
                if (attempt == 0) PICKER_TIMEOUT_MS else REOPENED_TIMEOUT_MS,
            )
            if (missing == null) {
                // The node that says the save has *finished*, either way. Waiting on the success
                // one when the save is meant to fail would time out on a test that is working.
                awaitNode(settlesOn, SAVE_TIMEOUT_MS)
                return
            }
            dismissThePicker()
        }
        throw AssertionError(
            "the create-document dialog never showed $missing, in $PICK_ATTEMPTS separate " +
                "dialogs (the last one left ${device.currentPackageName} in front)",
        )
    }

    /** Into the fixture root, then Save. Returns the selector never found, or null. */
    private fun walkTheSaveDialog(timeoutMs: Long): BySelector? {
        val picker = By.pkg(DOCUMENTS_UI_PACKAGE)
        val root = By.text(FixtureDocumentsProvider.ROOT_TITLE)
        return when {
            device.wait(Until.hasObject(picker), timeoutMs) != true -> picker
            !tapPickerNode(root, timeoutMs, ifAbsent = ::openTheRootsDrawer) -> root
            !tapPickerNode(SAVE_BUTTON, timeoutMs) -> SAVE_BUTTON
            else -> null
        }
    }

    private fun pickTheFixture() {
        var missing: BySelector? = null
        repeat(PICK_ATTEMPTS) { attempt ->
            requireAReadableScreen()
            openThePicker()
            missing = walkThePickerToTheFixture(
                if (attempt == 0) PICKER_TIMEOUT_MS else REOPENED_TIMEOUT_MS,
            )
            if (missing == null) {
                awaitNode(TestTags.Converter.FILE_CARD_NAME)
                return
            }
            dismissThePicker()
        }
        throw AssertionError(
            "the system picker never showed $missing, in $PICK_ATTEMPTS separate pickers " +
                "(the last one left ${device.currentPackageName} in front)",
        )
    }

    /**
     * Refuses to go near the picker until this process can read a window it already knows is there.
     *
     * **This is the check that would have answered #93 outright**, instead of leaving six PRs to
     * infer a SAF fault from a picker that was never the problem. It is here because of what the
     * failing logcat counts. Across the whole API 34 leg UiAutomator
     * asked for a node 1095 times and logged `Node not found` 1095 times — it never read anything,
     * from the first query of the run onwards. The green leg of the same job asked 7 times and
     * found 5. So the window list `UiDevice` searches, `UiAutomation.getWindows()`, was empty for
     * that entire instrumentation run; on API 21 and up that list is the *only* place
     * `getWindowRoots` looks, so an empty one makes every selector unfindable and says nothing
     * about the app, the picker or the fixture.
     *
     * The probe is deliberately the app's **own** window, asked while the app is in front and
     * before anything is tapped. It is the one window that must be readable for any of the rest to
     * mean anything, so a failure here is unambiguous — where "the picker never showed the root"
     * was not, and is what sent #93 looking at package installation and root caches.
     *
     * The repair is [rebuildUiAutomation]. It has been forced on and measured — a rebuilt
     * connection still reads windows, which is the way it could have been worse than nothing —
     * but it has **never been run against the real fault**, because the fault has never been
     * reproduced on demand. See the class KDoc. What is certain is that a fresh picker is *not*
     * the repair: the failing leg opened a second `PickActivity` for the second test, in the
     * same DocumentsUI process, and read exactly as little from it.
     */
    private fun requireAReadableScreen() {
        val app = By.pkg(appPackage)
        if (device.wait(Until.hasObject(app), READABLE_TIMEOUT_MS) == true) return
        dismissASystemErrorDialog()
        if (device.wait(Until.hasObject(app), READABLE_TIMEOUT_MS) == true) return
        unlockTheDevice()
        if (device.wait(Until.hasObject(app), READABLE_TIMEOUT_MS) == true) return
        rebuildUiAutomation()
        if (device.wait(Until.hasObject(app), READABLE_TIMEOUT_MS) != true) {
            throw AssertionError(
                "UiAutomator cannot see this app's own window, so it could not have seen the " +
                    "picker's either. This is not a SAF failure. Closing a system error dialog, " +
                    "waking the device, dismissing the keyguard and rebuilding the UiAutomation " +
                    "connection all failed to make it readable. What it could see: " +
                    describeWindows(),
            )
        }
    }

    /**
     * Closes a system "isn't responding" dialog, if that is what is on top of the app.
     *
     * **This is the occluder #93 turned out to have**, and it took the window list in the failure
     * message to find it. `AppNotRespondingDialog` belongs to `system_server`, so it is the
     * `android[type=3]` in `com.android.systemui[type=3], android[type=3]` — and it is opaque and
     * fullscreen, so `AccessibilityWindowManager` drops every application window beneath it. The
     * app is `Displayed` and unreadable at the same time, which is exactly the contradiction this
     * class spent #93 failing to explain. It is not even this app's dialog:
     *
     * ```
     * ANR in com.google.android.apps.nexuslauncher (com.google.android.apps.nexuslauncher/.NexusLauncherActivity)
     * Reason: Input dispatching timed out (Application does not have a focused window)
     * Window{4ed8414 u0 Application Not Responding: com.google.android.apps.nexuslauncher}
     * ```
     *
     * The launcher ANRs on a loaded runner emulator minutes before this class runs, and the dialog
     * it leaves behind never goes away on its own.
     *
     * Dismissed by resource id rather than by button text, because the text is localised and the
     * ids are not, and by id rather than by "the first button in the system window", because that
     * would click whatever system window happened to be there. `aerr_wait` first: it dismisses the
     * dialog and leaves the offending app alone, which is the polite answer when the app is not
     * ours. Back is not tried — `BaseErrorDialog` swallows key events.
     */
    private fun dismissASystemErrorDialog() {
        for (id in ERROR_DIALOG_BUTTONS) {
            val button = device.findObject(By.res(id)) ?: continue
            button.click()
            device.waitForIdle()
            return
        }
    }

    /**
     * Wakes the display and asks the keyguard to go away.
     *
     * The cheapest explanation for "this process cannot see the app's own window" is that
     * something is in front of it, and on a runner emulator that something is the lock screen:
     * these images come up unprovisioned, and `KeyguardViewMediator` says so in as many words --
     * `we need to show the keyguard since the device isn't provisioned yet`. An occluded window is
     * not in the accessibility window list, which is the same symptom as a broken connection and
     * has a far more ordinary cause.
     *
     * `wm dismiss-keyguard` rather than a swipe, because it is a request to the window manager
     * rather than a gesture that has to land somewhere this process cannot see. It is only
     * attempted on the failure path -- a device that was readable never reaches here -- so a run
     * where the keyguard was never up pays nothing and is not altered.
     */
    private fun unlockTheDevice() {
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        device.waitForIdle()
    }

    /** The accessibility window list, for a failure message that says what was actually there. */
    private fun describeWindows(): String {
        val windows = InstrumentationRegistry.getInstrumentation().uiAutomation.windows
        if (windows.isEmpty()) return "no windows at all (UiAutomation.getWindows() is empty)"
        return windows.joinToString(", ") { "${it.root?.packageName ?: "?"}[type=${it.type}]" }
    }

    /**
     * Tears down this run's `UiAutomation` connection and establishes a new one.
     *
     * `Instrumentation.getUiAutomation` hands back the existing connection unless the flags differ
     * from the ones it was created with, in which case it destroys it and builds another — so
     * asking for different flags and then for the original ones back is how a test reaches the
     * connection at all. `UiDevice` re-reads the flags from `Configurator` on every call rather
     * than caching an instance, so the next selector goes through the new connection.
     *
     * `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES` is toggled rather than chosen: it is only being
     * used as a value that differs from whatever is configured, and it is put back.
     *
     * **Forced on and measured, because the obvious way for this to be worse than nothing is
     * silent.** `UiDevice` puts `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` on the service info during its
     * own initialisation, and `getWindows()` is empty without it — so a rebuilt connection that
     * did not get the flag back would cause exactly the emptiness this is meant to cure, on the
     * one path where it is the last hope. Run unconditionally on every attempt, on a cold API 34
     * emulator, both tests passed, and logcat shows the connection really being replaced rather
     * than handed back: `Init UiAutomation[id=2, flags=0]`, then `id=4, flags=1`, then
     * `id=6, flags=0`, with `Registering UiTestAutomationService` between each.
     */
    private fun rebuildUiAutomation() {
        val configurator = Configurator.getInstance()
        val flags = configurator.uiAutomationFlags
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        configurator.uiAutomationFlags = flags xor UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        instrumentation.getUiAutomation(configurator.uiAutomationFlags)
        configurator.uiAutomationFlags = flags
        instrumentation.getUiAutomation(flags)
    }

    /** Waits for the app to be showing its own screen again, then asks for a picker. */
    private fun openThePicker() {
        awaitNode(TestTags.Converter.CHOOSE_FILE)
        composeRule.onNodeWithTag(TestTags.Converter.CHOOSE_FILE).performClick()
    }

    /**
     * Null once the fixture URI is with the app, or the selector whose list never carried it.
     *
     * Three things have to be there, in order, and the `when` names them in that order so that a
     * failure says which one was missing rather than "the picker did not work".
     *
     * **The first branch is what tells an unreadable picker from an absent root.** In #93 neither
     * the root *nor the toolbar's "Show roots" button* could be found for sixty seconds, and a
     * stale roots list would have left the toolbar findable. Both arrived as one message. Asking
     * for the picker's package on its own separates them: `never showed BySelector [PKG=...]`
     * means the picker was not readable, and the root selector means the root was not offered.
     *
     * The second is the line the MIME filter mutation fails on: DocumentsUI matches the requested
     * types against `Root.COLUMN_MIME_TYPES` and drops the roots that cannot answer, so a filter
     * the fixture root does not satisfy takes the root out of the picker altogether — along with
     * "Images", "Audio", "Videos" and "Documents", measured on API 34.
     *
     * **The third takes no recovery action of its own, and that is deliberate rather than an
     * oversight.** [openTheRootsDrawer] exists because a root has a *second* place it can be
     * shown; a document in a directory listing has no second place, so there is nothing an
     * in-picker action could do. Its recovery is the outer loop: a fresh picker re-walks from
     * Recent into the root, which rebuilds the directory listing as well as the roots strip.
     */
    private fun walkThePickerToTheFixture(timeoutMs: Long): BySelector? {
        val picker = By.pkg(DOCUMENTS_UI_PACKAGE)
        val root = By.text(FixtureDocumentsProvider.ROOT_TITLE)
        val fixture = By.text(FixtureDocumentsProvider.FIXTURE_DISPLAY_NAME)
        return when {
            device.wait(Until.hasObject(picker), timeoutMs) != true -> picker
            !tapPickerNode(root, timeoutMs, ifAbsent = ::openTheRootsDrawer) -> root
            !tapPickerNode(fixture, timeoutMs) -> fixture
            else -> null
        }
    }

    /**
     * The picker's own drawer, opened only when the root was not on the screen it landed on.
     *
     * **In practice it never runs, and #80 was right to say so.** A hierarchy dump taken on a
     * cold API 34 emulator while this test was passing has the fixture root on the landing
     * screen — `text="LMC R38 fixtures"` at `android:id/title`, under a `BROWSE FILES IN OTHER
     * APPS` header — with the drawer shut (`Show roots` present, `Hide roots` absent). So the
     * roots strip is the normal path and the drawer is a widening, kept because a device with a
     * populated Recent may push the strip off screen. Looking in a second place widens where the
     * root is searched for; it does not weaken what has to be found, which is still this root.
     */
    private fun openTheRootsDrawer() {
        device.findObject(By.desc(SHOW_ROOTS_DESCRIPTION))?.click()
    }

    /**
     * Backs out of the picker until the app has the window focus again.
     *
     * **The focus is asked of the Activity, not of UiAutomator, and that is not a stylistic
     * choice.** The failure this retry exists for is a picker window UiAutomator cannot see, so a
     * probe that went through the same accessibility window list would cheerfully report "the
     * picker is gone" about the window that is still in front — and the reopened pick would then
     * tap "Choose file" behind it. `Activity.hasWindowFocus` comes from the framework instead, and
     * answers about the app rather than about the picker.
     *
     * It is also why this counts backs rather than pressing a fixed number of them. One back is
     * enough from Recent and two are needed from inside the root, but a third from Recent would
     * finish `MainActivity` and take the rest of the test with it.
     *
     * **[forceStopThePicker] is the escalation after the presses, and it exists because a back
     * press is not always deliverable.** See its own KDoc for the measurement.
     */
    private fun dismissThePicker() {
        repeat(BACK_PRESSES) {
            if (awaitAppFocus()) return
            // Before the back press, not instead of it: an app-error dialog swallows key events,
            // so a back aimed at the picker lands on the dialog and nothing moves. Measured --
            // API 34 of run 32813885120 exhausted all four presses with `android` in front, which
            // is that dialog, while the launcher it belonged to went on ANRing behind everything.
            dismissASystemErrorDialog()
            device.pressBack()
        }
        // The check after the last press, and not a spare one: `repeat` presses on its final
        // iteration too, so without this a dismissal that worked on the last press would still be
        // reported as a failure to close.
        if (awaitAppFocus()) return
        forceStopThePicker()
        if (!awaitAppFocus()) {
            throw AssertionError(
                "the system picker would not close: after $BACK_PRESSES back presses and a " +
                    "force-stop of $DOCUMENTS_UI_PACKAGE the app still does not have the window " +
                    "focus, and ${device.currentPackageName} is in front. What could be seen: " +
                    describeWindows(),
            )
        }
    }

    /**
     * Kills the picker's process, for when no back press can reach it.
     *
     * **The failure this exists for cannot be answered with input, and that is the whole point.**
     * Measured on the gating API 37 legs of runs 34006456986 and 34001744574, which fail this way
     * and whose logcats say the same thing in the same order. `UiObject2.click()` on the fixture's
     * root is injected at the node's centre and the framework discards it —
     * `InputDispatcher: No new touched window at (539.0, 525.0) in display 0` — because
     * `PickActivity` has published accessibility nodes but has no touchable window there yet.
     * `click()` cannot see that and returns normally, so the walk goes on to wait out
     * [PICKER_TIMEOUT_MS] for a fixture that was never navigated to. By the time this function's
     * caller starts pressing back, WindowManager is still saying
     * `no window has focus but ...PickActivity may eventually add a window when it finishes
     * starting up` — and goes on saying it for another 63 s. Every one of the four presses is
     * dropped, and DocumentsUI ANRs on `Input dispatching timed out`.
     *
     * So the picker is in front, unreachable by key or by touch, and [pickTheFixture]'s whole
     * point — that a second `PickActivity` rebuilds every window and list in it — is unreachable
     * with it. `am force-stop` goes around input entirely: `UiAutomation` runs shell commands as
     * uid 2000, which holds `FORCE_STOP_PACKAGES`, so the picker's process is killed, its
     * activity leaves the task it was launched into, and `MainActivity` — the activity below it in
     * that same task — is resumed with the focus.
     *
     * **Only on the failure path**, after every back press has been spent, so a picker that closes
     * the ordinary way never reaches this and is not altered by it. If the framework itself is
     * gone, this cannot help either, and the caller still reports what it could see.
     */
    private fun forceStopThePicker() {
        device.executeShellCommand("am force-stop $DOCUMENTS_UI_PACKAGE")
        device.waitForIdle()
    }

    /** True once [MainActivity] has the window focus, false if it does not take it in time. */
    private fun awaitAppFocus(): Boolean = try {
        composeRule.waitUntil("the app has the window focus back", FOCUS_TIMEOUT_MS) {
            composeRule.activity.hasWindowFocus()
        }
        true
    } catch (_: ComposeTimeoutException) {
        false
    }

    /**
     * Finds the picker node [selector] names and taps it, re-finding it if it goes stale.
     *
     * **The re-finding is not padding, and this is not a retry of the assertion.** A `UiObject2`
     * holds an `AccessibilityNodeInfo` captured when it was found, and DocumentsUI is still
     * settling when the node first appears — its list rebinds, the roots strip lays out, a window
     * animates. If the node is replaced in that gap, `click()` throws `StaleObjectException`
     * against the handle rather than missing the target. Measured on a cold API 34 emulator:
     *
     * ```
     * androidx.test.uiautomator.StaleObjectException
     *   at androidx.test.uiautomator.UiObject2.getAccessibilityNodeInfo(UiObject2.java:1042)
     *   at androidx.test.uiautomator.UiObject2.click(UiObject2.java:526)
     * ```
     *
     * So what is retried is *acquiring a handle to a node that has to be there anyway*. **A node
     * that is simply not in this picker is reported rather than retried here** — it comes back as
     * `false`, and [pickTheFixture] answers it with a whole new picker, which is the only thing
     * that rebuilds a list or a window. The MIME mutation's bite is untouched either way: a root
     * that is not in the picker is not found on any attempt or in any picker, and the failure is
     * still "the system picker never showed" rather than a stale one.
     */
    private fun tapPickerNode(selector: BySelector, timeoutMs: Long, ifAbsent: () -> Unit = {}): Boolean {
        var stale: StaleObjectException? = null
        repeat(TAP_ATTEMPTS) { attempt ->
            // ifAbsent only on the first attempt: it navigates, and re-navigating from a screen it
            // already reached would walk away from the node.
            val node = awaitPickerNode(selector, timeoutMs, if (attempt == 0) ifAbsent else ({}))
                ?: return false
            device.waitForIdle()
            try {
                node.click()
                return true
            } catch (e: StaleObjectException) {
                stale = e
            }
        }
        throw AssertionError("$selector kept going stale between finding it and tapping it", stale)
    }

    /**
     * The picker node [selector] names, or null if this picker never showed it.
     *
     * [ifAbsent] runs once, after the first wait comes up empty, and then the wait is repeated. A
     * null return from `findObject` is deliberately not an error there: it is the "already on the
     * right screen" case.
     */
    private fun awaitPickerNode(selector: BySelector, timeoutMs: Long, ifAbsent: () -> Unit) =
        device.wait(Until.findObject(selector), timeoutMs)
            ?: run {
                ifAbsent()
                device.wait(Until.findObject(selector), timeoutMs)
            }

    /**
     * Blocks until [tag] is in the composition, so an assertion cannot race the picker's result.
     *
     * The described overload of `waitUntil`, not the bare one. A timeout is how both of this
     * class's mutations report themselves, and the bare overload's message is
     * `Condition still not satisfied after 30000 ms` — which names neither the node nor the test.
     * With the description it says which affordance never arrived, which is the whole finding.
     */
    /** Starts counting [MainActivity] creations, so [awaitRecreation] can wait for the next one. */
    private fun watchForRecreation() {
        recreations.set(0)
        ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(recreationWatcher)
    }

    /**
     * Waits for the rotation to actually rebuild [MainActivity], which `waitForIdle` does not.
     *
     * **This is #122.** `waitForIdle()` waits for the compose hierarchy to settle. Immediately
     * after a rotation the window manager has accepted but not yet delivered as a configuration
     * change, the *old* Activity's composition is already idle — so it returns, `composeRule
     * .activity` still resolves to the old instance, and the guard below reads an unchanged
     * identity hash. That is the clean `AssertionError` seen on the API 33 gating leg of #217, and
     * the wedges on #122 are the same race taken the other way: land while the composition is
     * being torn down and there is nothing coherent for `waitForIdle` to settle on.
     *
     * A bounded wait is worth having even if that second half is wrong. It turns a 20-minute
     * `WEDGE_TIMEOUT` — which costs the leg and names no test — into a fast failure that says which
     * test and what it was waiting for.
     */
    private fun awaitRecreation() {
        composeRule.waitUntil(
            "the rotation did not recreate MainActivity within $RECREATION_TIMEOUT_MS ms",
            RECREATION_TIMEOUT_MS,
        ) {
            recreations.get() > 0
        }
    }

    /**
     * Waits for [tag], treating "the app has no composition right now" as *not yet* rather than
     * as a failure.
     *
     * `fetchSemanticsNodes` **throws** `IllegalStateException: No compose hierarchies found in the
     * app` when nothing is attached at that instant, and `waitUntil` propagates it on the first
     * poll instead of waiting out the deadline. This class spends much of its time with another
     * app in front — the picker and the create-document dialog, and until #268 the permission
     * dialog too — so there is always a window where the app is coming back and has no composition
     * yet. Before this, that
     * window was a hard failure: measured on the API 34 leg of run 34057196628, where **both** SAF
     * tests died that way while the same commit passed API 33, 35, 36 and 37, and the previous
     * commit passed API 34 and failed 35. A failing leg that moves between runs is #190's
     * emulator flake, and this is the one place in the class that turned it into a red test.
     *
     * **The cost is honest and bounded**: an app that is genuinely gone now fails at the deadline
     * rather than immediately, so the last composition error is carried into the message to keep
     * that case diagnosable.
     */
    private fun awaitNode(tag: String, timeoutMs: Long = APP_TIMEOUT_MS) {
        var lastError: Throwable? = null
        try {
            composeRule.waitUntil("a node tagged $tag exists", timeoutMs) {
                runCatching { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
                    .onFailure { lastError = it }
                    .getOrDefault(false)
            }
        } catch (timeout: ComposeTimeoutException) {
            val note = lastError?.let { "; last composition error: ${it.message}" } ?: ""
            throw AssertionError("waited ${timeoutMs}ms for a node tagged $tag$note", timeout)
        }
    }

    private companion object {

        /**
         * Generous on purpose. This waits on another app being started, and on FFprobe spawning a
         * native process over a `content://` URI; a timeout that merely usually passes is a flaky
         * gating leg on five API levels, which costs far more than the seconds it saves.
         */
        const val PICKER_TIMEOUT_MS = 30_000L
        const val APP_TIMEOUT_MS = 30_000L

        /**
         * Bounds a hang, and **the first number here was measured on one API level and wrong on
         * another.** It read 120 s, on the strength of the whole test taking 11.8 s on the API 34
         * CI leg (run 34043502322). API 35 is a different machine: on run 34056545386 the fixture's
         * `libx265 -crf 24 -preset veryfast` encode started at `20:05:26.897` and the next job in
         * the suite did not appear until `20:07:41.693` — **134.8 s**, so the encode was still
         * running when the 120 s bound expired and the test failed with the conversion healthy.
         *
         * The logcat is what settles it: `ConversionWorker` logs the route and `FFmpegEngine` the
         * command, and there is no cancel between them. A timeout that fires on a working
         * conversion is worse than no bound, because it reads as a product failure.
         *
         * 300 s is chosen against that 134.8 s, not against API 34's 11.8 s. **Do not re-tighten
         * it from a fast leg's timing** — the encode is software on every emulator here, and the
         * spread between images is larger than any margin a single measurement would suggest.
         */
        const val CONVERSION_TIMEOUT_MS = 300_000L

        /** The copy is a few kilobytes, but it crosses a provider. */
        const val SAVE_TIMEOUT_MS = 30_000L

        /**
         * DocumentsUI's save button. Case-insensitive because the label is "SAVE" on some images
         * and "Save" on others, and the difference is not what this test is about.
         */
        val SAVE_BUTTON: BySelector = By.text(Pattern.compile("save", Pattern.CASE_INSENSITIVE))

        /**
         * The same wait once a picker has already come and gone, and shorter for a reason.
         *
         * What [PICKER_TIMEOUT_MS] is generous about is a cold start: DocumentsUI's process, the
         * fixture's provider process, the root cache. By the second attempt all three are warm and
         * the only thing left to wait on is one screen being laid out — measured at 2.7 to 3.4 s
         * from the picker starting, on cold CI emulators at API 33, 34 and 35. Ten seconds is
         * three times the worst of those, and it is what keeps a genuinely absent root — the MIME
         * mutation — from costing three full-length attempts.
         */
        const val REOPENED_TIMEOUT_MS = 10_000L

        /**
         * How long a rotation is given to destroy and rebuild the Activity.
         *
         * Generous against the API 33 and 34 emulators #122 was measured on, where the rotation is
         * slow enough for the gap this bound exists to cover to be observable at all — and still
         * two orders of magnitude inside the 1200 s `WEDGE_TIMEOUT` it replaces.
         */
        const val RECREATION_TIMEOUT_MS = 15_000L

        /**
         * How long the app is given to take the window focus back after a back press.
         *
         * Short, because this is asked once per back press and the first one is always asked while
         * the picker is still in front, where it is *expected* to time out.
         */
        const val FOCUS_TIMEOUT_MS = 3_000L

        /**
         * How long this process is given to be able to read the screen at all.
         *
         * Short, and it is not waiting on anything being drawn: the app is already in front
         * when this is asked. It is waiting only on the accessibility window list existing,
         * which either does within a poll or two or -- as in #93 -- not at all.
         */
        const val READABLE_TIMEOUT_MS = 5_000L

        /** `Surface.ROTATION_0`, named rather than `0` so the comparison reads. */
        const val NATURAL_ROTATION = 0

        /**
         * How many pickers the fixture may fail to appear in before that is the finding.
         *
         * Three. Each one is a fresh `PickActivity` -- a fresh window, a fresh accessibility
         * registration, a fresh roots query and a fresh directory load -- so this bounds the thing
         * #93 measured, which is a picker that came up unreadable *once*. A root that is genuinely
         * not offered is absent from all three, which is what keeps #64's MIME mutation red.
         */
        const val PICK_ATTEMPTS = 3

        /**
         * How many back presses may be spent getting out of a picker.
         *
         * One is enough from Recent, two from inside the fixture's own directory. Four leaves room
         * for a picker that has been navigated deeper than this test ever navigates it, and stops
         * well short of the count that would start finishing `MainActivity` instead.
         */
        const val BACK_PRESSES = 4

        /**
         * The package the system picker runs in.
         *
         * Named rather than resolved: `PackageManager.resolveActivity` is deprecated from API 33
         * and its replacement is a lint argument this test does not need to have. A wrong value
         * here cannot pass silently -- it is the first thing [walkThePickerToTheFixture] looks
         * for, so the failure would read `never showed BySelector [PKG='...']` on every device.
         * It is `com.google.android.documentsui` on every `google_apis` emulator image the CI
         * matrix uses and on the Pixel 10 Pro XL.
         */
        const val DOCUMENTS_UI_PACKAGE = "com.google.android.documentsui"

        /**
         * How many times a picker node may be re-found before its staleness is the finding.
         *
         * Three, not "until the timeout". Each attempt already waits up to [PICKER_TIMEOUT_MS] for
         * the node to exist, so this bounds only the settling window after it does; a node that is
         * still being replaced after three of those is telling you something about the device, and
         * a loop that hid it would be the flake rather than the fix.
         */
        const val TAP_ATTEMPTS = 3

        /**
         * The buttons on the framework's app-error dialogs, by resource id.
         *
         * `aerr_wait` is first because it dismisses the dialog without killing the app under it,
         * and the app under it is usually the launcher rather than anything this suite owns.
         * `button1` catches the plainer `BaseErrorDialog` shapes that have no `aerr_` ids.
         */
        val ERROR_DIALOG_BUTTONS = listOf(
            "android:id/aerr_wait",
            "android:id/aerr_close",
            "android:id/button1",
        )

        /** DocumentsUI's drawer button. It carries no text, only this description. */
        const val SHOW_ROOTS_DESCRIPTION = "Show roots"

        /** The detail row `MediaProbe` fills in for anything it could open and identify. */
        const val CONTAINER_LABEL = "Container"
    }
}

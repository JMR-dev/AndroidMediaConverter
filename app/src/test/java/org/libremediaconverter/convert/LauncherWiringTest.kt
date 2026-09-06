package org.libremediaconverter.convert

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.media3.common.util.UnstableApi
import androidx.work.Data
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.join.JoinScreen
import org.libremediaconverter.model.InputProbe
import org.libremediaconverter.ui.TestTags
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowActivity

/**
 * The launcher layer above the `ScreenContent` seam — registered, and until now never resulted.
 *
 * ## The hazard this exists for
 *
 * `ConversionViewModel.onInputPicked(uri: Uri)` and `.save(destination: Uri)` are **both
 * `(Uri) -> Unit`**, so swapping the two launcher callbacks at `ConverterScreen.kt:70` and `:83`
 * compiles, renders, and passes the entire suite. Picking a file would attempt a save to it, and
 * choosing a destination would load it as input.
 *
 * That is precisely the defect class `ScreenWiringTest` exists for, on the one pair it declines to
 * cover: it drives `converterActions` directly and says the launcher-backed actions stay
 * parameters. Correct for the `actions` seam, and it leaves the edge above that seam unpinned.
 *
 * Join's equivalents (`JoinScreen.kt:45`, `:55`) are `List<Uri>` and `Uri`, so they are **not**
 * transposable and need no such test. The picker filter is a different matter and is covered below
 * for both screens.
 *
 * ## The two mechanics, verified before the assertions were written
 *
 * Neither is used anywhere else in the suite, so both were spiked first:
 *
 * - **Reading what was launched** — `shadowOf(activity).nextStartedActivityForResult`, which returns
 *   the `Intent` with its `EXTRA_MIME_TYPES` intact.
 * - **Delivering a result** — `shadowOf(activity).receiveResult(...)`, which reaches
 *   `ComponentActivity`'s `ActivityResultRegistry` and fires the `rememberLauncherForActivityResult`
 *   callback.
 *
 * `createAndroidComposeRule`, as `AdaptiveShellTest` uses and for the reason it gives: the screens
 * compose real ViewModels through `viewModel()`, and the plain rule supplies no `ViewModelStoreOwner`.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class LauncherWiringTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        installTestWorkManager(app, Data.EMPTY)
        // The real screen composes a real ViewModel; neither test here is about probing.
        ConversionDependencies.probe = { _, _ -> InputProbe() }
    }

    @After
    fun tearDown() = ConversionDependencies.reset()

    /**
     * The transposition guard. A picked file has to reach `onInputPicked`, which is observable as
     * the screen arriving at `Ready` with the file card showing — `save()` from `Idle` returns at
     * its own guard and leaves nothing behind.
     *
     * ## Why this waits rather than asserting straight away (#220)
     *
     * `onInputPicked` does not reach `Ready` on the calling thread. It hops twice —
     * `withContext(pickDispatcher) { InputQuery.describe(...) }` and then the probe — and
     * `pickDispatcher` defaults to `Dispatchers.IO`, a real background thread that Compose's
     * idling does not know about. `deliver` therefore returns with the state still `Idle` more
     * often than not, and asserting immediately was a race the test usually won.
     *
     * It lost five times on CI in one day, on PRs whose diffs were instrumented tests and
     * documentation, which is what #220 was filed for. `waitUntil` polls through
     * `waitForIdle`, so it drains the main looper each time round and sees the recomposition that
     * the IO hop eventually posts back.
     *
     * **Injecting the dispatcher would be better and is not available here.** `pickDispatcher` is
     * a constructor parameter precisely so a test can pin it, but this test composes the real
     * `ConverterScreen`, which resolves its own ViewModel through `viewModel()` — the seam exists
     * one layer below the thing under test. Pinning it would mean not testing the launcher edge,
     * which is the whole point of this class.
     *
     * The wait does not weaken the assertion: transposing the two callbacks leaves the screen in
     * `Idle` forever, so it fails on the timeout with the same meaning it failed with before.
     */
    @Test
    fun `a picked document is loaded as input rather than saved to`() {
        composeRule.setContent { ConverterScreen() }

        composeRule.onNodeWithTag(TestTags.Converter.CHOOSE_FILE).performClick()
        deliver(Uri.parse("content://test/holiday.mkv"))

        composeRule.waitUntil(PICK_TIMEOUT_MS) {
            composeRule.onAllNodesWithTag(TestTags.Converter.FILE_CARD_NAME)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag(TestTags.Converter.FILE_CARD_NAME).assertIsDisplayed()
    }

    /**
     * `ConverterScreen.kt:65-67` records why the all-types wildcard is load-bearing rather than lazy:
     *
     * > the picker is images and video only, offers no audio at all, and will not reliably surface
     * > .mkv/.flac/.webm
     *
     * Narrowing it would make every audio conversion unreachable from the file picker, and nothing
     * would have gone red. (The literal is spelled only in the assertion below: a KDoc cannot
     * contain it, because the wildcard's second half closes the comment.)
     */
    @Test
    fun `the converter picker asks for every type, not just the ones a photo picker offers`() {
        composeRule.setContent { ConverterScreen() }

        composeRule.onNodeWithTag(TestTags.Converter.CHOOSE_FILE).performClick()

        val intent = launched().intent
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertEquals(listOf("*/*"), intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList())
    }

    @Test
    fun `the join picker asks for video and accepts more than one file`() {
        composeRule.setContent { JoinScreen() }

        composeRule.onNodeWithTag(TestTags.Join.CHOOSE_FILES).performClick()

        val intent = launched().intent
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertEquals(listOf("video/*"), intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList())
        // A join of one file is not a join; the contract is what asks for several.
        assertEquals(true, intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
    }

    private fun launched(): ShadowActivity.IntentForResult {
        composeRule.waitForIdle()
        return requireNotNull(shadowOf(composeRule.activity).nextStartedActivityForResult) {
            "nothing was launched for a result"
        }
    }

    private fun deliver(uri: Uri) {
        val started = launched()
        shadowOf(composeRule.activity).receiveResult(
            started.intent,
            Activity.RESULT_OK,
            Intent().setData(uri),
        )
        composeRule.waitForIdle()
    }

    private companion object {
        /**
         * Long enough that a slow CI runner is not the reason this fails, short enough that a
         * genuinely transposed callback does not stall the suite. The pick normally lands in
         * single-digit milliseconds.
         */
        const val PICK_TIMEOUT_MS = 10_000L
    }
}

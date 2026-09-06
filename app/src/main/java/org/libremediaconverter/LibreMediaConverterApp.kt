package org.libremediaconverter

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.libremediaconverter.convert.OutputPublisher

/**
 * Exists for one reason: to sweep abandoned files out of `<cacheDir>/conversions/` once per
 * process.
 *
 * Every other cleanup path in the app depends on a ViewModel still being alive to run it.
 * The cases that leak are exactly the ones where it is not — the process is reclaimed
 * between a conversion finishing and the user saving it, a worker fails before its output
 * ever becomes a `Converted` state, or a `reset()`'s delete is cancelled along with the
 * Activity. Process start is the one moment those leftovers are reliably observable.
 */
open class LibreMediaConverterApp : Application() {

    /**
     * Deliberately process-lifetime and never cancelled: the work it carries is a single
     * short task that should outlive nothing in particular and be interrupted by nothing.
     * A `SupervisorJob` so a failure here could never take a sibling down with it.
     *
     * **`protected open` for #159.** Robolectric builds an `Application` for every test that asks
     * for one, so on the JVM this is not one background sweep but one *per test* — all of them on
     * `Dispatchers.IO`, all touching the same `cacheDir`, none of them joined by anything. That is
     * a race against any test asserting about a file under `conversions/`, and it grew with the
     * suite: wave 4 added ten Robolectric classes and took it from CI-only to roughly one local run
     * in six. The JVM suite substitutes a scope that runs the sweep inline — see
     * `app/src/test/resources/robolectric.properties` and `TestLibreMediaConverterApp`.
     *
     * A constructor parameter would be the ordinary way to inject this and is not available: the
     * framework builds this class, so the seam has to be a member.
     */
    protected open val sweepScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The sweep [onCreate] last started, so a caller that needs it finished can wait for it.
     *
     * Nothing in production reads this — process start does not wait for its own housekeeping. It
     * exists because the alternative for a test is a timed poll, and a poll cannot tell "the sweep
     * has not run yet" from "the sweep ran and did nothing".
     */
    @Volatile
    var startupSweep: Job? = null
        private set

    override fun onCreate() {
        super.onCreate()

        // Off the main thread: this lists a directory and stats each entry, and it runs on
        // the path that decides how long the launcher icon stays unresponsive.
        //
        // Why this cannot race a live job -- and note the argument is NOT about ordering.
        // WorkManager initialises through androidx.startup's InitializationProvider, which
        // is a ContentProvider, so it is already up before onCreate() is called and can be
        // resuming a worker on its own executor while this runs. Workers run in this same
        // process, so "nothing has started yet" would simply be false.
        //
        // The grace period is what makes it safe. StagingSweep only collects a file nothing
        // has written to for a full day:
        //
        //  - Conversion and join outputs are written continuously, so a running job keeps
        //    its own mtime fresh and never looks abandoned.
        //  - A join's list file is the one written once and then only read, so it is the
        //    one that has to be reasoned about rather than observed. A WorkManager attempt
        //    is capped by the six-hour-per-day foreground-service budget and a retry starts
        //    doWork() again from the top, rewriting the list file -- so no single attempt
        //    can hold a file untouched for twenty-four hours.
        //  - A worker resuming right now writes its files at attempt start, which makes
        //    them zero seconds old, not a day.
        //
        // sweepStaging() also re-reads each timestamp immediately before deleting, which
        // closes the window between listing the directory and acting on the listing.
        startupSweep = sweepScope.launch { OutputPublisher(this@LibreMediaConverterApp).sweepStaging() }
    }
}

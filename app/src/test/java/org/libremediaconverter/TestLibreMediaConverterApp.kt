package org.libremediaconverter

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The [LibreMediaConverterApp] the JVM suite runs, differing from it in exactly one thing: the
 * startup sweep runs inline on the thread that builds the Application instead of on
 * `Dispatchers.Unconfined`.
 *
 * **This is #159.** Robolectric builds an `Application` per test class that asks for one, and each
 * one launches a sweep over the shared `<cacheDir>/conversions/`. Nothing joins them, so a test
 * asserting about a staged file is racing however many sweeps the classes before it left in
 * flight — `OutputPublisherStagingTest` being the one that lost, at roughly one local run in six
 * once wave 4 added ten more Robolectric classes. Making the sweep finish before `onCreate()`
 * returns removes the race for every test at once rather than asking each to opt in; 27 of the
 * suite's 58 Robolectric classes touch that directory, so opting in was not a real option.
 *
 * `Dispatchers.Unconfined` is what makes it inline: `sweepStaging()` is a plain function, so an
 * `Unconfined` `launch` runs it to completion before returning. The `SupervisorJob` is kept so this
 * differs from production in the dispatcher alone — a sweep that throws is logged and swallowed
 * here exactly as it is there, rather than taking Application construction down with it and failing
 * every test in the class for an unrelated reason.
 */
class TestLibreMediaConverterApp : LibreMediaConverterApp() {
    override val sweepScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
}

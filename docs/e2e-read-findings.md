# E2E-read findings

**Status:** seven findings; E4 fixed, E7 extended and its ticket closed, the rest standing — **plus one confirmed vacuous test, which is a
ticket rather than an entry here** (see [Not covered here](#not-covered-here)). `E1`–`E6` came from
the 2026-09-05 read of the instrumented suite. Every entry here is a *test-suite* observation —
something a new test would not fix, because the test already exists and the problem is what it
claims rather than what it runs.
**Scope:** what reading all 60 instrumented tests turned up that writing a 61st would not fix.
**Last verified:** `main` at `4b02294`, 2026-09-05. **60 `@Test` methods in 12 classes**, three
carrying `@FailsOnEmulatorApi37`, gating API 37 leg 57.

## Why this document exists, and why it is separate from the other two

`docs/coverage-read-findings.md` (`F1`–`F10`) came from reading a **JaCoCo report**, and JaCoCo
measures `testDebugUnitTest` only. So four waves of coverage work have been shaped by a number that
**cannot see `app/src/androidTest` at all**. The instrumented suite has never had the equivalent
read: nothing has asked what those 60 tests actually pin, only that they are green.

That is the gap this read is in. It is a **triage, not a test push** — the same shape as wave 4's
read, which "moved no number at all, and that is its result".

`docs/defect-audit.md` (`D1`–`D16`) is the record of things *wrong at runtime*. Nothing here is
wrong at runtime. These are tests whose names, KDoc or reputation overstate what they execute.

Entry ids are `E1`–`E6` so they cannot be confused with `F1`–`F10` or `D1`–`D16`.

## How to read the confidence labels

Same vocabulary as the other two documents, deliberately:

- **Confirmed by inspection** — the control flow is fully readable and the finding follows from it.
- **Confirmed by measurement** — observed in a CI artifact, with the run id recorded.
- **No action** — recorded because it looks like a finding and is not.

## The method, and the one filter that found everything

A coverage number is useless here by construction, so the read used a different question, applied
to every one of the 60 tests:

> **If the behaviour this test is named for stopped working, would it go red?**

Three answers, and only the third is a gap:

- **yes** — the test bites. Most of the suite.
- **no, and that is deliberate and written down** — `RealMediaBenchmark` asserts nothing on purpose
  (E2); `transcodesH264ToH265AndReportsProgress` declines to assert progress for a stated reason
  (E3). These are entries here, not tickets.
- **no, and nothing says so** — the gap. One test, and it is the most important one in the suite.

**The reusable part is the second filter**, because "does it assert something?" would have cleared
the vacuous test — it asserts two things. What it does not do is *reach the code it names*:

> **Does the test's own premise hold on the machine that runs it?**

`HardwareFallbackTest` asserts `SUCCEEDED` and a non-empty output, and both are true of a
conversion that never went near the path it exists to prove (**#223**). See
[Not covered here](#not-covered-here); it is filed rather than recorded here because a test fixes it.

---

## E1 — `RemuxTest`'s class KDoc argues for engine assertions three of its tests do not make, and they are right not to

**Severity: low · Confirmed by inspection · the KDoc is what is wrong, not the tests**

```
app/src/androidTest/java/org/libremediaconverter/convert/RemuxTest.kt:31-42
```

The class KDoc is headed **"Why these assert the engine, not just the file"** and makes a specific
argument:

> A remux routed to FFmpeg produces a perfectly correct file — `-c copy` moves the same samples
> into the same container. So an output-only assertion passes whether the hardware transmux path
> ran or never executed at all […] which makes "silently always FFmpeg" the most likely way for
> this feature to regress.

Five of its seven tests run a conversion. **Three assert no engine at all:**

| test | output container | asserts engine? |
|---|---|---|
| `mkvToMp4RemuxesOnHardware` | MP4 | **yes** — `MEDIA3` |
| `mp4ToMkvRemuxesOnFFmpeg` | MKV | **yes** — `FFMPEG` |
| `webmToMkvKeepsVp9WithoutReencoding` | MKV | no |
| `audioOnlySourceRemuxesIntoMka` | MKV (`.mka`) | no |
| `mp4ToMpegTsAndAviProduceTheirOwnContainers` | MPEG-TS, then AVI | **TS only**; the AVI half does not |

### Why this is not a gap

`ConversionRouter.MEDIA3_CONTAINERS = setOf(Container.MP4)` (`ConversionRouter.kt:37`), and every
one of the three produces MKV or AVI. **They can only ever be FFmpeg**, so the regression the KDoc
names — "silently always FFmpeg" — is not a thing that can happen to them. The two tests where the
hardware path is genuinely at risk are exactly the two that assert it.

An engine assertion on the other three would be near-tautological given today's router. It would
catch one thing: somebody adding MKV or AVI to `MEDIA3_CONTAINERS` without a muxer to match — which
is what `Media3MuxersTest` is for, on the JVM, where it does not need a device.

### Why it is recorded rather than dropped

**This was the strongest-looking candidate of the whole read and it dissolved on tracing**, which
is the same shape as `F5` in the coverage document (filed as a test gap, and only stopped being one
when someone went looking for its callers). Recorded so the next read does not re-file it.

**The fix is one line of KDoc**, not three tests: the class asserts the engine *where the engine is
in doubt*, which is a better rule than the one it currently states.

---

## E2 — three of the 60 instrumented tests assert nothing, and two of them never run

**Severity: n/a · No action — deliberate, documented, and load-bearing as documentation**

```
app/src/androidTest/java/org/libremediaconverter/bench/RealMediaBenchmark.kt:25-53
```

`reportDeviceEncoderCapabilities` logs and asserts nothing. `hardwareVersusSoftwareOnRealVideo` and
`av1InputRoutesAccordingToDeviceDecodeSupport` are `assumeTrue`-guarded on media that is **not
committed** and must be staged by hand into the app's internal `filesDir`, so they skip in every
automated run — they are the "2 skipped" every green leg reports, and `docs/local-emulator.md:305`
says so.

The class KDoc is unambiguous: *"This is a benchmark, not part of the automated suite […] Not a
correctness test — the assertions are deliberately loose."*

**No action.** Recorded for one reason: **the suite's headline number is 60, and three of those 60
are not tests.** Any future statement of the form "60 instrumented tests cover X" is off by three,
and two of the three have never executed on CI at all.

**It is the opposite of E-nothing, though** — `reportDeviceEncoderCapabilities` runs on every leg
and logs `BENCH can-encode:`, and **that log line is what confirmed the vacuous test this read
found** (**#223**). An assertion-free test that prints the machine's capabilities turned out to be
the only oracle in the suite. See [Not covered here](#not-covered-here).

---

## E3 — `transcodesH264ToH265AndReportsProgress` does not assert that progress was reported

**Severity: low · No action on the test; the name is the inaccurate part**

```
app/src/androidTest/java/org/libremediaconverter/convert/Media3EngineTest.kt:73, :90-93
```

```kotlin
// Deliberately NOT asserting that progress fired. Polling is on a 250 ms tick,
// and a 3 s 320x240 clip can finish inside one tick on fast hardware, which
// would make the assertion fail intermittently for no real defect.
seen.forEach { assertTrue("progress out of range: $it", it in 0..100) }
```

`seen` is empty-safe: `forEach` on an empty list asserts nothing, so replacing `onProgress` with a
no-op reddens nothing here. The reasoning is sound and the alternative really is a flaky test.

**No action on the body.** The name says `AndReportsProgress` and the body says it does not check
that, which is the `probeForConcat` shape from `CLAUDE.md` — *a passing test with a wrong
explanation is its own failure mode* — in its mildest form, since here the KDoc immediately corrects
the name.

**Contrast the FFmpeg side, which is a real gap and is filed as #229**: `FFmpegEngine`'s percentage
arithmetic is executed by every FFmpeg test and observed by none, because every call site omits
`onProgress` entirely. Media3's is unasserted; FFmpeg's is unobserved. Only the second is a ticket.

---

## E4 — the marker's KDoc says removing it grows the gating leg by two; three tests carry it

**Severity: low · Confirmed by inspection · one line**

```
app/src/androidTest/java/org/libremediaconverter/FailsOnEmulatorApi37.kt:20
```

> Delete the annotation from the tests, and the advisory job goes empty and the gating one grows by
> **two**.

Three tests carry it — `Media3EngineTest:72`, `Media3EngineTest:135`, `SafPickerRoundTripTest:320` —
and `FAILS_ON_EMULATOR_API37_BASELINE = 3` eleven lines further down the same file, where the count
is machine-checked by `.github/scripts/e2e-report-shape.sh`.

The third marker was added when the SAF rotation test was excluded; the sentence was not updated
with it. **Everything that is checked is consistent at three**; only the prose says two, which is
exactly why it drifted — and a good argument for the baseline const being a const.

---

## E5 — `coverage-read-findings.md`'s F7 calls covered code uncovered

**Severity: low · Confirmed by inspection · half of F7 is stale**

F7 says `probeWithExtractor`'s catch (`MediaProbe.kt:180-182`) is unreachable on Robolectric and
"stays device-only", measured across four URI shapes. **The unreachability claim is correct and
stands.** The implication readers take from it — that nothing exercises it — does not:

```
app/src/androidTest/java/org/libremediaconverter/convert/RemuxTest.kt:111
```

`probeDistinguishesAudioFromImagesFromRubbish` feeds it a file of random bytes and asserts
`InputKind.UNPARSEABLE`, on a device, on every gating leg.

**"Device-only" holds; "uncovered" does not** — and the difference matters, because F7 is one of the
six entries that document calls "no action", on the grounds that a test would not help. A test
already exists. The entry should say so.

**This is the failure mode the split between the two documents was meant to prevent**, and it caught
this repo out: a JaCoCo-derived document cannot see `androidTest`, so it will keep re-deriving
"uncovered" for anything the instrumented suite covers. That is a structural reason for this
document to exist, not a one-off correction.

---

## E6 — the suite's one device-capability assertion derives its expectation from the call it is testing

**Severity: low · Confirmed by inspection · no independent oracle exists**

```
app/src/androidTest/java/org/libremediaconverter/work/ConversionWorkerTest.kt:151-152
```

```kotlin
val hasHardwareHevc = AndroidDeviceCodecs.get().canEncode(VideoCodec.H265)
```

and then the expectation is `if (hasHardwareHevc) MEDIA3 else FFMPEG`. The test asks
`AndroidDeviceCodecs` what to expect and then checks that the router agreed with
`AndroidDeviceCodecs`. **If the whole enumeration returned empty, this would still pass** — and
empty is precisely what the `runCatching` fallback returns (the reason `#194` was worth cutting;
it logs "assuming permissive" while making `canEncode` answer *no* for everything).

Its KDoc defends the choice, and the defence is good:

> Asserting MEDIA3 unconditionally tests the test machine, not the router.

That is true, and there is no third source of truth on a device: `MediaCodecList` is what
`AndroidDeviceCodecs` reads, so any oracle built from it is the same oracle.

**No action, but read it with #223.** It is the same missing oracle that makes the
vacuous-test fix a judgement call rather than a one-liner — you cannot assert "this device has
hardware HEVC" from inside the suite without asking the class under test. The honest options are a
visible skip or a red test, and that decision is the ticket's.

---

## E7 — a real `DocumentsProvider` cannot be reached without the picker, so there is no cheap SAF test

**Severity: n/a · Confirmed by measurement · this is a platform rule, not a gap**

Added 2026-09-06, from doing #225 and #226 rather than from reading.

`OutputPublisher.publish`'s destination side is asserted only against Robolectric fakes —
`FakeSafProvider`, registered with `asDocumentsProvider = true`, which is the flag that *makes*
`DocumentsContract.isDocumentUri` answer true. #226 split that into a cheap headless half (drive a
real `DocumentsProvider` directly) and an expensive picker-driven half.

**The cheap half does not exist.** Three approaches, all measured on an API 34 emulator:

| approach | result |
|---|---|
| a second `DOCUMENTS_PROVIDER` declared **without** `MANAGE_DOCUMENTS` | refused at install: `SecurityException: Provider must be protected by MANAGE_DOCUMENTS` |
| create the document as the **test APK**, which owns the provider | denied — instrumentation runs *in the target app's process*, so it carries the app's uid whatever `Context` is asked |
| `uiAutomation.adoptShellPermissionIdentity(MANAGE_DOCUMENTS)` | denied identically |

The denial names the only way in:

> `Permission Denial: opening provider …FixtureDocumentsProvider from
> ProcessRecord{… org.libremediaconverter/u0a192} requires that you obtain access using
> ACTION_OPEN_DOCUMENT or related APIs`

And the intent filter is not optional: without it `isDocumentUri` returns false, which is exactly
the branch guarding `deletePartialOutput` — so a provider without the filter tests nothing the
ticket is about.

**So any test of `publish` against a real `DocumentsProvider` must drive DocumentsUI**, and pays
#190's flake tax. The work is one item at that cost, not two, and #226 was updated to say so.

**Updated 2026-09-06, doing it: there is a second constraint underneath, and it has the same
cause.** The obvious way to avoid driving the app was a host Activity in `androidTest` owning its
own `CreateDocument` launcher. It cannot be started at all:

```
java.lang.RuntimeException: Intent in process org.libremediaconverter resolved to different
  process org.libremediaconverter.test
    at android.app.Instrumentation.startActivitySync
```

Instrumentation runs in the target app's process, so a component declared in the instrumentation
APK is in the wrong one — the same fact that sinks approach 2 above, arriving from the other side.
**The app's own Save button is the only launcher available to drive**, which is also the more
faithful thing to drive. `SafPickerRoundTripTest.aSaveWritesToTheDocumentTheSystemPickerCreated` is
what came of it.

**And the premise turned out to be true**, which is the answer #226 was filed for: on API 34,
stock DocumentsUI hands back a document URI reporting a size of exactly zero. `deletePartialOutput`
can fire, and D4's fix is live rather than inert. A "no defect found" — and not one that could have
been reached by reading.

### What this does *not* block, which is the useful half

`FFmpegKitConfig.getSafParameterForRead` — the bridge on every real conversion and join — needs no
documents provider. It opens a descriptor through the resolver, so **any readable `content://` URI
exercises it**, and an ordinary `ContentProvider` may be exported without a permission. That is what
`FixtureContentProvider` is, and it made #225 headless.

**That distinction was worth the trouble**: the first test ever to hand the join path a real
`content://` input found #238, a defect that broke joining for every user who picks matched files.
The expensive gate protects the *destination* side; the *input* side never needed it.

## Summary

| ID | Finding | Severity | Evidence | Action |
|---|---|---|---|---|
| E1 | `RemuxTest`'s KDoc claims engine assertions three of its tests correctly omit | low | confirmed by inspection; traced through `MEDIA3_CONTAINERS` | **one line of KDoc** — the tests are right |
| E2 | Three of the 60 instrumented tests assert nothing; two never run | n/a | confirmed by inspection; `docs/local-emulator.md:305` | **no action** — deliberate; but 60 ≠ 60 |
| E3 | `…AndReportsProgress` does not assert progress fired | low | confirmed by inspection; reason inline | **no action** — the name overstates, the KDoc corrects it |
| E4 | The API 37 marker's KDoc says "two"; three tests carry it | low | confirmed by inspection; baseline const says 3 | **fixed** in #243 — it names the constant now |
| E5 | `coverage-read-findings.md` F7's "uncovered" half is stale | low | confirmed by inspection; `RemuxTest.kt:111` drives it | **amend F7** — "device-only" stands, "uncovered" does not |
| E6 | The device-capability assertion asks the class under test what to expect | low | confirmed by inspection; no third oracle exists on a device | **no action** — read with **#223** |
| E7 | A real `DocumentsProvider` is unreachable without the picker, so #226 has no cheap half | n/a | measured three ways on API 34; each denial names `ACTION_OPEN_DOCUMENT` | **no action** — it re-scoped #226 |

**Six of the seven are prose, not code**, and that is the shape of this read. The instrumented suite
is in good condition: 57 of its 60 tests bite, the fixtures are committed with their generation
recipes, and the one class that asserts nothing says so in its first line. What this read found is
that **the suite's self-description has drifted from the suite** in five small places and one large
one.

**The large one is not in this table**, because a test fixes it: **#223**.

## Not covered here

**The vacuous test.** `HardwareFallbackTest.aFileMedia3CannotDecodeStillConvertsViaFfmpeg` passes on
every CI leg without ever entering the fallback it exists to prove. It is **#223**, not an entry
here, because a test fixes it — and it is the reason this read happened rather than an aside from it.

Measured, not inferred, on run **`34004304566`** (all legs green), from each leg's own
`e2e-diagnostics-api*` logcat:

```
I/AndroidDeviceCodecs: Hardware video encoders: []
I/RealMediaBenchmark: BENCH can-encode: COPY=true, H264=false, H265=false, VP9=false, VP8=false, AV1=false
I/ConversionWorker: Routing sample_h264_444.mp4 -> OutputSpec(container=MP4, videoCodec=H265,
                    audioCodec=AAC) via FFMPEG (NO_HARDWARE_ENCODER)
```

Identical on **API 33, 34, 35 and 37**. (API 36's logcat artifact on that run is truncated to 838 KB
and carries no test output at all, so it is unread rather than different.) The job is routed
**straight to FFmpeg before Media3 is attempted**, the `catch` in `runMedia3OrFallBack` is never
entered, and the test's two assertions — `SUCCEEDED`, output non-empty — are true anyway. It ran in
448 ms.

**The repository already knew.** `ForcedFailureTest.hardwareFailureFallsBackToSoftware`, in the same
package, pins `ConversionDependencies.deviceCodecs = { DeviceCodecs.PERMISSIVE }` and says why:

> most emulators expose no hardware video encoder at all -- so the router would legitimately send
> the job straight to FFmpeg and the hardware path would never be attempted. Without this the test
> passes on a Pixel and fails on every emulator, which says nothing about the code under test.

`ConversionWorkerTest.routesAFastMp4JobByDeviceCapability` records the same fact a third time. The
knowledge is in two sibling files; `HardwareFallbackTest` is the one that walked into it — and
because its assertions are about the *output* rather than the *path*, it passes where
`ForcedFailureTest` would have failed. **That asymmetry is why nobody noticed.**

**State it precisely.** The fallback *wiring* is covered on every leg by `ForcedFailureTest`, with
fakes. What has never run on any emulator is a fallback triggered by a **real** mid-export codec
failure — which is the case `HardwareFallbackTest` exists for, and the only reason
`sample_h264_444.mp4` is committed at all. That fixture, generated with x264 because Fedora's
ffmpeg ships openh264 and cannot produce High 4:4:4, does nothing on any CI leg today.

The fix is not one assertion. `KEY_ENGINE_USED` is `FFMPEG` **whether the fallback fired or the
router went straight there** — asserting it changes nothing. The vacuity guard is two facts
together: the router chose `MEDIA3` for this request on this device, *and* the worker reported
`FFMPEG`. Whether to reach that with `assumeTrue` (a visible skip on emulators, and the "2 skipped"
becomes 3) or with an assertion (red on emulators, announcing it cannot test what it claims) is a
decision, not a detail — see **E6** for why no third option exists — and **#223** leaves it open.

**The other e2e gaps this read found are tickets too**, and are not repeated here:

| # | Gap |
|---|---|
| # | Gap | Outcome |
|---|---|---|
| **#223** | `HardwareFallbackTest` never attempts the hardware path on any emulator leg | closed — it skips instead of passing vacuously |
| **#224** | Cancelling a *running* native session, in any of the three engines | closed — all three engines |
| **#225** | No `content://` input has reached a *successful* conversion — the ffkitsaf bridge | closed, and it found **#238** |
| **#226** | `OutputPublisher.publish` against a real `DocumentsProvider` | closed — the *premise* holds; see E7. The delete **arm** is still unrun: **#250** |
| **#227** | The notification's Cancel action has never been fired | closed |
| **#228** | `encodesFlacLosslessAudio` and `encodesOpus` pass on any non-empty file | closed |
| **#229** | FFmpeg's progress percentage is computed everywhere and asserted nowhere | closed |
| **#230** | *(spike)* whether a running conversion's process can be killed | closed — it cannot; the runner shares the app's process |

**The read's own result, once the tickets were worked: one production defect.** #238 — joining files
picked through the system picker failed outright on the stream-copy path, because the concat demuxer
whitelists protocols separately from `-safe 0` and `ffkitsaf` was not on the list. Only `STREAM_COPY`
feeds the demuxer a list file, and every existing join test passed `Uri.fromFile`, so the one broken
combination was the only one a user could reach.

That is the argument for this kind of read in one line: the gap was not a missed line or an
unasserted value, it was **a combination of two covered things that no test put together**.

**Nothing here was filed as a coverage delta.** Each names the mutation that has to go red, which is
the acceptance criterion wave 4 established and which caught two vacuous tests in that wave before
they shipped. #223 is the one that shows why the criterion matters: it has two passing assertions and
still tests nothing.

## The 2026-09-06 re-check

Run after the last ticket landed, to ask whether the suite's self-description had drifted again. It
had, and **every drifted line came from #226 — the last PR of this read's own wave.**

The suite is 70 tests in 14 classes, 6 carrying `@FailsOnEmulatorApi37`, gating leg 64; the
committed baseline says 6 and the advisory job agrees (`baseline: matches`). Every gating leg is
green on `main`.

- **The counts had gone stale in four places** — `CLAUDE.md` (three sites),
  `FailsOnEmulatorApi37.kt`'s KDoc, and two comments in `status_check.yml` — all still saying five
  carriers of 69. **The gating figure is what hid it**: 69 − 5 and 70 − 6 are both 64, so the one
  number a reader would check against a run had not moved. CLAUDE.md's own instruction to derive
  these rather than remember them is what caught it.
- **Two KDoc claims in `SafPickerRoundTripTest` described a draft rather than the code.** The save
  test says MP3 was chosen so the setup could not depend on device codecs; the code converts at the
  default `MP4_H265` / `FAST`, which routes by `canEncode(H265)`. The *negation* of the stated
  reason was true. This is **E1 and E3's failure mode landing in a test written by the read that
  found it** — a passing test with a wrong explanation.
- **Neither picker test has ever reported on the advisory leg.** The marker's KDoc said the picker
  test *fails* there behind the rotation test; with six carriers the rotation test truncates the run
  first, and all four advisory runs at this baseline (`34041156680`, `34041593697`,
  `34042397320`, `34043502322`) report `expected: 6, received: 4, failed: 4` — the three Media3
  tests plus the rotation. The save test is therefore
  marked by **inheritance, not measurement**, which is now what both KDocs say.
- **One substantive gap, filed as #250.** `FixtureDocumentsProvider.deletedDocumentIds()` has no
  callers. #226 proved D4's *premise* — SAF hands back a document of exactly zero bytes — but drove
  only the success path, so `deletePartialOutput` against a real `DocumentsProvider` is still
  asserted nowhere. `openDestination` is `protected open` precisely to force the failure, so the
  test is cheap; it costs another marked picker test and a baseline of 7.

**The reusable part is the second bullet.** A read that fixes documentation drift can introduce it in
the same wave, and the tests it writes are no more self-describing than the ones it audited. The
check that found it is the one this document already recommends: **read the KDoc against the code,
not against the ticket.**

## E8 — the instrumented suite's coverage, measured for the first time

**Severity: n/a · Measured 2026-09-06 on API 34 · the number had never existed**

Four coverage waves were steered by a figure that cannot see `app/src/androidTest`. Nothing had
ever produced the other half, because `enableAndroidTestCoverage` was unset, so a connected run
emitted no `.ec` at all and `jacocoTestReport`'s execution data names only `testDebugUnitTest`.

Measured by setting that flag temporarily, running `run-e2e.sh 34` (70/70, 0 failed, 3m21s — the
instrumentation destabilised nothing), and reporting the resulting `.ec` against the **same** class
directories and exclusions the committed task uses:

| suite | line | branch |
|---|---|---|
| JVM `testDebugUnitTest` | 2236/2374 — **94.2%** | 1171/1338 — **87.5%** |
| Instrumented, 70 tests | 1711/2374 — **72.1%** | 669/1354 — **49.4%** |
| **Union** | 2342/2374 — **98.7%** | 1212/1354 — **89.5%** |

The JVM row reproduced the committed figure exactly, which is the control: both exec sets match the
current class files, so the union is trustworthy.

**Two caveats before anyone quotes these.** Branch denominators differ by 16 — 1338 against 1354 —
entirely inside `MediaProbe`, an artefact of offline versus on-the-fly instrumentation; line
denominators are identical at 2374, so only the line figures compare exactly. And **72.1% is not a
grade for the instrumented suite.** Seventy end-to-end tests reach code broadly and choose arms
rarely; a branch figure of 49.4% is what that shape looks like. This whole document exists because
the gaps that mattered — #223's vacuous assertions, #238's two covered things nobody combined —
are invisible to any percentage.

### What the device suite is for, in numbers

It closes **106 lines** the JVM suite misses, and they are precisely the ones wave 4 wrote off:

| file | JVM missed | union missed |
|---|---|---|
| `FFmpegEngine.kt` | 32 | **0** |
| `Media3Engine.kt` | 24 | **0** |
| `ConcatEngine.kt` | 15 | **0** |
| `MediaProbe.kt` | 13 | **3** |
| `MainActivity.kt` | 10 | **1** |
| `AndroidDeviceCodecs.kt` | 8 | **0** |
| `Transcoders.kt` | 10 | 3 |

CLAUDE.md's wave-4 read called 81 lines "native or device edges" — `FFmpegEngine` 33,
`Media3Engine` 24, `ConcatEngine` 14, `MainActivity.onCreate` 10. The first four rows above total
**81**, and the union leaves **1**. That **confirms** the read's own hypothesis rather than
overturning it: it always said those zeroes were "the `testDebugUnitTest`-only measurement
boundary". Nobody had measured past the boundary. `AndroidDeviceCodecs` is the pointed one — #194
was filed to cut a seam because `probe()` could not be reached, and on a device it is fully covered.

### The 32 lines neither suite reaches, classified

Every one was read. **None of them is an e2e test gap**, which is the result:

| lines | where | classification |
|---|---|---|
| 9 | `Transcoders` ×3, `ConversionViewModel`, `ConverterScreen`, `JoinViewModel`, `JoinScreen`, `MainActivity`, `Reattachment` | **compiler-generated** — default-arg `$default` bridges, coroutine completion, the synthetic `NoWhenBranchMatchedException` arm of a `when` over `Destination` |
| 10 | `ConversionWorker:342-346`, `ConcatWorker:132-136` | `getForegroundInfo()` — WorkManager's **expedited-work** hook, and nothing here enqueues expedited work. The live path is `setForeground(foregroundInfo(...))`, which is covered. **#252** |
| 3 | `ConversionNotifications:60-62` | **F5** — `areEnabled()` has no callers. Already on record |
| 3 | `CopyPlanner:28`, `OutputFormat:222-223` | public members with no callers. **#253**, with F5 |
| 3 | `MediaProbe:210-212` | `probeWithFFprobe`'s `catch` — **F7's sibling, and now measured**. See below |
| 2 | `FFmpegCommandBuilder:167-168` | `COPY`/`NONE -> error(...)` — F4-shaped, deliberately exempt |
| 1 | `FFmpegCommandBuilder:188` | the `VORBIS` encode arm. No `OutputFormat` produces it, but `ContainerCapabilities` lists it for WEBM and OGG. **#254** |
| 1 | `ConversionWorker:231` | `?: error("Could not open the input file.")`. `UnopenableUriTest` fails the job *downstream* of it, so the elvis is unprovoked — F4-shaped, same as the two above |

**`MediaProbe:210-212` is the one that gained a measurement.** F7 ruled `probeWithExtractor`'s catch
unreachable because Robolectric's `MediaExtractor` never throws. That reasoning does not transfer:
`probeWithFFprobe` calls `readMediaInformation` in native ffmpeg-kit, which the JVM never loads.
But `MediaProbe.probe` calls **both** probes on one line, and
`RemuxTest.probeDistinguishesAudioFromImagesFromRubbish` drives it on a device with 4096 bytes of
garbage — so the ffprobe path *has* been given malformed input on real hardware and **did not
throw**. Same conclusion as F7, reached by a different mechanism, and now on record rather than
assumed.

**The reusable part**: a union report is what separates "no test calls this" from "only a device
calls it", and neither report alone can. Six of the eight rows above were indistinguishable from
real gaps in the JVM-only number.

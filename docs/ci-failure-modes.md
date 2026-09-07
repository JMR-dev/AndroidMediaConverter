# When a gating E2E leg goes red and the diff cannot explain it

**Status:** a census of every gating E2E leg-attempt in the repo's history, classified by mode,
with a disposition for each. **1489 gating leg-attempts, 129 failures, 8.7%** — 2026-08-20 to
2026-09-07. This is the standing answer to "my docs-only PR turned an emulator leg red, what is
it?", and it is what #102 asked for before being closed as an umbrella.
**Last verified:** 2026-09-07, against `main` at `ef9d35e`.

This document is about **the emulator failing underneath the suite**. It is not a defect record
(`docs/defect-audit.md`), not a coverage read (`docs/coverage-read-findings.md`), and not a
test-suite read (`docs/e2e-read-findings.md`). Nothing here is a bug in the app.

## Read this first: three counting rules, each learned by getting it wrong

**Count per leg-attempt, never per run.** Measured here rather than asserted: the 129 failing
leg-attempts sit in **83 distinct runs, and 45 of those 83 ended green** once someone re-ran them.
So a census that counts failed *runs* finds 38 events where there were 129 — it does not
under-report evenly, it deletes exactly the failures somebody already decided were noise, which are
the ones this document is about. Every number here is per leg-attempt, with `cancelled` legs
excluded: those are `concurrency: cancel-in-progress` cancellations rather than runs, and there are
135 of them.

**Every mode has its own denominator, and it is not 1489.** Derive it from where and when the
*test* ran, not from the leg count, and two things move it. The API 37 row filters out every test
carrying `@FailsOnEmulatorApi37` with `notAnnotation` — **all four of `SafPickerRoundTripTest` and
three of `Media3EngineTest`, seven today** — which is every mode in the table below except 3 and 5.
And **that set has grown across this window**: the picker test and the two saves only joined it on
2026-09-06, which is why `SafPickerRoundTripTest` has 25 API 37 failures on record — 14 of them
since 2026-08-27 — that could not happen now. The saves did
not exist at all before 2026-09-06T15:12. A rate quoted over "all gating leg-attempts" is wrong for
every one of them, and is how "8% of legs" gets said about a thing that happens on one row.

**Anchor the mode to the test name beside the `FAILED` marker, then to the message under it.**
The name alone is not enough: `transcodesH264ToH265AndReportsProgress` has failed for three
different reasons, one of which was the whole suite going down around it.

## The modes

| # | mode | signature | where | disposition |
|---|---|---|---|---|
| 1 | SAF picker will not close | `the system picker would not close: after 4 back presses ...` | 37 only, since #96 | **#108** — collateral of the gralloc abort |
| 1b | picker never showed, from the rotation test | `never showed BySelector [PKG=...], in 3 separate pickers` | 33, 34 — 3 times | #268/#269; no gating attempt on `main` since |
| 2 | wedge | gradle never returns; leg killed at `WEDGE_TIMEOUT`; `wedged: yes` in the shape row | 33/34 only | **#122**, addressed by #219 — see below |
| 3 | emulator never came up | `adb ... failed with exit code 224`, before any test | 37 only, 3 times | infra, before the suite; nothing to attribute |
| 4 | Media3 export watchdog | `ExportException: Muxer error` / `no output sample written in the last 25000 milliseconds` | 34, 36 | **environmental, measured** — see below |
| 5 | `system_server` gone mid-suite | `Can't find service: package`, `am get-current-user` fails, `INSTRUMENTATION_ABORTED` | 37 only | **#108** — `hasReadColorBufferDma` |
| 6 | app Activity destroyed under the SAF save tests | `NullPointerException: Cannot run onActivity since Activity has been destroyed already` | 35, once | **fixed** — see below |

**#96 held, and mode 1 is worth stating as a number rather than a memory.**
`pickingAFileThroughTheSystemPickerFillsInTheFileCard` — the test #93 and #96 were about — has
failed **zero times on API 33-36 in the 881 gating leg-attempts since #96 merged**. Every remaining
failure of that class on those four rows is a *different* test: three of the rotation test (1b) and
seven of the two save tests (mode 6). The picker mode is an API 37 mode now.

Background noise that is **not** a mode on its own: `Failed to find ColorBuffer: N` and `bad color
buffer handle N` never name anything in this app and appear on green legs. Measured over 12 green
gating legs sampled from 2026-09-02 onwards, all reporting `failed: 0`: `bad color buffer handle`
in **6** of them, `Failed to find ColorBuffer` in **2**. Neither is evidence of anything on its own.

## Mode 4 — the Media3 export watchdog is the emulator's codec HAL segfaulting

**This is the mode #102 was filed for, and it is not starvation.** The per-test logcat in
`e2e-report-api34` of run `34000816016` attempt 1, 62 ms after the test starts:

```
00:20:39.814 D MediaCodec: MediaCodec::reclaim(...) c2.goldfish.h264.decoder
00:20:39.822 F DEBUG : Cmdline: /vendor/bin/hw/android.hardware.media.c2@1.0-service-goldfish
00:20:39.822 F DEBUG : signal 0 (SIGSEGV), code 1 (SEGV_MAPERR)
00:20:39.822 F DEBUG : Cause: null pointer dereference
  #00 C2Block2D::handle() const+4                          libcodec2_vndk.so
  #01 getClientUsage(std::shared_ptr<C2BlockPool> const&)  libcodec2_goldfish_common.so
  #02 android::C2GoldfishAvcDec::process(...)              libcodec2_goldfish_avcdec.so
00:20:39.839 E CCodec  : Codec2 component "c2.goldfish.h264.decoder" died.
00:20:39.846 E MediaCodec: Codec reported err 0xffffffe0/DEAD_OBJECT
```

The decoder HAL process dies and respawns. Media3 is left with a dead codec, writes no output
sample, and its own 25-second export watchdog aborts the export — which is the `Muxer error` the
job log shows. **The crashing code is `/vendor/lib64/*` inside the system image**, so this is
environmental in the same sense `@FailsOnEmulatorApi37` is, and now with the same kind of evidence.

**Six for six.** Every leg-attempt that has failed this way carries the crash in the same job's
`--- native crashes (tail 60) ---` dump. **Grep `c2@1.0-service-goldfish` and not the friendlier
line**: `Codec2 component "c2.goldfish.h264.decoder" died` is a `CCodec` message in the main
buffer and is in **none** of the six job logs, because that dump is `adb logcat -d -b crash` and
what reaches it is the tombstone, whose `Cmdline:` names the HAL. The six are
`32855014836` a1 (36), `32857067112` a1 (34),
`32919928048` a1 (36), `33261618358` a1 (34), `33588264439` a1 (36), `34000816016` a1 (34).
**Six in 1210 API 33-36 leg-attempts — 0.5%**, split 3 on API 34 and 3 on API 36, none on 33 or 35.

**It is the same weakness the API 37 marker names.** `FailsOnEmulatorApi37`'s stated reason is that
Media3 transcodes "fail inside the emulator's own `c2.goldfish.h264.decoder`". That is this HAL.
One weakness, deterministic on the android-37 images and 0.5% below them.

**What is not settled:** *why* it dereferences null. `MediaCodec::reclaim` is logged 8 ms earlier,
and a reclaim is the resource manager taking a codec instance away — so "a reclaim races
`C2GoldfishAvcDec::process` and the block pool goes out under it" is the obvious hypothesis and is
**untested**. Recorded as a hypothesis, not as a cause.

**Two failures of that test are excluded and it matters that they are.** `32545625459` a1 (API 37)
had 37 tests fail together with the gralloc assertion present — that is mode 5, and this test was
collateral. `32669190757` a1 (API 35) predates #111's shape report and carries a bare `FAILED`
marker with no message at all; it is **unclassifiable, and is not classified**.

## Mode 6 — the back press that finished `MainActivity`

Traced on the API 35 gating leg of run `34161043035` **attempt 1**, whose head is #269's own
commit:

```
20:59:36.033  MainActivity RESUMED           <- the save picker has already returned
20:59:37.068  UiDevice: Pressing back button.
20:59:41.094  UiDevice: Retrieving node ... [RES='android:id/aerr_wait']
20:59:41.169  Input channel 'Application Not Responding: ...nexuslauncher' was disposed
20:59:41.713  UiDevice: Pressing back button.
20:59:41.754  TopTaskTracker: onTaskMovedToFront: ... NexusLauncherActivity
20:59:42.278  MainActivity DESTROYED
```

`dismissThePicker` guarded its back presses on `Activity.hasWindowFocus`. A system app-error dialog
is a fullscreen `system_server` window, so **it makes that false too** — the guard could not tell
"the picker is still up" from "a dialog is on top of an app that is already in front". The loop
dismissed the launcher's ANR dialog (#93's occluder, still ambient) and then pressed back on the
reading it had taken before doing so, into an app with nothing left to go back to.

Fixed by re-reading the focus after a dialog is actually dismissed, and only then —
`SafPickerRoundTripTest.dismissThePicker` carries the trace. That **removes** a press sent on a
stale reading rather than retrying one, and a picker genuinely in front still fails there.

**It cannot be demonstrated by re-running.** The launcher ANR is ambient and not reproducible on
demand, so a green sweep is not evidence for this fix; the trace is.

### The other six failures of those save tests are four different things

Filed as one mode, they are not one: seven occurrences, **five distinct messages** counting the
destroy above. Three of the six below are on heads that predate their own follow-up fix, and one is
on a head that **contains** the fix meant for it. This is the worked example for "split by message
before diagnosing". **The two rows still open are #270**; the `button1` finding below is **#271**.

| run / attempt | head | message | what it is |
|---|---|---|---|
| `34041593697` a1 (35) | `fa10d94` — the commit that **added** the test | `No compose hierarchies found`, thrown directly | pre-`b23ff0f` |
| `34056545386` a1 (35) | `cbbaf74` | `ComposeTimeoutException ... after 120000 ms` | the `CONVERSION_TIMEOUT_MS` case `19e3539` fixed. **Not a system-service failure at all** — API 35's software encode measured 134.8 s against a 120 s bound |
| `34057706195` a1 (34) | `19e3539` | `No compose hierarchies found` ×2 | **contains `b23ff0f`**, so that fix did not close this shape. Open |
| `34067653670` a1 (35), `34146936252` a1 (35) | `d45abe7`, `73482520` | `waited 300000ms for a node tagged action.saveFile`, **no** composition error | `awaitNode` appends the composition error only when `fetchSemanticsNodes` threw, so the composition was readable throughout. `34067653670`'s per-test logcat has `MainActivity` `RESUMED` for the whole 300 s and **no conversion running at all**. Open |
| `34146936252` a2 (35) | `73482520` | `waited 300000ms ...; last composition error: No compose hierarchies` | app `PAUSED` and never resumed; the back press at `17:38:32.479` follows `Waiting 5000ms for ... permissioncontroller`, the permission-dialog helper #269 replaced with `pm grant` |

### And the dismissal can click a dialog that is not a system dialog (#271)

In the same trace, at `20:59:35.689`, `aerr_wait` and `aerr_close` both missed and
`android:id/button1` — the framework's generic `AlertDialog` positive button, present on every
`AlertDialog` on the device — was found and clicked. `MainActivity` came back 339 ms later and
`PickActivity`'s window went away with it, so what was clicked was a button inside **DocumentsUI's
own create-document flow**. It did no harm on that run. Filed rather than fixed here, because
narrowing the selector is a decision about what `dismissASystemErrorDialog` may reach.

## Mode 2 — the wedge, and why this says "consistent with" rather than "fixed"

Nine occurrences, **all on API 33/34**, 9 in 497 leg-attempts before 2026-09-06T02:00Z — **1.8%**
— and **0 in the 106 since**. The last one, `34001741668` (2026-09-06T00:36), is
`thePickedInputSurvivesARealRotation` again, and `git merge-base --is-ancestor 32ab54d <head>` says
that head **does not contain** #219's fix, so no wedge has ever been recorded against the fix.

At the prior rate, P(0 in 106) ≈ 0.15. **That is suggestive and it is not evidence.** Re-count
before writing "fixed" here.

Its diagnostics say the framework is fine, which is what separates it from every other mode in this
document: `e2e-wedge-api34` of `34001741668` has `started:` the rotation test with no `finished:`,
and `input`, `window`, `activity` and `media.player` all `found`.

## Reading the evidence, when the leg is already gone

Four things that are not obvious and each cost a wrong answer:

- **A re-run destroys the log.** `gh run view --job <id> --log` resolves by *run* and serves the
  latest attempt, so after a re-run to green it hands back a green log for a red attempt. Use
  `gh api --allow-escape-sequences /repos/{owner}/{repo}/actions/jobs/{job_id}/logs`, with the job
  id from `/actions/runs/{run}/attempts/{n}/jobs`. Without `--allow-escape-sequences`, `gh` writes
  nothing and exits 0.
- **A re-run does *not* destroy the artifacts, but the convenient command hides them.**
  `/actions/runs/{run}/artifacts` returns every attempt's upload under the same name with different
  ids and `created_at`; `gh run download` takes the newest, which after a re-run-to-green is the
  green one. Match `created_at` to the attempt's window and fetch
  `/actions/artifacts/{id}/zip`.
- **The per-test logcat is the evidence, not the job log.** `e2e-report-apiNN` carries
  `outputs/androidTest-results/connected/debug/<device>/logcat-<class>-<method>.txt` — one file per
  test, scoped to that test's window — plus the JUnit XML with the untruncated stack. The job log
  truncates a stack to its first frame, which is why the `ActivityScenario` frames in mode 6 are
  invisible there.
- **Grep the fault, not the thread.** #102 once split one bug into two by grepping
  `TaskSnapshotPer` — a thread name from a ticket title — instead of `hasReadColorBufferDma`, the
  assertion. The assertion is the invariant; the thread is only which caller tripped it.

## What this does not cover

The advisory `E2E API 37 Media3 hardware transcode (advisory)` job is **red on every PR by design**
and is not a signal. `docs/api-37-emulator-crash.md` has API 37's own story;
`.github/scripts/e2e-report-shape.sh` explains the shape table every leg prints.

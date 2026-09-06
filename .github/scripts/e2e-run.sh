#!/usr/bin/env bash
#
# Runs the instrumented suite on an already-booted emulator, and makes a failure
# diagnosable without a re-run.
#
# Adapted from LibreMail's CI emulator instrumentation. Its lesson, learned there over
# several wedged merge queues, is that a red E2E leg with nothing but "exit 1" in the log
# costs more than the failure itself -- so every failure path here leaves evidence behind.
#
# WHY THIS IS A FILE rather than inline YAML: reactivecircus/android-emulator-runner splits
# its `script:` input on newlines and runs each line as its own `sh -c`. Shell functions,
# `if` blocks and traps cannot survive that, which is why the previous version had its whole
# failure handler crammed onto one unreadable line. One line calls this; this can breathe.
#
# Two failure shapes, deliberately handled differently:
#
#   FAILED  -- gradle returned non-zero. The reports say which test and why, so capture the
#              device and runner state around it.
#   WEDGED  -- gradle never returned and the wrapper timeout killed it. There is no report at
#              all, so the evidence has to be taken from the live device: what test was
#              running, and what every process was doing. SIGQUIT is the important part -- ART
#              dumps full thread stacks to logcat and /data/anr, which is how you tell a
#              deadlocked test from a stuck MediaCodec from an emulator that stopped answering.
#
# Every probe is guarded with `|| true`. A diagnostic must never be the thing that turns a run
# red -- notably, a grep that matches nothing exits 1.
set -uo pipefail

LABEL="${1:-unknown}"
APP_ID="org.libremediaconverter"
TEST_ID="org.libremediaconverter.test"

TMP="${RUNNER_TEMP:-/tmp}"
LOGCAT_LOG="$TMP/logcat-api${LABEL}.txt"
DIAG_LOG="$TMP/diagnostics-api${LABEL}.txt"
WEDGE_LOG="$TMP/wedge-diagnostics-api${LABEL}.txt"
# Gradle's own output, captured to a file as well as the step log, because the run-shape report
# below has to parse it. Uploaded with the diagnostics, so a report that reads wrong can be
# checked against what it read.
GRADLE_LOG="$TMP/gradle-api${LABEL}.txt"

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"

# ~5 min is a healthy leg (measured across API 33-36), and this wraps only the gradle client,
# a subset of that. 20 min is generous enough never to trip on a slow-but-working run, and far
# enough under the job's 60-min cap that a genuine wedge still leaves time to capture it.
WEDGE_TIMEOUT=1200

# ---------------------------------------------------------------------------
# API 37 only, and nothing else sets it, so this is inert everywhere it is not wanted --
# the same shape as E2E_EXTRA_GRADLE_ARGS below. The other four E2E legs run byte-identical
# commands with it unset.
#
# WHY IT RUNS HERE, BEFORE THE LOGCAT STREAM: it is a 45-second wait, and the stream below is
# meant to cover the suite rather than the wait. Everything this function counts comes from
# `adb logcat -d -b crash`, a fresh read each time and independent of the stream. (The original
# reason was stronger and no longer applies: `adb shell stop` would have ended the streamed
# `adb logcat` and nothing restarts it. There is no `stop` here any more -- see below.)
#
# WHAT IT IS FOR -- AND THE NAME IS NOW WRONG, WHICH IS WHY THIS PARAGRAPH IS LONG.
# The android-37.x images abort surfaceflinger from RegionSamplingThread inside their own gralloc
# mapper (docs/api-37-emulator-crash.md). surfaceflinger is a critical service, so init SIGKILLs
# zygote with it and the framework restarts under the run -- Gradle then reports
# `cmd: Can't find service: package` and `Starting 0 tests`. RegionSamplingThread exists only
# because SystemUI registers a nav-bar luma-sampling listener, so this was written to remove the
# package and with it the whole chain. Measured cadence of those kills on `-gpu host`: 20-90 s
# apart, median 60-70 s, three to five in a four-minute window.
#
# **THE DISABLE HALF OF THAT HAS NEVER WORKED, AND THE QUIET WINDOW IS WHAT THE LEG ACTUALLY
# GETS.** Measured 2026-09-05, two ways that agree:
#
#   - On CI, in the gating leg of run 34006456986: `pm disable-user` is accepted at 02:28:37.9 and
#     `com.android.systemui` really is in `pm list packages -d` at 02:29:33 -- and SystemUI is
#     started anyway at 02:28:39.5 and again at 02:28:52.3, the second of which (pid 4275) is
#     alive for the whole instrumentation run, logging `WindowManagerShell ...
#     app=com.android.systemui` minutes after this function prints its final line.
#   - Locally on android-37.0, with the package verified disabled before AND after a deliberate
#     `stop; start`: `com.android.systemui` comes up 3 s after `system_server` regardless.
#
# So `pm disable-user --user 0 com.android.systemui` does not stop SystemUI starting on this
# image, whatever else happens. The name `E2E_DISABLE_SYSTEM_UI` and the name of this function are
# kept because the matrix row, both workflows and two documents refer to them, and a rename would
# touch all of that to no benefit -- read this comment, not the name.
#
# WHAT IS LEFT IS LOAD-BEARING, so do not delete the function as dead weight. It is the 45-second
# window with zero new `hasReadColorBufferDma` aborts. The boot-time aborts land close together --
# 02:28:18 and 02:28:43 in that same run -- and the wait is what puts instrumentation (02:32:42)
# after them rather than inside one. That is what stops a leg reporting `Starting 0 tests`, and it
# is why the three-round retry stays.
#
# THE `pm disable-user` CALL STAYS TOO, for a narrower reason than it was written for: every green
# leg and every measurement quoted anywhere about this row was taken with it applied and SystemUI
# running. Removing it would change the configuration the numbers came from, which is not a change
# to make while fixing a flake.
#
# AND THE FRAMEWORK RESTART IS GONE, having been measured to be worse than nothing. It was written
# as `adb shell stop; adb shell start`, which are root-only; adbd is not root, so every leg printed
# `Must be root` twice and restarted nothing. Adding `adb root` made it real, and api37-debug run
# 34010167885 is what that looks like: `pm disable-user` reports success, the stop lands ~2 s later
# and kills system_server before PackageManager has flushed its delayed write of package
# restrictions, so the state is gone on the way back up -- `NOT DISABLED after the restart`, three
# rounds, `final state: SystemUI STILL ENABLED`, and the leg then reported `expected: 0,
# received: 0`. A 15 s pause before the stop does make the state survive (bisected locally), and it
# still does not help, because of the two measurements above. So the restart is removed rather than
# repaired: it cost the leg every test it had, and there is nothing for it to buy.
#
# NOTHING HERE TRUSTS A COMMAND'S OWN REPORT, and that is not paranoia: of four runs of an
# earlier one-shot version, one (32646029143) reported `new state: disabled-user` and then
# started SystemUI eight more times. So this reports what `pm list packages -d` says AND what
# `pidof` says, side by side, rather than one line implying both.
# ---------------------------------------------------------------------------
count_aborts() { adb logcat -d -b crash 2> /dev/null | grep -c 'hasReadColorBufferDma'; }
systemui_disabled() { adb shell pm list packages -d 2> /dev/null | grep -q 'com.android.systemui'; }
systemui_pid() { adb shell pidof com.android.systemui 2> /dev/null | tr -d '\r\n'; }

disable_region_sampling() {
  local round=1 i out pid before after
  while [ "$round" -le 3 ]; do
    echo "--- round $round ---"
    for i in $(seq 1 10); do
      out="$(adb shell pm disable-user --user 0 com.android.systemui 2>&1 | tr -d '\r')"
      echo "  pm attempt $i: $out"
      case "$out" in *"new state: disabled"*) break ;; esac
      sleep 5
    done

    if systemui_disabled; then
      echo "  pm list packages -d: com.android.systemui is in it"
    else
      echo "  pm list packages -d: com.android.systemui is NOT in it"
    fi
    # Printed next to the line above precisely because the two disagree on this image, and a
    # reader who sees only the first will believe something that is not true.
    pid="$(systemui_pid)"
    echo "  com.android.systemui pid: ${pid:-none} (expected: a pid -- see the header)"

    before="$(count_aborts)"
    sleep 45
    after="$(count_aborts)"
    echo "  aborts: $((after - before)) new in 45 s (total ${after:-0})"
    [ "$((after - before))" -eq 0 ] && break
    echo "  still aborting after round $round"
    round=$((round + 1))
  done

  # A warning rather than an exit. If the disable did not take, the run is about to report
  # `Starting 0 tests` and fail on its own -- and it will do so with the logcat, the crash
  # buffer and the diagnostics attached, which is more useful than dying here with none of it.
  if [ "$((after - before))" -eq 0 ]; then
    echo "  final state: 45 s with no new aborts -- the suite starts here"
  else
    echo "::warning::E2E api${LABEL}: still aborting after three rounds -- expect INSTRUMENTATION_ABORTED"
  fi
  return 0
}

if [ "${E2E_DISABLE_SYSTEM_UI:-}" = "1" ]; then
  echo "::group::E2E api${LABEL} -- waiting out the boot-time gralloc aborts"
  disable_region_sampling
  echo "::endgroup::"
fi

# Stream logcat from now until the step ends, into a file that survives to the artifact upload.
# Without this, a failure that happens on-device leaves nothing behind: `adb logcat -d` at the
# end only has whatever is still in the ring buffer, and a chatty test run evicts the cause.
echo "===== logcat (api${LABEL}) =====" >> "$LOGCAT_LOG"
adb logcat -v time >> "$LOGCAT_LOG" 2>&1 &
LOGCAT_PID=$!

dump_diagnostics() {
  {
    echo "===== E2E api${LABEL} failure diagnostics -- $(date -u +%FT%TZ) ====="
    echo "--- adb devices ---";                adb devices -l 2>&1 || true
    echo "--- guest memory ---";               adb shell cat /proc/meminfo 2>&1 | grep -E 'MemTotal|MemAvailable|SwapTotal' || true
    echo "--- guest storage ---";              adb shell df /data 2>&1 || true
    echo "--- is the app even installed? ---"; adb shell pm list packages 2>&1 | grep -a libremedia || true
    echo "--- native crashes ---";             adb logcat -d -b crash 2>&1 | tail -80 || true
    echo "--- runner: kvm ---";                ls -l /dev/kvm 2>&1 || true
    echo "--- runner: memory ---";             free -h 2>&1 || true
    echo "--- runner: disk ---";               df -h 2>&1 || true
  } >> "$DIAG_LOG" 2>&1 || true

  # Also to the step log, so the common case needs no artifact download.
  echo "----- FAILURE SUMMARY (api${LABEL}) -----"
  adb shell cat /proc/meminfo 2>&1 | grep -E 'MemTotal|MemAvailable' || true
  echo "--- native crashes (tail 60) ---"
  adb logcat -d -b crash 2>&1 | tail -60 || true
}

capture_wedge() {
  {
    echo "==================================================================="
    echo "===== E2E WEDGE -- api${LABEL} -- $1"
    echo "===== $(date -u +%FT%TZ) -- after ${WEDGE_TIMEOUT}s wrapper timeout"
    echo "==================================================================="
    # The single most useful line: which test was in flight when everything stopped.
    echo "--- running/last instrumented test (logcat TestRunner) ---"
    grep -a TestRunner "$LOGCAT_LOG" 2>/dev/null | tail -25 || true
    echo "--- boot state ---"
    adb shell getprop sys.boot_completed 2>&1 || true
    echo "--- are the binder services published? ---"
    for svc in input window activity media.player; do
      echo "  service check $svc:"; adb shell service check "$svc" 2>&1 || true
    done
    APP_PID="$(adb shell pidof "$APP_ID" 2>/dev/null | tr -d '\r')" || true
    TEST_PID="$(adb shell pidof "$TEST_ID" 2>/dev/null | tr -d '\r')" || true
    echo "--- pids --- app: ${APP_PID:-<none>}  test: ${TEST_PID:-<none>}"
    # SIGQUIT makes ART dump every thread's stack to logcat and /data/anr. This is what
    # distinguishes a deadlocked test from a stuck native encode from a dead device.
    echo "--- SIGQUIT thread dumps ---"
    for pid in $APP_PID $TEST_PID; do
      [ -n "$pid" ] && adb shell kill -3 "$pid" 2>&1 || true
    done
    sleep 5
    echo "--- /data/anr/* ---"
    adb shell 'cat /data/anr/* 2>/dev/null' 2>&1 || true
    echo "--- dumpsys activity ---"; adb shell dumpsys activity 2>&1 || true
    echo "--- dumpsys window ---";   adb shell dumpsys window 2>&1 || true
    # FFmpeg and Media3 both run through MediaCodec; a wedged transcode shows up here.
    echo "--- dumpsys media.player ---"; adb shell dumpsys media.player 2>&1 || true
    echo "--- logcat -d (tail 400, includes the SIGQUIT dump) ---"
    adb logcat -d 2>&1 | tail -400 || true
  } >> "$WEDGE_LOG" 2>&1 || true
  echo "::warning::E2E api${LABEL} WEDGED ($1) -- see the wedge-diagnostics-api${LABEL} artifact"
}

echo "::group::E2E api${LABEL}"
adb shell cat /proc/meminfo 2>&1 | grep -E 'MemTotal|MemAvailable|SwapTotal' || true

status=0
# -k 30s SIGKILLs a gradle client that ignores SIGTERM. The wrapper covers ONLY the foreground
# gradle client -- never the emulator, which the action owns -- so it cannot hang the leg.
#
# E2E_EXTRA_GRADLE_ARGS is unset in CI, so this expands to nothing and the command is exactly
# what it has always been. It exists for tools/local-emulator/run-e2e.sh, which reuses this
# script rather than forking it: that runs several API levels back to back against one checkout
# and passes `--rerun`, so a level cannot be skipped as up-to-date and report the previous
# level's results as its own. CI gets a fresh runner per level and does not need it.
#
# `2>&1 | tee`, and the `2>&1` is the load-bearing half. The step log merges both streams, so
# reading one cannot tell you which stream a line came from -- and the single line the report
# below needs most, `Test run failed to complete. ... INSTRUMENTATION_ABORTED`, is not on
# stdout. Capturing stdout alone would leave the report saying "completed cleanly: yes" forever,
# which is precisely the comparison that cannot fire. pipefail is already set and tee exits 0,
# so the pipeline's status is still gradle's -- including the 124 that means the wrapper fired.
#
# `tee` and not `tee -a`, unlike the logcat above: CI gets a fresh runner per leg, but
# tools/local-emulator/run-e2e.sh reuses one machine, and an appended log would have the report
# reading the PREVIOUS run of the same API level. The console goes plain rather than showing
# gradle's live progress bar, which is what it already did in CI.
# shellcheck disable=SC2086
timeout -k 30s "$WEDGE_TIMEOUT" \
  ./gradlew :app:connectedDebugAndroidTest -PabiFilters=x86_64 --stacktrace \
  ${E2E_EXTRA_GRADLE_ARGS:-} 2>&1 | tee "$GRADLE_LOG" || status=$?
echo "::endgroup::"

# Whether the wrapper timeout fired, decided ONCE. 124 is `timeout` saying it killed the
# command, and two places downstream need that fact: capture_wedge below, and the report, which
# otherwise calls a killed leg `completed cleanly: yes` (#118). Deriving it twice is how those
# two would drift apart -- the report would keep printing after someone changed what a wedge
# means here. It stays a string: empty on every other path, so those legs pass an empty
# E2E_WEDGED_AFTER and the report behaves exactly as before.
wedged=""
[ "$status" -eq 124 ] && wedged="$WEDGE_TIMEOUT"

# The run-shape report: expected/received/failed and whether the run finished, every time,
# green or red. It never changes `status` -- it is a diagnostic, and the header's rule about
# diagnostics applies to it as much as to every probe below.
#
# E2E_WEDGED_AFTER is the wedge, told to the report rather than left for it to infer. It cannot
# be inferred: a wedge is gradle never returning, so gradle printed no verdict at all, and the
# log the report reads looks like a run that simply stopped. Only this script knows the
# difference, because only this script saw the exit status.
#
# The baseline argument, and only it, turns on the comparison, and only the advisory API 37 job
# passes E2E_ADVISORY=1. Comparing on the gating legs would announce a deviation on all five of
# them every run, since they run the whole suite rather than the marked three. They still get
# the report: a truncated run reporting fewer results than it ran is what #108 looks like, and
# `completed cleanly` is the field that shows it.
if [ "${E2E_ADVISORY:-}" = "1" ]; then
  E2E_WEDGED_AFTER="$wedged" bash "$SCRIPT_DIR/e2e-report-shape.sh" "$LABEL" "$GRADLE_LOG" \
    "$REPO_ROOT/app/src/androidTest/java/org/libremediaconverter/FailsOnEmulatorApi37.kt" || true
else
  E2E_WEDGED_AFTER="$wedged" bash "$SCRIPT_DIR/e2e-report-shape.sh" "$LABEL" "$GRADLE_LOG" || true
fi

if [ "$status" -eq 0 ]; then
  kill "$LOGCAT_PID" 2>/dev/null || true
  exit 0
fi

if [ -n "$wedged" ]; then
  capture_wedge "api${LABEL}"
else
  echo "::error::E2E api${LABEL} failed (exit $status)"
fi
dump_diagnostics
kill "$LOGCAT_PID" 2>/dev/null || true
exit "$status"

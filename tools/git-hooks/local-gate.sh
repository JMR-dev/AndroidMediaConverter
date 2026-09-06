#!/usr/bin/env bash
#
# The local gate: what has to be green before a commit is made or a branch is pushed.
#
# THE RULE THIS ENFORCES (2026-09-06). Source changes must have the unit tests AND the
# instrumented tests passing at every supported API level before they are committed or
# pushed; test changes must have the whole suite passing at every API level. CI is not the
# place to find out. Four legs of this repo's history were spent discovering on CI what a
# local sweep would have said in twenty minutes -- and worse, the failing leg MOVED between
# runs (API 35 red then green, API 34 green then red), which is exactly the signal that gets
# misread as "someone else's flake" when it is read one leg at a time.
#
# WHY BOTH HOOKS RUN THE SAME GATE. A pre-commit-only gate is bypassed by amending; a
# pre-push-only gate lets a broken commit exist locally and get rebased into something else.
# Running both is not redundant in practice because of the cache below.
#
# THE CACHE IS KEYED ON CONTENT, NOT ON TIME, AND ON THE RIGHT CONTENT. The sweep is recorded
# under the hash of the `app/src` SUBTREE it verified, not the whole repo tree. Keying it on the
# whole tree was the first cut and it was wrong in a way that would have trained people to hate
# this hook: editing a comment in CLAUDE.md, or in this script, invalidated a sweep of identical
# application code and re-ran forty minutes of emulators to prove nothing. What the sweep is
# evidence about is `app/src`; that is what it is filed under. Any change to a single byte under
# `app/src` still invalidates it. The JVM gate is cheap and runs unconditionally.
#
# WHAT COUNTS AS "EVERY SUPPORTED API LEVEL", AND WHY 37 IS NOT AN EMULATOR HERE. 33, 34, 35
# and 36 run the whole suite on emulators. **API 37 cannot be run on an emulator on this host at
# all** -- not "is red", cannot run: measured 2026-09-06, the image logs
# `3 new surfaceflinger aborts in 45 s (want 0)` and then the APK install itself fails with
# `Can't find service: package`, because the framework is already gone before Gradle gets to
# install anything. `Starting 0 tests`. That is the same gralloc abort docs/api-37-emulator-crash.md
# measures, hit earlier in the sequence than the suite.
#
# So API 37 is covered here by the physical Pixel 10 Pro XL when it is attached, and by CI's
# gating leg otherwise. The hook says loudly which of the two happened rather than quietly
# claiming five levels when it ran four.
#
# THERE IS DELIBERATELY NO SKIP VARIABLE. An `LMC_SKIP_E2E=1` would be `--no-verify` wearing
# a different hat, and `--no-verify` needs the repo owner's say-so each time. If this gate is
# wrong, fix the gate.
set -uo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT" || exit 1

MODE="$(basename "$0")"
ZERO="0000000000000000000000000000000000000000"
CACHE_DIR=".git/lmc-verify"
GRADLE_GATE=(:app:assembleDebug :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin
  :app:ktlintCheck :app:detekt :app:lintDebug)

say() { printf '\n\033[1m[local-gate]\033[0m %s\n' "$*"; }
die() {
  printf '\n\033[1;31m[local-gate] BLOCKED\033[0m %s\n' "$*"
  printf '  The rule: source work needs unit + e2e green at every API level before commit/push;\n'
  printf '  test work needs the whole suite green at every level. Fix it, or ask before using\n'
  printf '  --no-verify -- that flag is not yours to reach for unprompted.\n\n'
  exit 1
}

# --- what changed, and what tree is being verified ---------------------------------------

changed_files=""
tree=""
case "$MODE" in
  pre-commit)
    changed_files="$(git diff --cached --name-only --diff-filter=ACMR)"
    tree="$(git rev-parse "$(git write-tree):app/src" 2>/dev/null || echo "")"
    ;;
  pre-push)
    # stdin is `<local ref> <local sha> <remote ref> <remote sha>`, one line per ref pushed.
    while read -r _ local_sha _ remote_sha; do
      [ "$local_sha" = "$ZERO" ] && continue          # branch deletion carries no content
      base="$remote_sha"
      if [ "$remote_sha" = "$ZERO" ]; then
        # A new branch: compare against main rather than against every commit ever made.
        base="$(git merge-base origin/main "$local_sha" 2>/dev/null || echo "")"
      fi
      if [ -n "$base" ]; then
        changed_files="$changed_files$(git diff --name-only --diff-filter=ACMR "$base" "$local_sha")"$'\n'
      else
        changed_files="$changed_files$(git show --pretty=format: --name-only "$local_sha")"$'\n'
      fi
      tree="$(git rev-parse "$local_sha:app/src" 2>/dev/null || echo "")"
    done
    ;;
  *)
    say "unknown hook name '$MODE'; nothing to do"
    exit 0
    ;;
esac

if [ -z "${changed_files//[[:space:]]/}" ]; then
  say "no added/modified files; nothing to verify"
  exit 0
fi

touches_source=0
touches_tests=0
while IFS= read -r f; do
  case "$f" in
    app/src/main/*) touches_source=1 ;;
    app/src/test/*|app/src/androidTest/*) touches_tests=1 ;;
  esac
done <<< "$changed_files"

# --- the cheap gate always runs -----------------------------------------------------------

# --- shellcheck, at CI's exact pin ---------------------------------------------------------
# WHY THIS IS HERE. The gate ran ktlint, detekt and Android lint but not shellcheck, so a new or
# edited `.sh` file was precisely the case where this hook passed and CI's Static analysis leg
# still went red. That is not hypothetical: this script is itself a new `.sh` file, and the first
# thing it could not check was itself. It was caught by hand twice before it was caught here.
#
# THE DIGEST IS READ OUT OF status_check.yml, NOT COPIED INTO THIS FILE. shellcheck 0.9.0 and
# 0.11.0 disagree about how to report a trap handler -- SC2317 on seven body lines versus SC2329
# once on the declaration, same script, same directive, one red and one green. That disagreement
# is why CI pins by digest, and a second copy of the digest here would drift from it silently.
# When it drifts, the symptom is this gate passing and CI failing: the exact thing this section
# exists to prevent. So there is one digest in the repo and this reads it.
#
# ALL TRACKED FILES, not just changed ones, because that is what CI does -- `git ls-files '*.sh'`.
# The point is to predict that leg, not to audit the diff.
shellcheck_pin="$(grep -oE 'koalaman/shellcheck@sha256:[0-9a-f]{64}' \
  .github/workflows/status_check.yml | head -1)"
runtime=""
for candidate in podman docker; do
  if command -v "$candidate" >/dev/null 2>&1; then
    runtime="$candidate"
    break
  fi
done

if [ -z "$shellcheck_pin" ]; then
  say "NOT COVERED: shellcheck. Could not read the pinned digest out of
  .github/workflows/status_check.yml -- if that pin moved or was reformatted, fix this grep
  rather than leaving the check silently absent."
elif [ -z "$runtime" ]; then
  say "NOT COVERED: shellcheck. Neither podman nor docker is on PATH, and there is no shellcheck
  system package on this host. CI's Static analysis leg is what answers for .sh files then."
else
  # :z is podman's SELinux relabel and is what this host needs; docker on CI does without it.
  mount=":z"
  [ "$runtime" = "docker" ] && mount=""
  say "shellcheck ($runtime, $shellcheck_pin)"
  if ! git ls-files -z '*.sh' |
    xargs -0 -r "$runtime" run --rm -v "$PWD:/mnt$mount" "docker.io/$shellcheck_pin"; then
    die "shellcheck failed. CI runs the same digest over the same files, so this is a red
  Static analysis leg waiting to happen."
  fi
fi

say "$MODE: running the JVM gate"
if ! ./gradlew "${GRADLE_GATE[@]}" --continue; then
  die "the JVM gate failed (assemble, unit tests, androidTest compile, ktlint, detekt, lint)."
fi

# --- the sweep, when code is involved ------------------------------------------------------

if [ "$touches_source" -eq 0 ] && [ "$touches_tests" -eq 0 ]; then
  say "no app/src changes; the instrumented sweep is not required for this one"
  mkdir -p "$CACHE_DIR" && [ -n "$tree" ] && : > "$CACHE_DIR/$tree"
  exit 0
fi

if [ -n "$tree" ] && [ -f "$CACHE_DIR/$tree" ]; then
  say "app/src ($tree) already swept and green; nothing under app/src has changed since"
  exit 0
fi

say "app/src changed -- sweeping API 33, 34, 35, 36 (this takes tens of minutes, by design)"
if ! tools/local-emulator/run-e2e.sh 33 34 35 36; then
  die "the instrumented suite is not green on 33-36."
fi

# API 37: the physical device if it is here, and an honest statement if it is not. run-e2e.sh is
# emulator-only (and overwrites E2E_EXTRA_GRADLE_ARGS with --rerun, so extra args cannot be passed
# through it), so this drives Gradle directly with the serial pinned -- the phone must never be
# picked up by accident, which is the hazard run-e2e.sh's header calls out.
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"

device=""
while read -r serial state; do
  [ "$state" = "device" ] || continue
  case "$serial" in emulator-*) continue ;; esac
  [ "$(adb -s "$serial" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')" = "37" ] || continue
  device="$serial"
  break
done < <(adb devices 2>/dev/null | tail -n +2)

levels="33, 34, 35, 36"
if [ -n "$device" ]; then
  say "API 37 on the attached device $device"
  if ! ANDROID_SERIAL="$device" ./gradlew :app:connectedDebugAndroidTest -PabiFilters=arm64-v8a; then
    die "the instrumented suite is not green on API 37 (device $device)."
  fi
  levels="$levels, 37"
else
  say "NOT COVERED LOCALLY: API 37. No API 37 device is attached, and the API 37 emulator cannot
  install the APK on this host (see this script's header). CI's gating leg is what answers for it;
  attach the Pixel 10 Pro XL to have this hook cover it too."
fi

mkdir -p "$CACHE_DIR" && [ -n "$tree" ] && : > "$CACHE_DIR/$tree"
# Name the levels rather than claiming "every supported level". The first cut said the latter on
# both paths, including the one that had just printed NOT COVERED two lines above -- a false claim
# printed by the tool whose whole job is to stop false claims reaching CI.
say "green on API $levels; $MODE allowed"
exit 0

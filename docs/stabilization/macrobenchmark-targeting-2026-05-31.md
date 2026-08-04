# Macrobenchmark Targeting - 2026-05-31

Public release status: CLEARED BY 2026-06-01 LOCAL RC PASS.

## Scope

The first local full macrobenchmark run on the Android VM failed because UI Automator searched settings rows by visible text and bottom-nav coordinates. Several settings rows already have stable Compose test tags, but those tags were not exported as resource IDs for UI Automator.

## Changes

- Export Compose test tags as resource IDs in debug builds only.
- Target settings macrobenchmark detail rows by stable tags, with visible-text and coordinate fallbacks for VM runs where UI Automator does not expose rows promptly.
- Keep root settings navigation resilient with bottom-nav coordinate taps plus the existing dashboard/settings swipe affordance.
- Keep visible-text lookup as a fallback for compatibility.

## Verification

- `ANDROID_SERIAL=emulator-5554 ./gradlew --no-daemon --console=plain --stacktrace :app:installDebug :macrobenchmark:connectedCheck -Pmacrobenchmark.targetPackage=com.foxhole.guard.debug -Pandroid.testInstrumentationRunnerArguments.class=com.foxhole.guard.macrobenchmark.HomeMacrobenchmark#settingsDnsTransition`
- `ANDROID_SERIAL=emulator-5554 bash scripts/run-ci-macrobenchmark.sh schedule refs/heads/dev`

The full VM macrobenchmark run completed with 10 discovered tests, 1 intentionally ignored `homeScroll` scenario, and 0 failures.

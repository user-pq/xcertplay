# E02 R6.2: independent DIM read-back and diagnostics

R6.2 builds on R6.1's exit/recovery behavior and never creates a persistent navigation-package disable.
It does not claim to fix physical cluster visibility without a new vehicle test.

- `XingruiReflectiveDim.read()` observes `isScreenProjection` before invoking the optional public getter.
  Each observation has its own error handling. A wrapped `AbstractMethodError`, `NoSuchMethodError` or
  unsupported getter produces `enabled=null` with the actual root cause, retaining the internal value.
  An absent public getter no longer prevents constructing the adapter. A working getter is still read
  when the private internal field is unavailable. No read-back error is reclassified as a failed write.
- Request write order and raw arguments remain unchanged: kind=0, the actual owner package/class,
  followed on enable by `disableScreenProjection(false, 2)`. The OEM meanings of kind/mode and acceptance
  of that owner remain unverified. A returned method invocation is logged only as a returned call,
  not as authorization, server acceptance, actual routing, or physical visibility.
- `SENT_UNVERIFIED` means every required write returned without throwing and no readable value contradicts
  the request, but full verification is unavailable. It causes no repeated writes and is neither a failed
  write nor confirmed software state. Actual write exceptions or a readable conflicting value retain the
  existing bounded retries. Partial progress on a throwing request is not silently upgraded to sent.
- Release completion still carries a Boolean indicating **confirmation**, not write success. Unverified
  release completes promptly with confirmation=false; it is not delayed to the exit deadline just because
  a getter is missing. Exit diagnostics explicitly say unconfirmed and do not print SDK release=false as
  though that proved a failed call. Legacy native-navigation recovery failure still blocks normal full exit.
- Request diagnostics capture independent internal state before and after each request, DIM/navigation/power
  implementation class names, actual arguments, the stage that threw, and the unwrapped exception.
- Presentation diagnostics capture onStart, successful show return, surface lifetime, first stream-111
  frame release, stream changes, and onStop: isShowing, display ID/name/state/validity/flags/size,
  Surface validity, stream/map flags and window alpha. These are Android software observations only.
- Diagnostic ZIPs additionally contain `display-state.txt`. Logcat includes `XingruiCluster`, the real
  `DiPlay-Cluster` tag, `ClusterMapPresentation`, and more OEM DIM/power tags. READ_LOGS and other OS access
  restrictions still apply; adding tags does not grant permission or ensure all system messages appear.

## Validation

104 focused pure-JVM tests pass, including the production reflection adapter and real session/coordinator
wiring with fixtures that model an unimplemented getter. Generated-source exit and observability contracts,
and the detached log collector integration pass. All production overlays are byte-identical to the compiled
source inputs. No production source edits followed verification.

Final validation on 2026-10-23, implementation commit e4eb579991be98ed474ed90f436ccf4ee620332c:

- Android execution: all 104 Xingrui tests pass. All 10 existing side-panel/video-host lifecycle tests pass.
- Shared: 1495 tests, 0 failures, 1 skipped. Home: 6 tests, 0 failures.
- Common: 1469 tests, 159 failures. All 159 failed test identities match the previously recorded baseline;
  there are no added or removed failures. The complete required Gradle task run used --continue and returns
  failure solely for :common:testDebugUnitTest. It must not be reported as an entirely successful build/check run.
- Mobile/home/maphost debug APKs were produced; all three lint reports have 0 errors (20/7/1 warnings).
- Final APK: diplay-Xingrui-E02-v0.2.20-r6.2-platform-standalone.apk
- Package: com.shihab.diplay.xingrui.platform; version 0.2.20-xingrui-r6.2-platform; versionCode 46.
- SHA-256: eafe62711f6b330072ef98797cec3536893b60482fab58d205bb3cd913331c82
- The platform signer matches R6.1:
  c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8.
- Signature verification succeeds on API 28 and API 25-27. Native-library ZIP alignment checks (4K/16K),
  CRC/manifest/production-DEX/fixture-exclusion checks pass. All 16 native libraries are present.
- The complete MFi credential pair is byte-identical to the previous user-provided standalone build.
  Credentials and signing keys are local-only inputs and are not committed.

The previous R6.1 whole-common-suite baseline has 159 failures; they must not be described as a green suite.
No new vehicle results have been supplied for this release.

## Next on-car capture

Cover-install the same platform package; connect wireless CarPlay, enable Ultra, wait for the intended
instrument content, disable Ultra and use in-app exit. Export the adaptation diagnostic ZIP. Inspect the
actual permission report, before/after internal values, independent getter status, power implementation,
Presentation/window state and OEM messages together. If the display remains unchanged, preserve that
observation; do not infer physical success from a Surface callback or method returning normally.

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
and the detached log collector integration pass. Android build/APK results will be recorded after completion.

The previous R6.1 whole-common-suite baseline has 159 failures; they must not be described as a green suite.
No new vehicle results have been supplied for this release.

## Next on-car capture

Cover-install the same platform package; connect wireless CarPlay, enable Ultra, wait for the intended
instrument content, disable Ultra and use in-app exit. Export the adaptation diagnostic ZIP. Inspect the
actual permission report, before/after internal values, independent getter status, power implementation,
Presentation/window state and OEM messages together. If the display remains unchanged, preserve that
observation; do not infer physical success from a Surface callback or method returning normally.

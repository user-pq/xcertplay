# E02 R6 notes — evidence from car captures 2026-10-18 and 2026-10-23 (FS11A1, Android 9, ECARX_E02_V1.3)

## Wheel keys: requestKeysInterception takes SDK action ids, not Android keycodes
- Raw keys 304/305/302 are mapped by ecarx.xsf.inputservice to action ids 200088 (MediaPrevious),
  200087 (MediaNext), 200025 (VolumeDown). 301 is volume up (200024 inferred).
- AdaptAPI.InputImpl matches requested keys against those ids ("isValidKey keys[n]=200088=200088") and logs
  "keycode is not in request keys, so we don't report" otherwise.
- A working E02 reference app binds "上一曲 keyCode=200088 / 下一曲 keyCode=200087" in its learning UI.
- r4/r5 requested [305,87,88,85,126,127]; the SDK accepted the list without validating, but no event could
  ever match. This explains the silent wheel. R6 requests 200087/200088; legacy codes only map actions.
- Every raw callback is logged as "方控回调" so the next capture shows exactly what the SDK delivers.

## Cluster (DashboardMapDisplay id=1, 1920x720, 213 dpi, FLAG_PRESENTATION)
- Reference app and r4+ send the same call: setPresentationToDimSwitch(app=0, pkg, cls, on=true), accepted
  with "导航投屏状态准备好，可以投屏"; its updateNAVInfo also stays isRoutingSuccess=false.
- Native navigation (ecarx.naviservice) sets isRoutingSuccess=true with navStatus=1, turnId, road name,
  distances, ETA, direction; a CarPlay switch request arriving afterwards resets it to false.
- iPhone (Amap) sent RouteGuidanceUpdate (0x5201) in this capture only with state=3 / destination
  "未知位置"; no real CarPlay route was active, so forwarding real guidance is still unobserved.

## Cluster preemption (car log 2026-10-23 12:35)
- The reference app disables ecarx.naviservice while it projects (system uid, "仪表冲突处理"). The vendor service
  had no process before 12:35:11; the user's "restore original service" action (pm default-state) started it and
  it took the cluster back (firstFrame 1 -> 0). Re-entering CarPlay disabled/killed it again (am_kill, 12:36:35).
- R6 platform build does the same, more conservatively: XingruiClusterGuard disables ecarx.naviservice only
  while a DIM projection session is claimed, restores it when the session ends, at boot and on package
  replacement, and never touches a service it did not disable (XingruiClusterGuardPolicy, unit-tested).
- Opt out: create /sdcard/Download/XingruiLogs/no-cluster-guard. Manual recovery if the app is removed while held:
  adb shell pm default-state ecarx.naviservice
- Effect while held: the vehicle's own navigation/cluster guidance is unavailable.

## Integration checks
- Known Android media codes 305/87 and 304/88 in saved mappings migrate to SDK actions 200087 and 200088.
- DIM and Input reflection are shared by production sessions and JVM contract tests. Fixtures validate call
  shapes, callback ownership, failed reads, retries, cancellation and release; they do not prove firmware
  authorization or physical instrument visibility. Diagnostic exports include a read-only SDK signature probe.
- A missing navigation object, missing display-source request or unknown internal state never confirms DIM.
  Close waits for bounded release checks before disposing; stale retries cannot reopen a closed session.
- Navigation conflict handling records the original package enabled state before disabling. It restores that
  exact state on stream release, SDK disconnect, failed retries, session close, next app start, boot or update.
  Recovery after process death requires a subsequent start/boot; uninstalling while held still needs the
  manual recovery command above. The uploaded policy tests cannot guarantee process-death recovery alone.
- Raw key repeats are deduplicated, journal writes from callbacks are asynchronous with a bounded queue.
- CI verifies the built APK identity, manifest, native library alignment and absence of test fixtures.
  Final platform signing and paired MFi assets remain local; no private material is committed.
- The full inherited suite previously had 159 failures; inspect the full-suite results separately from the
  focused Xingrui tests and do not describe a green workflow as all upstream tests passing.

## mediacenter
"No Authorization to operate [mediacenter]" is also logged for the working reference app (uid 1000, with AppId/AppKey),
so it is not what blocks the wheel (Input) or the cluster (DIM).

## Validation recorded 2026-10-23
- Local Kotlin 2.2.10 / JUnit 4.13.2: all 77 focused tests passed.
- Detached log collector integration test and shell syntax check passed.
- GitHub displays a fork Actions usage suspension, so the final APK and required Android checks were built locally.
- mobile/home/maphost lintDebug and assembleDebug all passed. shared: 1495 tests, zero failures, one skipped;
  home: six tests, zero failures.
- Full common suite: 1442 tests; all 159 historical failure names reproduced, plus 14 ExistingWifiManagerTest
  failures caused by this isolated host exposing only loopback. The fixture uses only an interface name/index,
  with all addresses and capabilities mocked, so it now allows an existing loopback interface as a fallback.
- After that test-only portability fix, the targeted rerun passed all 91 tests (77 Xingrui + 14 existing Wi-Fi).
  The whole common suite was not repeated after this fixture-only fix; the 159 historical failures remain unresolved.
- Local Mockito tests use an explicit startup agent because dynamic self-attachment is unavailable here.
- Final standalone: com.shihab.diplay.xingrui.platform, version 0.2.20-xingrui-r6-platform, code 44.
  Android 9 signature verification, v2 verification for API 25–27, native alignment (16 libraries), APK CRC,
  production code markers and both authentication assets all passed. The signing certificate matches R5.
- APK SHA-256: 66ce5949014bf5b8a7ce560a3c3285255e9e75045a7429e9010ce5e928b3ff46.
- On-car instrument visibility, vehicle-property permissions and all key/audio/startup behavior still require
  a real vehicle test; build and mock-contract checks do not establish those outcomes.

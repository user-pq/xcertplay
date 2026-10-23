# E02 R6.1: exit and recovery

This supersedes R6's navigation-package conflict handling.

- Normal software exit and disconnect wait for the DIM release completion before controller teardown,
  stopping session services and finishing the Activity. Already-closing presentations remain in a process
  registry so an early onStop cannot lose the completion callback. Concurrent shutdown requests share one completion.
- DIM release retains the bounded retries and returns software success/failure. The exit barrier has a
  12-second deadline; unknown/timed-out SDK state is logged as unconfirmed, not physical recovery proof.
- The host no longer calls Process.killProcess. Android owns process lifetime, so late vendor cleanup
  is not deliberately cut off by the app. Returning to the launcher still preserves an active CarPlay session.
- New sessions never disable, suspend or force-stop the native navigation package. Removing this persistent
  change is necessary because force-stop/uninstall/process death cannot guarantee any app cleanup callback.
  Consequently the previous strong suppression of native-navigation preemption is removed; cluster stability
  must be measured on the car, rather than assumed.
- Existing R6 recovery records are preserved. Recovery restores only an explicitly recorded original DEFAULT
  or ENABLED state, verifies the package read-back, and clears the journal only on a successful commit.
  New sessions do not adopt an already-disabled service without our journal. Exit retries old recovery up to
  three times; if recovery remains unconfirmed, full software exit is aborted with a retry message.
- Boot/update recovery runs only for protected system broadcasts, in a non-exported receiver. Broadcast
  completion is bounded independently of a stalled package-manager request.
- No CAN/VHAL writes, fake telemetry or changes to vehicle firmware are introduced.

## Verification

Production session/reflection contracts, exit coordinator, release callback coalescing, release registry,
and legacy recovery are exercised in focused tests. The generated-source contract additionally checks the
actual Android host wiring, avoiding a test-only duplicate of the exit algorithm.

Verified on 2026-10-23 against pinned upstream 3c95c936859e8a9153f0040caf2337adddb9e3e4:

- Fresh application of all patches and generated-source exit contracts pass; generated common sources match
  the compiled sources. Detached log collector integration and shell syntax checks pass.
- All 91 Xingrui tests pass; all 10 existing side-panel/video-host lifecycle cases pass after awaiting the
  asynchronous exit barrier. Side-panel refresh stops immediately at shutdown entry.
- Shared: 1495 tests, 0 failures, 1 skipped. Home: 6 tests, 0 failures.
- Common: 1456 tests, 159 failures. Failed test identities match all 159 previously recorded failures;
  there are no additional failures. The full suite is therefore not green.
- Mobile/home/maphost lint and debug assembly complete with BUILD SUCCESSFUL. Mobile lint reports
  0 errors and 20 warnings. No further production edits followed this build.
- Final package: com.shihab.diplay.xingrui.platform, 0.2.20-xingrui-r6.1-platform, versionCode 45.
- APK: diplay-Xingrui-E02-v0.2.20-r6.1-platform-standalone.apk
- SHA-256: e762fac1047851feef2e34b42a5ad49edd66d70d8088a7284f9ff73aaaaf3106
- APK signature verification passes for API 28 and API 25-27. The signer is unchanged from R6:
  c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8.
- Packaging/CRC/manifest/DEX/native-library alignment checks pass. The MFi credential pair is byte-identical
  to the previous user-provided standalone build; neither credentials nor signing keys are committed.

Cover-install over R6, preserving the legacy recovery journal, and open the app once. Returning to the
launcher is still background operation; use the in-app exit action to request full shutdown. Instrument
visibility and OEM SDK behavior during exit/force-stop/uninstall remain on-car checks; software tests
do not establish physical recovery. Removing persistent navigation suppression may allow OEM navigation
to compete for the cluster again; this tradeoff is intentional and needs on-car validation.

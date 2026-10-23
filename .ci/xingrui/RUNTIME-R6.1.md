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

Build/test results and final APK identity are recorded after verification. Instrument visibility and OEM
SDK behavior during actual force-stop/uninstall remain on-car checks; software tests do not establish them.

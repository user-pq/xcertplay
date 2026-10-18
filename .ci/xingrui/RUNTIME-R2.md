# E02 runtime revision 2 (2026-10-18)

Pinned upstream remains DiPlay 0.2.20 / 3c95c936859e8a9153f0040caf2337adddb9e3e4.

- Android 9 Car.createCar(Context, ServiceConnection[, Handler]) and asynchronous connect;
  property reads off the UI thread; status checked, read failures logged with causes, no fake values.
  CURRENT_GEAR is preferred over GEAR_SELECTION. Declaring car permissions does not grant them.
- A2DP_SINK (11) release for the selected iPhone only after CarPlay local media PCM playback
  advances on a non-Bluetooth AudioTrack. Reconnection is checked every three seconds until
  the CarPlay session ends. RFCOMM, HFP and the Bluetooth adapter are not disabled.
  Profile API denial is logged. No pairing or connection priority is changed.
- Confirmed E02 key 305 is consumed by the active CarPlay window and sent as NEXT HID.
  OEM keys intercepted upstream of the app still require observed ECARX routing support.
- Automatic car hotspot toggle restored, default enabled once on upgrade. Uses the existing
  saved hotspot configuration and existing tethering helper, not a new random Wi-Fi P2P network.
  WRITE_SETTINGS may still need to be allowed on the actual head unit.
- USB button removed from home. Version label now includes xingrui-r2.
- Stream 111/presentation/surface diagnostics added; repetitive display candidate logs throttled.

## Installation / identity

R2 uses com.shihab.diplay.xingrui. The earlier standalone signer private key was not
available, so R2 is a separate app rather than requiring destructive uninstall of the
working .hudtest baseline. Close the previous app before testing R2, and select the
already-bonded iPhone once in R2. It does not delete old app preferences or Bluetooth bonds.
A new private local test signer is retained separately for future updates.

## Instrument limitation

Presentation + a Surface on DashboardMapDisplay is NOT proof of a physical dashboard mode
change. The test showed OEM instruments unchanged and concurrent Amap Presentation.
A runtime-gated DimInteraction.setPresentationToDimSwitch(int,String,String,boolean)
request is added only while the Ultra Presentation is shown; close sends false if true
was dispatched. Contract source is jiopia/DiPlay-ECARX at
7f78343642fe695bb56906c3d8761c1c68bae7ce, for a DIFFERENT vehicle (Lynk DCY11),
so class/method/permissions and physical effect are explicitly unverified on E02.
Missing/denied APIs stop retries. A void return is logged as a request, never success.
No undocumented ECARX property is written. Physical IPK projection activation is still unresolved;
fixing Car reflection does not itself switch the dashboard or replace native gauges/telltales.

## Capture from phone ADB terminal

Put collect_xingrui_test.sh in /sdcard/Download/ on the head unit, then run:

    sh /sdcard/Download/collect_xingrui_test.sh start

Run the test. The phone terminal may disconnect; collection stays on the head unit.

    sh /sdcard/Download/collect_xingrui_test.sh stop
    sh /sdcard/Download/collect_xingrui_test.sh status

Wait for READY before downloading these head-unit files with the phone ADB file manager:

- /sdcard/Download/XingruiLogs/xingrui_carplay_test.txt
- /sdcard/Download/XingruiLogs/xingrui_props.txt

Raw files and health timestamps remain in the capture directory for diagnosing failed exports.
No photos or secrets should be committed to the repo. Logs may contain VIN/network identifiers.

## Verification

Apply all patches to a clean pinned upstream tree, including apply_xingrui_runtime_patch.py last.
Run python3 .ci/xingrui/test_collector.py and sh -n .ci/xingrui/collect_xingrui_test.sh.
Android compilation and APK signing are checked by Xingrui DiPlay APK workflow.
Vehicle permissions, profile disconnect acceptance, hotspot startup and physical projection
require a new stationary in-car test.

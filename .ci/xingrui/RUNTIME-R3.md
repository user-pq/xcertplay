# Xingrui E02 R3

Based on upstream v0.2.20 and R2; package com.shihab.diplay.xingrui, versionCode 40.

## Evidence and changes

The 2026-10-18 v5 car log shows stream 111 decoded up to 30 fps, but
DimInteractionImpl repeatedly reports `mVehicleManager is disconnect`.
R3 implements the ECARX IConnectable lifecycle: register a watcher, connect,
wait for onConnected, then request projection. Disconnection stops writes;
close releases the presentation before disconnecting. No raw VHAL writes.
The switch's first parameter is an APP_TYPE, not Display ID. The known AMAP
app-type contract is retained; the actual presentation is still Display 1.

The same log routes Android 305 through ecarx.xsf.inputservice, then
MediaKeyReceiver (87), MediaCtrlByKeyCb/playCtrlNext. R3 registers its own
package as an ECARX music source via MediaCenterAPI.init/registerMusic;
MusicClient onNext/onPrevious/onPlay/onPause send explicit CarPlay HID commands.
requestPlay is called only after CarPlay media audio becomes active, not in a
periodic loop that steals the original radio's focus. Session teardown
unregisters only our token. No root, OEM package spoofing, system app disabling,
or global input-service replacement is used.

A dynamic MusicClient subclass uses DexMaker 2.28.3 against the device SDK.
Missing SDK classes, denied registration and rejected requestPlay are logged.
These APIs still require E02 vehicle validation; successful dispatch is not
proof of physical instrument output or working wheel control.

Custom learned media mappings now also apply in the active CarPlay window.
They cannot affect OEM keys which never reach the window.
The simple settings regain a boot-start toggle; the first R3 launch enables it
once, with subsequent user choices preserved. This covers full Android boot,
not an unobserved OEM suspend/resume path. BOOT_COMPLETED receipt is logged.

## Interface references (read as contracts, not proof of E02 compatibility)
- Salat39/GInputBridge adaptapi DimInteraction, DIMInteractionImpl, IConnectable.
- lvalen91/CPC200-CCPA_resources AutoKit ECarXUtil and MusicClient subclass c.java.
- halftan/AMBridge MediaCenterHelper SDK loading and registration.
- linkedin/dexmaker ProxyBuilder (runtime subclassing).

## Delivery
Use the existing private R2 signer and private MFi assets locally; never commit
or publish private authentication assets. Install over R2 and open once to run
the migration. Pairing data and authentication assets are retained.

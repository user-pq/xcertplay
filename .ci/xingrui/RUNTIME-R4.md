# E02 R4 — input callback and frame-gated DIM experiment

Package com.shihab.diplay.xingrui, versionCode 41, version 0.2.20-xingrui-r4.

The R3 car log explicitly denies MediaCenterAPI with `No Authorization to operate
[mediacenter]`. R4 stops invoking this denied media API. It calls the separate
published Input.create/requestKeysInterception/IKeyCallback contract with the
real app Context. Rejection is logged, not bypassed. Requests only confirmed
E02 key 305 and standard Android media codes. Returns true only for successfully
sent CarPlay commands; repeats send once and their matching release is consumed.
No root, package impersonation, privileged permission changes or guessed vendor
key mappings. Whether E02 authorizes/delivers these callbacks is UNVERIFIED.

R3 DIM connects, but switches before stream111 has a decoded output frame.
R4 waits for both IConnectable.onConnected and the decoder's first released
buffer on the main stream111 surface. A released buffer is still not evidence
of physical cluster visibility. This ordering change is an experiment, not a
confirmed explanation of isRoutingSuccess=false. No fabricated route-start,
phone state or direct VHAL control packets are written. Stop/close releases DIM.

The normal settings add one optional '适配诊断 / 导出' entry under Recovery.
It shows version, callback results and DIM/frame status. The user selects the
ZIP destination through Android's document picker. Export includes public
runtime method contracts, capped read-only ECARX /system/framework JAR files,
and the installed system OpenAPI APK if readable. It explicitly excludes
DiPlay's own APK/assets, authentication keys, pairing data and other app data.
No exporter bytes go to public GitHub. This ZIP enables inspecting the ACTUAL
E02 SDK; currently available stubs are insufficient to claim a full fix.

Focused regression tests cover press/release pairing, failed sends, session
cleanup, both DIM readiness orderings, reconnect and stale callbacks after close.
The upstream complete verification command remains executed; its result must be
reported separately because the E02 product layout has known incompatible stock
settings/presentation assertions (R3: 159/1365 common tests failed).

Retains R2/R3 private signing identity and the user's MFi authentication assets,
embedded only after downloading the CI APK. Install over R3, open once, connect
CarPlay, test wheel keys and Ultra. If unchanged, export diagnostic ZIP and send
it with the existing v5 logs before another speculative protocol change.

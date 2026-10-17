#!/usr/bin/env python3
from __future__ import annotations
import argparse
import re
import shutil
from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        print(f"[ok] {label}: already applied")
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(
            f"[FAIL] {label}: expected exactly one upstream match in {path}, found {count}. "
            "Pinned base is DiPlay v0.2.20."
        )
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    print(f"[ok] {label}")


def regex_once(path: Path, pattern: str, replacement: str, label: str, flags: int = 0) -> None:
    text = path.read_text(encoding="utf-8")
    new_text, count = re.subn(pattern, replacement, text, count=1, flags=flags)
    if count != 1:
        raise SystemExit(f"[FAIL] {label}: expected exactly one match in {path}, found {count}")
    path.write_text(new_text, encoding="utf-8")
    print(f"[ok] {label}")


def main() -> None:
    ap = argparse.ArgumentParser(description="Apply 2023 Geely Xingrui E02 wireless/cluster adaptation")
    ap.add_argument("repo", nargs="?", default=".", help="DiPlay repository root")
    args = ap.parse_args()
    repo = Path(args.repo).resolve()
    here = Path(__file__).resolve().parent

    host = repo / "common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt"
    presentation = repo / "common/src/main/java/com/shilapi/xcertplay/ClusterMapPresentation.kt"
    geely_wheel = repo / "common/src/main/java/com/shilapi/xcertplay/GeelyWheelKeys.kt"
    if not host.exists() or not presentation.exists() or not geely_wheel.exists():
        raise SystemExit("[FAIL] Not a supported DiPlay v0.2.20 tree")

    overlay = here / "overlay/common/src/main/java/com/shilapi/xcertplay"
    target = repo / "common/src/main/java/com/shilapi/xcertplay"
    for src in overlay.glob("*.kt"):
        shutil.copy2(src, target / src.name)
        print(f"[ok] overlay {src.name}")

    # Wireless-only: never select USB CarPlay transport and never persist wired mode.
    replace_once(host,
        "    private var wirelessEnabled = false\n",
        "    // Xingrui E02 build: wireless CarPlay only.\n    private var wirelessEnabled = true\n",
        "wireless default")
    replace_once(host,
        "        transport = if (wirelessEnabled) CarPlayTransport.WIRELESS else CarPlayTransport.WIRED,\n",
        "        transport = CarPlayTransport.WIRELESS,\n",
        "force wireless runtime transport")
    replace_once(host,
        "        wirelessEnabled = AirPlayPersistence.loadWirelessEnabled(this)\n",
        "        // Ignore a legacy wired preference in this dedicated Xingrui build.\n        wirelessEnabled = true\n",
        "ignore persisted wired mode")
    regex_once(host,
        r"        if \(isIphoneUsbAttachment\(intent\)\) \{\n            AirPlayPersistence\.saveWirelessEnabled\(this, false\)\n        \}\n",
        "        // Xingrui E02 build ignores an attached iPhone as a transport selector.\n",
        "ignore USB attachment at launch")

    regex_once(host,
        r"    override fun onNewIntent\(intent: Intent\) \{\n        super\.onNewIntent\(intent\)\n        setIntent\(intent\)\n        if \(isIphoneUsbAttachment\(intent\)\) \{.*?\n        \}\n    \}\n",
        "    override fun onNewIntent(intent: Intent) {\n"
        "        super.onNewIntent(intent)\n"
        "        setIntent(intent)\n"
        "        if (isIphoneUsbAttachment(intent)) {\n"
        "            appendLog(\"Xingrui wireless-only: ignored iPhone USB attachment\")\n"
        "        }\n"
        "    }\n",
        "prevent USB hot-switch",
        re.DOTALL)
    replace_once(host,
        "        AirPlayPersistence.saveWirelessEnabled(this, wirelessEnabled)\n",
        "        AirPlayPersistence.saveWirelessEnabled(this, true)\n",
        "persist wireless-only mode")

    # Auto-enable the cluster path only when Android exposes a convincing secondary Presentation display.
    replace_once(host,
        "    private fun ensureClusterPresentation() {\n",
        "    private fun xingruiClusterProjectionEnabled(): Boolean =\n"
        "        AirPlayPersistence.loadClusterMapEnabled(this) || XingruiClusterProbe.find(this) != null\n\n"
        "    private fun ensureClusterPresentation() {\n",
        "add Xingrui cluster enable gate")
    replace_once(host,
        "        if (!AirPlayPersistence.loadClusterMapEnabled(this)) {\n            clusterLaunchGate.reset()\n",
        "        if (!xingruiClusterProjectionEnabled()) {\n            clusterLaunchGate.reset()\n",
        "auto-enable physical cluster presentation")
    replace_once(host,
        "            if (isDestroyed || !AirPlayPersistence.loadClusterMapEnabled(this@CarPlayHostActivity)) return\n",
        "            if (isDestroyed || !xingruiClusterProjectionEnabled()) return\n",
        "listen for Xingrui cluster display")
    replace_once(host,
        "        if (!AirPlayPersistence.loadClusterMapEnabled(this)) return null\n",
        "        if (!xingruiClusterProjectionEnabled()) return null\n",
        "request stream 111 for Xingrui cluster")
    replace_once(host,
        "        if (AirPlayPersistence.loadClusterMapEnabled(this)) {\n            clusterSurface?.let { sink?.setSurface(SCREEN_TYPE_ALT, it) }\n",
        "        if (xingruiClusterProjectionEnabled()) {\n            clusterSurface?.let { sink?.setSurface(SCREEN_TYPE_ALT, it) }\n",
        "route stream 111 to Xingrui cluster")

    # Keep standard Android media keys. Unknown E02 keys are logged for later refinement.
    replace_once(host,
        "        if (!CarPlayMediaButton.opensSiri(event.keyCode)) return super.dispatchKeyEvent(event)\n",
        "        if (!CarPlayMediaButton.opensSiri(event.keyCode)) {\n"
        "            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) XingruiWheelDiagnostics.log(event)\n"
        "            return super.dispatchKeyEvent(event)\n"
        "        }\n",
        "log unclaimed Xingrui wheel keys")

    # DiPlay already contains Geely OneOS steering-wheel support for geely_fx11. This dedicated
    # Xingrui build also attempts the same exported Geely input service on E02; if absent, bindService
    # simply returns false and standard media-key handling remains available.
    replace_once(geely_wheel,
        "        if (!GeelyHeadUnit.detected) return\n",
        "        if (!GeelyHeadUnit.detected) {\n"
        "            Log.i(TAG, \"Xingrui build: trying Geely input service despite unmatched product fingerprint\")\n"
        "        }\n",
        "attempt Geely wheel service on Xingrui E02")

    # Presentation fallback: keep BYD-specific selection first, then score generic public displays.
    old_fallback = '''                return displays.firstOrNull { display ->\n                    val size = sizeOf(display)\n                    DiLink4ClusterDisplay.accepts(display.name, size.x, size.y)\n                }\n'''
    new_fallback = '''                val byd = displays.firstOrNull { display ->\n                    val size = sizeOf(display)\n                    DiLink4ClusterDisplay.accepts(display.name, size.x, size.y)\n                }\n                return byd ?: XingruiClusterProbe.find(context, displays.toList())\n'''
    replace_once(presentation, old_fallback, new_fallback, "add Xingrui Presentation fallback")

    old_wait = '''        waitingLabel = FrameLayout(context).apply {\n            if (plan == null) setBackgroundColor(placeholder)\n            addView(ProgressBar(context).apply {\n                isIndeterminate = true\n                indeterminateTintList = ColorStateList.valueOf(waitingColor)\n                contentDescription = context.getString(R.string.cluster_waiting_for_map)\n            }, FrameLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt(), Gravity.CENTER))\n        }\n'''
    new_wait = '''        waitingLabel = if (XingruiClusterProbe.isCandidate(display)) {\n            XingruiUltraPlaceholderView(context)\n        } else FrameLayout(context).apply {\n            if (plan == null) setBackgroundColor(placeholder)\n            addView(ProgressBar(context).apply {\n                isIndeterminate = true\n                indeterminateTintList = ColorStateList.valueOf(waitingColor)\n                contentDescription = context.getString(R.string.cluster_waiting_for_map)\n            }, FrameLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt(), Gravity.CENTER))\n        }\n'''
    replace_once(presentation, old_wait, new_wait, "Ultra-inspired cluster waiting screen")

    print("\nXingrui patch applied to DiPlay v0.2.20.")
    print("Build with: ./gradlew :mobile:assembleDebug")
    print("Note: ordinary source builds intentionally contain no MFi accessory identity.")


if __name__ == "__main__":
    main()

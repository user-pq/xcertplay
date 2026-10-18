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


def main() -> None:
    ap = argparse.ArgumentParser(description="Apply 2023 Geely Xingrui E02 cluster/wheel adaptation")
    ap.add_argument("repo", nargs="?", default=".", help="DiPlay repository root")
    args = ap.parse_args()
    repo = Path(args.repo).resolve()
    here = Path(__file__).resolve().parent

    host = repo / "common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt"
    presentation = repo / "common/src/main/java/com/shilapi/xcertplay/ClusterMapPresentation.kt"
    if not host.exists() or not presentation.exists():
        raise SystemExit("[FAIL] Not a supported DiPlay v0.2.20 tree")

    overlay = here / "overlay/common/src/main/java/com/shilapi/xcertplay"
    target = repo / "common/src/main/java/com/shilapi/xcertplay"
    for src in overlay.glob("*.kt"):
        shutil.copy2(src, target / src.name)
        print(f"[ok] overlay {src.name}")

    # IMPORTANT: keep upstream wired + wireless transport behavior intact.
    # AirPlayPersistence already defaults to wireless, while an attached iPhone may switch to USB.

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

    replace_once(host,
        "        if (!CarPlayMediaButton.opensSiri(event.keyCode)) return super.dispatchKeyEvent(event)\n",
        "        if (!CarPlayMediaButton.opensSiri(event.keyCode)) {\n"
        "            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) XingruiWheelDiagnostics.log(event)\n"
        "            return super.dispatchKeyEvent(event)\n"
        "        }\n",
        "log unclaimed Xingrui wheel keys")

    old_fallback = '''                return displays.firstOrNull { display ->\n                    val size = sizeOf(display)\n                    DiLink4ClusterDisplay.accepts(display.name, size.x, size.y)\n                }\n'''
    new_fallback = '''                val byd = displays.firstOrNull { display ->\n                    val size = sizeOf(display)\n                    DiLink4ClusterDisplay.accepts(display.name, size.x, size.y)\n                }\n                return byd ?: XingruiClusterProbe.find(context, displays.toList())\n'''
    replace_once(presentation, old_fallback, new_fallback, "add Xingrui Presentation fallback")

    old_wait = '''        waitingLabel = FrameLayout(context).apply {\n            if (plan == null) setBackgroundColor(placeholder)\n            addView(ProgressBar(context).apply {\n                isIndeterminate = true\n                indeterminateTintList = ColorStateList.valueOf(waitingColor)\n                contentDescription = context.getString(R.string.cluster_waiting_for_map)\n            }, FrameLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt(), Gravity.CENTER))\n        }\n'''
    new_wait = '''        waitingLabel = if (XingruiClusterProbe.isCandidate(display)) {\n            XingruiUltraPlaceholderView(context)\n        } else FrameLayout(context).apply {\n            if (plan == null) setBackgroundColor(placeholder)\n            addView(ProgressBar(context).apply {\n                isIndeterminate = true\n                indeterminateTintList = ColorStateList.valueOf(waitingColor)\n                contentDescription = context.getString(R.string.cluster_waiting_for_map)\n            }, FrameLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt(), Gravity.CENTER))\n        }\n'''
    replace_once(presentation, old_wait, new_wait, "Ultra-inspired cluster waiting screen")

    print("\nXingrui patch applied to DiPlay v0.2.20.")
    print("Wired and wireless CarPlay transports are both preserved; wireless remains the default.")


if __name__ == "__main__":
    main()

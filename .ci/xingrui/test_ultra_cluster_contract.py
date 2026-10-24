#!/usr/bin/env python3
"""Regression checks for Xingrui CarPlay Ultra second-display/DIM handoff."""
from pathlib import Path
import sys


def verify(root: Path) -> None:
    source = root / "common/src/main/java/com/shilapi/xcertplay"
    media = (root / "shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt").read_text()
    host = (source / "CarPlayHostActivity.kt").read_text()
    presentation = (source / "ClusterMapPresentation.kt").read_text()
    dim = (source / "XingruiDimProjection.kt").read_text()
    build = (root / "mobile/build.gradle.kts").read_text()

    # Stream 111 must not burn its one-shot first-frame event on the centre-screen fallback Surface.
    assert "val targetSurface = surfaces[type] ?: defaultSurface.takeIf { type == MAIN_SCREEN_TYPE }" in media
    assert "newVideoDecoder(type, targetSurface, startImmediately = false)" in media

    # A rendered ALT frame must be routed to the Xingrui Presentation/DIM readiness path.
    assert 'type == SCREEN_TYPE_ALT' in host
    assert 'stream=111 first frame on bound output' in host
    assert 'clusterPresentation?.xingruiVideoReady()' in host

    # Physical DashboardMapDisplay detection, not the legacy map preference, owns DIM startup.
    assert "if (XingruiClusterProbe.isCandidate(display)) {" in presentation
    assert "XingruiClusterProbe.isCandidate(display) && AirPlayPersistence.loadClusterMapEnabled(context)" not in presentation

    # An early first-frame callback must survive DIM-session construction/connect latency.
    for token in (
        "private var latestVideoReady = false",
        "latestVideoReady = ready",
        "it.video(latestVideoReady)",
        "AirPlayPersistence.loadClusterMapEnabled(app) || XingruiClusterProbe.find(app) != null",
    ):
        assert token in dim, token

    # This behavior change is intentionally distinguishable from R6.2 vehicle builds.
    assert 'versionCode = 47' in build
    assert 'versionNameSuffix = "-xingrui-r6.3-platform"' in build

    print("PASS: stream 111 waits for a real cluster Surface, first-frame readiness is latched, and DIM follows DashboardMapDisplay")


if __name__ == "__main__":
    verify(Path(sys.argv[1]))

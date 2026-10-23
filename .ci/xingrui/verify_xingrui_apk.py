#!/usr/bin/env python3
"""Validate the produced APK, not just Gradle/source strings. No authentication bytes are logged."""
import argparse
from pathlib import Path
import re
import struct
import subprocess
import zipfile


def verify(apk: Path, aapt2: str, package: str, version: str, code: int) -> None:
    badging = subprocess.check_output([aapt2, "dump", "badging", str(apk)], text=True)
    header = badging.splitlines()[0]
    for expected in (f"name='{package}'", f"versionName='{version}'", f"versionCode='{code}'"):
        if expected not in header:
            raise AssertionError(f"Wrong APK identity: expected {expected}")
    minimum = re.search(r"(?:minSdkVersion|sdkVersion):'(\d+)'", badging)
    if minimum is None or int(minimum[1]) > 28:
        raise AssertionError("APK does not declare Android 9 compatibility")
    manifest = subprocess.check_output([aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", str(apk)], text=True)
    if "android.uid.system" not in manifest:
        raise AssertionError("Platform variant lacks system UID declaration")
    for suffix in (".diagnostic-reports", ".update-apks"):
        if package + suffix not in manifest:
            raise AssertionError(f"Wrong provider authority: {suffix}")
    for permission in ("android.permission.CHANGE_COMPONENT_ENABLED_STATE", "android.permission.READ_LOGS", "android.permission.BLUETOOTH_PRIVILEGED",
                       "android.permission.TETHER_PRIVILEGED", "android.permission.RECEIVE_BOOT_COMPLETED",
                       "android.car.permission.CAR_SPEED", "android.car.permission.CAR_POWERTRAIN",
                       "android.car.permission.CAR_ENGINE_DETAILED", "android.car.permission.CAR_VENDOR_EXTENSION",
                       "android.cevt.dimsupportservice.permission.DIM_MESSAGING", "ecarx.oem.permission.OPENAPI_NAVI_PERMISSION"):
        if permission not in manifest:
            raise AssertionError(f"Missing permission declaration: {permission}")
    with zipfile.ZipFile(apk) as archive, apk.open("rb") as raw:
        if archive.testzip() is not None:
            raise AssertionError("ZIP CRC failure")
        names = archive.namelist()
        auth = [name in names for name in ("assets/offline-mfi/identity.pk8", "assets/offline-mfi/certificate.p7b")]
        if any(auth) and not all(auth):
            raise AssertionError("Partial authentication assets")
        if any("platform.pk8" in name for name in names):
            raise AssertionError("APK must not contain a platform signing private key")
        native = 0
        for info in archive.infolist():
            if info.filename.startswith("lib/") and info.filename.endswith(".so"):
                if info.compress_type != zipfile.ZIP_STORED:
                    raise AssertionError(f"Compressed native library: {info.filename}")
                raw.seek(info.header_offset)
                header_bytes = raw.read(30)
                offset = info.header_offset + 30 + sum(struct.unpack_from("<HH", header_bytes, 26))
                if offset % 4096:
                    raise AssertionError(f"Unaligned native library: {info.filename}")
                native += 1
        if not native:
            raise AssertionError("No native libraries")
        dex = b"".join(archive.read(name) for name in names if re.fullmatch(r"classes\d*\.dex", name))
        for marker in (b"XingruiDimSession", b"XingruiWheelSession", b"XingruiSdkContract", b"XingruiClusterGuard",
                       b"XingruiExitCoordinator", b"XingruiLegacyNavigationRecovery",
                       b"SENT_UNVERIFIED", b"xingruiWindowSnapshot", b"display-state.txt"):
            if marker not in dex:
                raise AssertionError(f"Missing production code: {marker.decode()}")
        if b"XingruiFixture" in dex or b"XingruiManualScheduler" in dex:
            raise AssertionError("Test fixtures leaked into APK")
    print(f"APK contract PASS: {package} {version} code={code}; native={native}; auth_pair={all(auth)}")
    print("CI validates packaging and declarations only; platform signing, granted permissions and physical output remain separate checks.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--aapt2", required=True)
    parser.add_argument("--package", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--version-code", type=int, required=True)
    args = parser.parse_args()
    verify(args.apk, args.aapt2, args.package, args.version, args.version_code)

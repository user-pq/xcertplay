#!/usr/bin/env python3
"""Add the Xingrui E02 optional fragrance-on-boot feature."""
from __future__ import annotations

import sys
from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        print(f"[ok] {label}: already applied")
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"[FAIL] {label}: expected one match in {path}, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    print(f"[ok] {label}")


def main() -> None:
    repo = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    common = repo / "common/src/main/java/com/shilapi/xcertplay"
    persistence = common / "AirPlayPersistence.kt"
    boot = common / "BootReceiver.kt"
    settings = common / "DiPlayActivity.kt"
    manifest = repo / "common/src/main/AndroidManifest.xml"

    replace_once(
        persistence,
        '    private const val KEY_AUTO_START_ON_BOOT = "auto_start_on_boot"\n',
        '    private const val KEY_AUTO_START_ON_BOOT = "auto_start_on_boot"\n'
        '    private const val KEY_FRAGRANCE_AUTO_START = "xingrui_fragrance_auto_start"\n',
        "add fragrance auto-start preference key",
    )

    replace_once(
        persistence,
        '''    fun saveAutoStartOnBoot(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_START_ON_BOOT, enabled)
            .apply()
    }
''',
        '''    fun saveAutoStartOnBoot(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_START_ON_BOOT, enabled)
            .apply()
    }

    fun loadFragranceAutoStart(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_FRAGRANCE_AUTO_START, false)

    fun saveFragranceAutoStart(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_FRAGRANCE_AUTO_START, enabled)
            .apply()
    }
''',
        "add fragrance auto-start preference accessors",
    )

    replace_once(
        boot,
        '''        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Starts the watcher for other apps' calls when that setting is on, even without opening DiPlay.
        PhoneAudioRoute.attach(context)
        val launchEnabled = AirPlayPersistence.loadAutoStartOnBoot(context)
        StartupDiagnosticSnapshot.received(context, launchEnabled)
        if (!launchEnabled) return

        val launch = Intent(context, DiPlayActivity::class.java).apply {
''',
        '''        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Starts the watcher for other apps' calls when that setting is on, even without opening DiPlay.
        PhoneAudioRoute.attach(context)
        val launchEnabled = AirPlayPersistence.loadAutoStartOnBoot(context)
        StartupDiagnosticSnapshot.received(context, launchEnabled)

        // Fragrance is independent of whether DiPlay itself auto-starts.
        if (AirPlayPersistence.loadFragranceAutoStart(context)) {
            val pendingResult = goAsync()
            XingruiFragranceAutoStart.ensureOnAsync(context.applicationContext) {
                pendingResult.finish()
            }
        }

        if (!launchEnabled) return

        val launch = Intent(context, DiPlayActivity::class.java).apply {
''',
        "trigger fragrance independently from DiPlay boot launch",
    )

    replace_once(
        manifest,
        '    <uses-permission android:name="android.car.permission.CAR_SPEED" />\n',
        '    <uses-permission android:name="android.car.permission.CAR_VENDOR_EXTENSION" />\n'
        '    <uses-permission android:name="android.car.permission.CAR_SPEED" />\n',
        "declare vehicle vendor-extension permission",
    )

    replace_once(
        settings,
        '''        section(content, "恢复", R.drawable.ic_dp_advanced) { card ->
''',
        '''        section(content, "香氛", R.drawable.ic_dp_advanced) { card ->
            toggle(
                card,
                "开机自动打开香氛",
                "车机开机后等待原车车辆服务就绪，再通过原车 CarFunction 接口打开香氛。",
                AirPlayPersistence.loadFragranceAutoStart(this),
            ) { enabled ->
                AirPlayPersistence.saveFragranceAutoStart(this, enabled)
            }
        }

        section(content, "恢复", R.drawable.ic_dp_advanced) { card ->
''',
        "add fragrance setting toggle",
    )

    replace_once(
        settings,
        '''                AirPlayPersistence.saveClusterMapEnabled(this, false)
                AirPlayPersistence.saveFpsCounter(this, false)
''',
        '''                AirPlayPersistence.saveClusterMapEnabled(this, false)
                AirPlayPersistence.saveFragranceAutoStart(this, false)
                AirPlayPersistence.saveFpsCounter(this, false)
''',
        "reset fragrance preference with Xingrui recommended settings",
    )

    print("Xingrui fragrance auto-start patch applied.")


if __name__ == "__main__":
    main()

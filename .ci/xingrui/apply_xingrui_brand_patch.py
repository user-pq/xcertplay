#!/usr/bin/env python3
from __future__ import annotations

import re
import shutil
import sys
from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"[FAIL] {label}: expected one match in {path}, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    print(f"[ok] {label}")


def regex_once(path: Path, pattern: str, replacement: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    new_text, count = re.subn(pattern, replacement, text, count=1, flags=re.DOTALL)
    if count != 1:
        raise SystemExit(f"[FAIL] {label}: expected one match in {path}, found {count}")
    path.write_text(new_text, encoding="utf-8")
    print(f"[ok] {label}")


def main() -> None:
    repo = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    here = Path(__file__).resolve().parent

    activity = repo / "common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt"
    host = repo / "common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt"
    manifest = repo / "mobile/src/main/AndroidManifest.xml"

    # Dedicated build: never show the generic DiLink / multi-car first-run wizard.
    old_launch = '''        page = savedInstanceState?.getString("page") ?: intent.getStringExtra("page")
            ?: if (setupError == null && SetupGuide.shouldOpenOnLaunch(SetupGuide.seen(this),
                    DiPlayPreferences.phoneAddress(this) != null)) "setup" else "home"
'''
    new_launch = '''        page = savedInstanceState?.getString("page") ?: intent.getStringExtra("page") ?: "home"
        // Xingrui E02 is a fixed target: the generic DiLink setup wizard is intentionally disabled.
        if (page == "setup") page = "home"
'''
    replace_once(activity, old_launch, new_launch, "disable generic DiLink setup wizard")

    # Rename the dedicated app consistently. Localized resources all use the same product name.
    renamed = 0
    for strings in list((repo / "common/src/main/res").glob("values*/strings.xml")) + [
        repo / "mobile/src/main/res/values/strings.xml"
    ]:
        if not strings.exists():
            continue
        text = strings.read_text(encoding="utf-8")
        next_text, count = re.subn(
            r'<string name="app_name">DiPlay</string>',
            '<string name="app_name">Car</string>',
            text,
        )
        if count:
            strings.write_text(next_text, encoding="utf-8")
            renamed += count
    if renamed == 0:
        raise SystemExit("[FAIL] rename app: no app_name resources changed")
    print(f"[ok] rename app to Car ({renamed} resources)")

    # Copy the Xingrui-specific launcher artwork and bundled license notice.
    overlay_root = here / "overlay"
    for relative in (
        Path("common/src/main/res/drawable/ic_xingrui_car.xml"),
        Path("common/src/main/assets/licenses/lucide-ISC.txt"),
    ):
        source = overlay_root / relative
        target = repo / relative
        if not source.exists():
            raise SystemExit(f"[FAIL] missing overlay asset: {source}")
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        print(f"[ok] overlay {relative}")

    replace_once(
        manifest,
        '        android:icon="@drawable/ic_carplay"\n',
        '        android:icon="@drawable/ic_xingrui_car"\n',
        "replace launcher icon",
    )
    replace_once(
        manifest,
        '        android:roundIcon="@drawable/ic_carplay"\n',
        '        android:roundIcon="@drawable/ic_xingrui_car"\n',
        "replace round launcher icon",
    )

    # Replace the very large generic three-finger settings sheet with an E02-only quick panel.
    quick_panel = r'''    private fun buildSettingsMenu(): View {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(SWIPE_SCRIM_COLOR)
            isClickable = true
            layoutDirection = resources.configuration.layoutDirection
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = swipeMenuChrome()
            clipToOutline = true
            elevation = dp(SWIPE_PANEL_ELEVATION_DP).toFloat()
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(SWIPE_CHROME_PADDING_DP), dp(SWIPE_CHROME_PADDING_DP),
                dp(SWIPE_CHROME_PADDING_DP), dp(SWIPE_CHROME_PADDING_DP))
            addView(menuText("星瑞 E02", 22f, MENU_PRIMARY, bold = true),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(DiPlayButton(this@CarPlayHostActivity).apply {
                text = "关闭"
                textSize = 16f
                minHeight = dp(SWIPE_ACTION_HEIGHT_DP)
                style(DiPlayButtonKind.SECONDARY, overlayPalette, overlay = true)
                setOnClickListener { leaveSettingsMenu() }
            })
        }
        panel.addView(header, LinearLayout.LayoutParams(-1, -2))

        var reconnectNeeded = false
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(SWIPE_CHROME_PADDING_DP), dp(18),
                dp(SWIPE_CHROME_PADDING_DP), dp(24))
        }

        val cluster = XingruiClusterProbe.find(this)
        content.addView(menuText(
            if (cluster != null) "仪表：${cluster.name} 已检测" else "仪表：未检测到 DashboardMapDisplay",
            16f,
            if (cluster != null) MENU_ACCENT else MENU_DANGER,
            bold = true,
        ))
        content.addView(menuText(
            "星瑞专版只保留本车需要的控制，不显示 DiLink、比亚迪、热点和通用高级选项。",
            14f, MENU_SECONDARY,
        ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        content.addView(settingsSwitchRow(
            label = "Ultra 仪表",
            checked = AirPlayPersistence.loadClusterMapEnabled(this),
            description = "使用 DashboardMapDisplay 显示 CarPlay 仪表画面；改变后点“应用并重连”。",
        ) { checked ->
            AirPlayPersistence.saveClusterMapEnabled(this, checked)
            reconnectNeeded = true
            appendLog("Xingrui quick panel: Ultra cluster=$checked")
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })

        content.addView(menuText("方控", 17f, MENU_PRIMARY, bold = true),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })
        content.addView(menuText(
            "E02 原始硬键适配 · 音量继续走原厂；上一首/下一首目标走 CarPlay；OK 原始码 306 已确认。",
            14f, MENU_SECONDARY,
        ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        content.addView(DiPlayButton(this).apply {
            text = "完整 Car 设置"
            textSize = 17f
            minHeight = dp(54)
            style(DiPlayButtonKind.SECONDARY, overlayPalette, overlay = true)
            setOnClickListener { leaveSettingsMenu { showDiPlayHome("settings") } }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(content, ViewGroup.LayoutParams(-1, -2))
        }
        settingsMenuContentScroll = scroll
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val apply = DiPlayButton(this).apply {
            text = "应用并返回 CarPlay"
            textSize = 17f
            minHeight = dp(SWIPE_ACTION_HEIGHT_DP)
            style(DiPlayButtonKind.PRIMARY, overlayPalette, overlay = true)
            setOnClickListener {
                leaveSettingsMenu {
                    if (reconnectNeeded) restartCarPlay("Xingrui quick settings changed")
                    else ensureClusterPresentation()
                }
            }
        }
        panel.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = swipeMenuChrome(bottomCornersOnly = true)
            setPadding(dp(SWIPE_CHROME_PADDING_DP), dp(SWIPE_CHROME_PADDING_DP),
                dp(SWIPE_CHROME_PADDING_DP), dp(SWIPE_CHROME_PADDING_DP))
            addView(apply, LinearLayout.LayoutParams(-1, -2))
        }, LinearLayout.LayoutParams(-1, -2))

        val width = settingsMenuWidth(resources.displayMetrics.widthPixels)
        val height = minOf(
            resources.displayMetrics.heightPixels * SWIPE_PANEL_HEIGHT_PERCENT / 100,
            (resources.displayMetrics.heightPixels - dp(SWIPE_PANEL_MARGIN_DP * 2)).coerceAtLeast(1),
        )
        overlay.addView(panel, FrameLayout.LayoutParams(width, height, Gravity.CENTER))
        return overlay
    }
'''
    regex_once(
        host,
        r"    private fun buildSettingsMenu\(\): View \{.*?\n    \}\n\n    private fun persistMenuSettings\(\)",
        quick_panel + "\n    private fun persistMenuSettings()",
        "replace three-finger menu with Xingrui quick panel",
    )

    print("Xingrui branding / quick-panel patch applied.")


if __name__ == "__main__":
    main()

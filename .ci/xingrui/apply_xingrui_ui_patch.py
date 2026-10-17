#!/usr/bin/env python3
from __future__ import annotations
import re
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
    host = repo / "common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt"
    presentation = repo / "common/src/main/java/com/shilapi/xcertplay/ClusterMapPresentation.kt"
    settings = repo / "common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt"

    replace_once(
        host,
        "        AirPlayPersistence.loadClusterMapEnabled(this) || XingruiClusterProbe.find(this) != null\n",
        "        AirPlayPersistence.loadClusterMapEnabled(this) && XingruiClusterProbe.find(this) != null\n",
        "make Ultra switch authoritative",
    )

    replace_once(
        presentation,
        "        root.addView(ClusterSpeedometerView(context), FrameLayout.LayoutParams(-1, -1))\n",
        "        root.addView(ClusterSpeedometerView(context), FrameLayout.LayoutParams(-1, -1))\n"
        "        if (XingruiClusterProbe.isCandidate(display)) {\n"
        "            root.addView(XingruiUltraTelemetryView(context), FrameLayout.LayoutParams(-1, -1))\n"
        "        }\n",
        "add Xingrui Ultra telemetry frame",
    )

    old_rail = '''    private fun settingsRail(): LinearLayout = column().apply {\n        background = rounded(SURFACE, BORDER)\n        setPadding(dp(12), dp(12), dp(12), dp(12))\n        SettingsCategory.entries.forEach { category ->\n            addView(settingsRailDestination(category), matchButton(0, 52).apply {\n                bottomMargin = if (category == SettingsCategory.entries.last()) 0 else dp(8)\n            })\n        }\n    }\n'''
    new_rail = '''    private fun settingsRail(): LinearLayout = column().apply {\n        background = rounded(SURFACE, BORDER)\n        setPadding(dp(12), dp(12), dp(12), dp(12))\n        val visible = listOf(SettingsCategory.OVERVIEW, SettingsCategory.ABOUT)\n        visible.forEachIndexed { index, category ->\n            addView(settingsRailDestination(category), matchButton(0, 52).apply {\n                bottomMargin = if (index == visible.lastIndex) 0 else dp(8)\n            })\n        }\n    }\n'''
    replace_once(settings, old_rail, new_rail, "trim settings rail")

    regex_once(
        settings,
        r"    private fun settingsCategoryContent\(content: LinearLayout\) \{\n.*?\n    \}\n\n    private fun settingsOverview",
        '''    private fun settingsCategoryContent(content: LinearLayout) {\n        when (settingsCategory) {\n            SettingsCategory.ABOUT -> about(content)\n            else -> {\n                settingsCategory = SettingsCategory.OVERVIEW\n                settingsOverview(content)\n            }\n        }\n    }\n\n    private fun settingsOverview''',
        "hide generic settings categories",
    )

    simple = r'''    private fun settingsOverview(content: LinearLayout) {
        settingsPageTitle(content, "星瑞 E02 设置", "无线 CarPlay 专版 · 仅保留日常需要的设置")

        val readiness = card().apply { setPadding(dp(20), dp(16), dp(20), dp(16)) }
        readinessCard = readiness
        renderedReadiness = null
        refreshReadiness()
        content.addView(readiness, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(SETTINGS_BLOCK_GAP_DP) })

        section(content, "连接", R.drawable.ic_dp_connection) { card ->
            card.addView(label("无线 CarPlay（固定）", 17, TEXT, true))
            card.addView(label("本版本不提供 USB CarPlay、热点模式和认证后端切换。", 14, MUTED).apply { setPadding(0, dp(4), 0, dp(10)) })
            toggle(card, "自动连接", "打开 DiPlay 后自动连接已配对 iPhone。",
                DiPlayPreferences.autoConnect(this)) { DiPlayPreferences.saveAutoConnect(this, it) }
            card.addView(button(chooseIphoneTitle(), false) { choosePhone() }, matchButton(10, 58))
        }

        section(content, "音质", R.drawable.ic_dp_audio) { card ->
            card.addView(label("无线音频缓冲越小延迟越低，越大越稳定。", 14, MUTED).apply { setPadding(0, 0, 0, dp(8)) })
            musicBufferChoice(card, wireless = true)
        }

        section(content, "画质", R.drawable.ic_dp_display) { card ->
            val presets = listOf(85, 100, 120)
            val current = AirPlayPersistence.loadDisplayScalePercent(this)
            val selected = when { current <= 92 -> 0; current >= 110 -> 2; else -> 1 }
            choice(card, "CarPlay 画质", listOf("流畅", "标准（推荐）", "高清"), selected) { index ->
                AirPlayPersistence.saveDisplayScalePercent(this, presets[index])
            }
        }

        section(content, "帧率", R.drawable.ic_dp_display) { card ->
            choice(card, "主屏帧率", listOf("30 FPS", "60 FPS"),
                if (AirPlayPersistence.loadFps(this) == 60) 1 else 0) { index ->
                AirPlayPersistence.saveFps(this, if (index == 1) 60 else 30)
            }
            card.addView(label("仪表投屏按星瑞仪表显示链路运行。", 14, MUTED).apply { setPadding(0, dp(8), 0, 0) })
        }

        section(content, "语音", R.drawable.ic_dp_audio) { card ->
            toggle(card, "回声消除", "用于 CarPlay 通话；出现通话回声时开启。",
                AirPlayPersistence.loadCallEchoCancellation(this)) {
                AirPlayPersistence.saveCallEchoCancellation(this, it)
                markReconnectNeeded()
            }
            toggle(card, "人声滤波", "减少通话中的低频噪声。",
                AirPlayPersistence.loadCallVoiceFilter(this)) {
                AirPlayPersistence.saveCallVoiceFilter(this, it)
                markReconnectNeeded()
            }
        }

        section(content, "方控", R.drawable.ic_dp_controls) { card ->
            card.addView(label("星瑞 E02 方控适配", 17, TEXT, true))
            card.addView(label("保留上一首、下一首、播放/暂停、音量与 Siri；未识别按键会写入 XingruiWheel 日志。", 14, MUTED).apply { setPadding(0, dp(5), 0, 0) })
        }

        section(content, "Ultra 仪表（实验）", R.drawable.ic_dp_dashboard) { card ->
            val detected = XingruiClusterProbe.find(this)
            card.addView(label(
                if (detected != null) "已检测到 ${detected.name}" else "未检测到可用仪表显示",
                14, if (detected != null) READY else WARNING,
            ).apply { setPadding(0, 0, 0, dp(8)) })
            toggle(card, "启用 Ultra 仪表", "连接后自动把 CarPlay 仪表视频流投到星瑞仪表；关闭后释放仪表并恢复原厂显示。",
                AirPlayPersistence.loadClusterMapEnabled(this), enabled = detected != null) { enabled ->
                AirPlayPersistence.saveClusterMapEnabled(this, enabled)
                reconnectIfRunning()
                render()
            }
            card.addView(label("Ultra 风格边框会尝试读取系统车辆属性中的真实车速、转速和挡位；无权限或无数据时显示“—”，不会伪造车辆数据。", 13, MUTED).apply { setPadding(0, dp(8), 0, 0) })
        }

        section(content, "恢复", R.drawable.ic_dp_advanced) { card ->
            card.addView(button("恢复原厂仪表", false) {
                AirPlayPersistence.saveClusterMapEnabled(this, false)
                reconnectIfRunning()
                render()
            }, matchButton(0, 56))
            card.addView(button("恢复星瑞推荐设置", false) {
                AirPlayPersistence.saveWirelessEnabled(this, true)
                DiPlayPreferences.saveAutoConnect(this, true)
                AirPlayPersistence.saveMediaBufferMillis(this, true, 300)
                AirPlayPersistence.saveDisplayScalePercent(this, 100)
                AirPlayPersistence.saveFps(this, 60)
                AirPlayPersistence.saveCallEchoCancellation(this, false)
                AirPlayPersistence.saveCallVoiceFilter(this, false)
                AirPlayPersistence.saveClusterMapEnabled(this, false)
                AirPlayPersistence.saveFpsCounter(this, false)
                reconnectIfRunning()
                render()
            }, matchButton(10, 56))
            card.addView(label("不会删除 iPhone 配对信息或 CarPlay 认证数据。", 13, MUTED).apply { setPadding(0, dp(8), 0, 0) })
        }
    }
'''
    regex_once(
        settings,
        r"    private fun settingsOverview\(content: LinearLayout\) \{\n.*?\n    \}\n\n    private fun currentReadiness",
        simple + "\n    private fun currentReadiness",
        "install Xingrui simple settings page",
    )

    replace_once(
        settings,
        "        val index = SettingsCategory.entries.associateByTo(linkedMapOf(), ::settingsCategoryTitle)\n",
        "        val visibleCategories = listOf(SettingsCategory.OVERVIEW, SettingsCategory.ABOUT)\n"
        "        val index = visibleCategories.associateByTo(linkedMapOf(), ::settingsCategoryTitle)\n",
        "limit settings search index",
    )
    replace_once(
        settings,
        "            for (category in SettingsCategory.entries.filter { it != SettingsCategory.OVERVIEW } + SettingsCategory.OVERVIEW) {\n",
        "            for (category in listOf(SettingsCategory.ABOUT, SettingsCategory.OVERVIEW)) {\n",
        "limit settings search categories",
    )

    print("Xingrui UI/Ultra patch applied.")


if __name__ == "__main__":
    main()

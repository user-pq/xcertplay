# CarPlay 星瑞方控补丁（2026-11-14）

按照用户最终选择，直接修改提供的 `com.ganfandw.carplay` APK。不是重新移植整套 CarPlay App。

## 输入和范围

- 参考 APK：1.4.1-20261105-20，versionCode 1401。
- 输入 SHA-256：`885dd544394c8d0e9f96050c090a4ec587217a57dca27d9808513d436ae41672`。
- 方控依据：`user-pq/xcertplay` 的 `build/xingrui-wireless-20261017`，提交 `b26c407296fac2d17567338655730877331489a1`；其中 `XingruiKeyRouter`、`XingruiWheelPresses`、`XingruiWheelSession`、`XingruiReflectiveInput`。
- 已移植：请求 SDK 动作码 200087/200088、原始按键 305/304 兜底、按住只发一次、消费已接管按键的松键、通话/蓝牙音频期间让行、退出释放回调、拒绝迟到回调和待发任务。
- 本次只接入证据明确的上一曲/下一曲。播放暂停、Siri、电话、音量、OK 等继续由参考 APK/原车原有逻辑处理，未声称移植旧版全部学习功能。

新入口先于原 APK 的 KeyEvent 分发。SDK 回调转换成标准媒体键 87/88 后，调用参考 APK 已有的 `requestMediaKey()`，复用其活动会话检查、HID 发送、全局切歌去重和音频焦点处理。参考 APK 的 OEM 媒体通路作为兼容路径保留；未删除其音频注册。新入口与原通路重叠时仍由原 `claimTrackChange()` 去重。

## APK 修改范围

只修改 `CarPlayHostActivity` 的四个方法入口：`startMediaGuard`、`stopMediaGuard`、`dispatchKeyEvent`、`onDestroy`，并增加独立的 `classes4.dex` 补丁类。

`AndroidManifest.xml`、资源、原生库、认证资产及其他 DEX 的载荷保持原样。`classes2.dex` 因重新汇编改变，旧签名文件移除后重新签名。打包校验包含逐个 ZIP 载荷比较、原生库页对齐和最终 v2/v3 签名校验。

## 签名

原 APK 的证书 SHA-256 是 `c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8`，与 [AOSP 官方公开开发证书](https://android.googlesource.com/platform/build/+/refs/heads/main/target/product/security/platform.x509.pem) 的 DER 指纹完全相同。

本地使用同一公开开发签名重新签署；没有更改原有系统 UID 和权限。`SignPlatformApk.java` 拒绝其他证书。任何签名输入、参考 APK、完整反编译代码、认证资产都不放进源码仓库或源码压缩包。签名通过只能证明 APK 完整性和证书一致；安装兼容性和方控效果仍需目标车机验证。

## 本地构建

需要 JDK 21、[Apktool 3.0.3](https://github.com/iBotPeaches/Apktool/releases/tag/v3.0.3)、JADX 1.5.6 的 all jar（内含 D8 9.1.31）。脚本校验输入 APK 和 Apktool 指纹；工作目录必须是新目录。

```sh
python3 patch_apk.py --input /path/to/CarPlay.apk \
  --output /path/to/CarPlay-wheel-unsigned.apk --work /path/to/new-scratch \
  --java-home /path/to/jdk --apktool /path/to/apktool_3.0.3.jar \
  --d8-classpath /path/to/jadx-1.5.6-all.jar
```

再用 Android apksig 8.9.1 和本地匹配的开发签名输入执行 `SignPlatformApk.java`。这是独立的本地签名步骤，不接受换证书伪装覆盖安装。

`src/` 只有补丁；`tests/` 是 JVM 假 SDK/宿主，绝不能打入 APK。GitHub Actions 编译补丁并运行生产桥接测试；完整 APK 重打包在本机进行，不把参考 APK/其认证资产上传到公开仓库。

## 验证和实车操作

生产桥接的 JVM 测试通过 44 项断言，包含重连时拒绝把旧按压发送给新会话，但没有连接目标车机进行运行验证。完整 CarPlay 功能和安装结果不能由这些测试推断。

1. 在车机保留原参考版，覆盖安装签名后的修改版；包名与版本号保持一致。安装失败时保留完整错误信息。
2. 保持原来已能连接的设置；确认媒体按键已启用，优先使用“车厂媒体接口”模式。
3. 停车后连接 CarPlay，逐个短按上一曲/下一曲，再按住一次，确认一次按压只切一首。
4. 检查音量、OK 和电话仍按原设置工作，退出 CarPlay 后原车切歌恢复。
5. 如无效，采集 `adb logcat -d -v threadtime -s XingruiWheelPatch:I MediaKeys:I`。`requested SDK actions` 只说明发起注册；`action=... forwarded=true` 只说明已交给原 APK，不能单独证明 iPhone 执行成功。

回退：覆盖安装原参考 APK；不必先卸载或删除已有配置。

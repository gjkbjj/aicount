# 项目协作约定

- 每次改动 Android 应用并成功构建调试 APK 后，检查 `adb devices -l`。若有一台已授权、状态为 `device` 的手机，直接使用 `adb -s <序列号> install -r <本次 APK>` 覆盖安装，并在回复中报告结果。若连接了多台设备，使用用户指定的手机。
- 使用 `AI记账<versionName>.apk` 命名应用安装包。
- 发布约定：本地安装包保持 `AI记账<versionName>.apk`；GitHub Release 仓库为 `gjkbjj/aicount`，tag 为 `v<versionName>`，附件必须复制为 `AIAccount-<versionName>.apk`。命令见 `开发文档.md` 第 8 节。只有明确「发版」才创建 Release。

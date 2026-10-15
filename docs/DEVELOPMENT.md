# 开发与验证

## 项目结构

| 路径 | 用途 |
| --- | --- |
| `app/src/main/java/edu/multisense/collector/` | 原生采集、权限、记录与导出 |
| `app/src/main/assets/` | APK 内的本地 HTML/CSS/JavaScript 界面 |
| `app/src/main/res/` | Android 图标、样式和数据备份规则 |
| `preview/` | 网页预览服务器与模拟数据适配器 |
| `tests/`、`scripts/test-storage.sh` | 可独立运行的 Java 存储回归测试 |
| `docs/images/` | 使用模拟数据生成的界面截图 |
| `outputs/MultiSense-debug.apk` | 当前课程试采用调试 APK |
| `gradle/wrapper/` | Gradle Wrapper，确保构建版本一致 |

`MainActivity` 提供本地 WebView 和 JavaScript 桥接。`CaptureService` 枚举并接收设备传感器与 GNSS 回调。`SessionRecorder` 将完整事件写入独立会话目录，并提供列表、回看、分页和 ZIP 导出。`SampleStats` 统计实际频率、间隔和异常；`SamplingPolicy` 定义逐类请求周期；`NetworkCollector` 管理 Wi-Fi / BLE / 蜂窝扫描及回调生命周期；`Csv` 负责 CSV 转义。

## 提交前验证

1. 用 JDK 17 执行 `./gradlew assembleDebug lintDebug`。
2. 按 README 下载 `org.json` 测试依赖，执行 `scripts/test-storage.sh`。
3. 修改界面时，运行 `python3 preview/server.py --port 8765`，检查手机和桌面布局，以及开始、标注、停止、回看与导出。
4. 修改采集逻辑时，在 Android 真机确认权限拒绝、缺失传感器、GNSS、Wi-Fi 节流、BLE、蜂窝、锁屏及后台记录行为。

网页预览使用模拟数据。存储测试与静态检查不能证明真机采集行为。

## 构建产物和隐私

本地 SDK 路径、Gradle 缓存、签名文件、环境配置及采集会话不提交到仓库。调试 APK 仅用于试采，不是应用商店发布包。更新源码后若需分发，请重新构建 APK 并更新 README 中的版本说明。

报告问题时提供 Android 版本、手机型号、操作步骤和错误提示。上传日志或数据前先移除真实轨迹、参考点、设备标识以及个人备注。

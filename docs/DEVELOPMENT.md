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
3. 修改界面时，执行下文浏览器回归测试，并运行 `python3 preview/server.py --port 8765` 检查手机和桌面布局，以及开始、标注、停止、回看与导出。
4. 修改采集逻辑时，在 Android 真机确认权限拒绝、缺失传感器、GNSS、Wi-Fi 节流、BLE、蜂窝、锁屏及后台记录行为。

网页预览使用模拟数据。存储测试与静态检查不能证明真机采集行为。

## 浏览器回归测试

需要 Node.js、Python 3 和 Playwright。测试通过请求拦截加载项目中的真实页面与适配器，无需启动服务器，只使用模拟数据。安装测试依赖后执行：

```sh
npm install --prefix .build/browser-test --no-save --package-lock=false playwright@1.62.1
.build/browser-test/node_modules/.bin/playwright install chromium
MULTISENSE_PLAYWRIGHT="$PWD/.build/browser-test/node_modules/playwright" node tests/browser-regression.cjs
```

也可用 `MULTISENSE_CHROME=/路径/Chrome` 指定已安装的浏览器可执行文件。测试覆盖旧版动作标注恢复、新版场景/动作区分、标签重命名及取消、切换确认及取消、手机姿态锁定、桌面和手机布局，以及超过 64 KB 的 ZIP 导出与 Python 完整解压验证。截图和模拟 ZIP 保存在忽略目录 `.build/browser-test/`。

## 发布调试 APK

版本 `1.1.2`（versionCode 4）沿用原发布签名，可以覆盖安装 `1.1.1` 并保留本机记录。维护者构建分发包时，通过 `MULTISENSE_DEBUG_KEYSTORE` 指定原有调试签名文件；签名文件、密码及本机路径不得提交。其他开发者的默认调试签名用于本机开发，不应替换仓库中的分发 APK。

```sh
MULTISENSE_DEBUG_KEYSTORE=/本机/原发布.keystore ./gradlew assembleDebug lintDebug
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs app/build/outputs/apk/debug/app-debug.apk
```

确认签名证书 SHA-256 为 `7955d8509e2a2d3f8cdda4c917683dc660eb2d150895f198ce6651d54e876b9e` 后，再复制到 `outputs/MultiSense-debug.apk`，并更新 `outputs/SHA256SUMS`。该值是公开证书指纹，不是签名密钥。分发前还应核对 APK 中的页面资源与当前源码一致。

## 构建产物和隐私

本地 SDK 路径、Gradle 缓存、签名文件、环境配置及采集会话不提交到仓库。调试 APK 仅用于试采，不是应用商店发布包。更新源码后若需分发，请重新构建 APK 并更新 README 中的版本说明。

报告问题时提供 Android 版本、手机型号、操作步骤和错误提示。上传日志或数据前先移除真实轨迹、参考点、设备标识以及个人备注。

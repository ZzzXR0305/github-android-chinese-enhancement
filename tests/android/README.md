# Android 真机检查

这套检查通过 `Instrumentation` 在增强版进程中验证 WebView 原位翻译、动态正文、恢复原文、双语切换、原生 TextView 与真实 Compose 渲染、公开仓库搜索，以及悬浮按钮的拖动、长按隐藏、5 秒驻留、页面识别、位置保存和旋转边界。原版搜索接入增强搜索的默认关闭、显式开启和排队后关闭也有检查。

需要 Python 3.9+、JDK 17、Android SDK platform 36 和 build-tools 36。设置 `JAVA_HOME` 与 `ANDROID_SDK_ROOT`，或使用 `--java-home`、`--sdk`。此工具直接编译自有测试源码，不需要官方 APK 或 Apktool，也不读取 `APKTOOL_JAR`。构建与签名复用 `tools/updates/build.py` 中的标准库工具函数。

提供增强版原来的外部签名密钥和别名，并在当前进程设置密码环境变量 `GITHUBCN_KEYSTORE_PASSWORD`、`GITHUBCN_KEY_PASSWORD`。可用 `--store-pass-env`、`--key-pass-env` 指定其他变量名；工具不打印密码，也不将签名材料写入仓库。

默认只构建与签名测试 APK，不访问设备：

```text
python -B tools/check-device.py --keystore /private/clone.jks --key-alias clone
```

输出保留在系统临时目录，包含 `instrumentation.apk` 和 `check-result.json`。可用 `--work-dir /private/new-check-directory` 指定一个尚不存在的仓库外目录。`--help` 列出工具链、签名及设备选项。

只有明确加入 `--run` 才会访问手机：

```text
python -B tools/check-device.py --keystore /private/clone.jks --key-alias clone --run --serial DEVICE_SERIAL
```

手机需要已允许 ADB，并已安装适配的 `com.github.android.chinese` 及同密钥的模型服务。模型应已下载；公开仓库搜索检查需要网络。工具只读取增强版安装路径和 APK 来核对签名，不读取账号、token 或应用私有数据。签名不一致或增强版未安装时停止，不安装测试包。

检查使用合成正文和临时登录 Activity 作为宿主，不登录账号。测试可临时点亮屏幕、保持测试窗口亮屏、在锁屏上显示合成内容并旋转宿主 Activity；不会解锁手机或修改系统熄屏时间，窗口退出后亮屏标志自动移除。测试修改的翻译、双语、悬浮位置及搜索开关设置会在清理时恢复。当前源码对应已验证的官方 `1.279.0` 适配；其他混淆版本需先更新适配与测试。

运行器的总截止时间是 180 秒；外层 ADB 最多等待 190 秒，留出启动与退出时间。首次语言模型下载也在此时限内。结果保存到仓库外的 `instrumentation-result.txt`，只有 `passed=true` 且 `RESULT_OK` 才返回成功。运行结束或检查失败后，工具只卸载临时测试包 `cn.local.githubcn.check`，不卸载增强版或模型服务，不清除其数据。

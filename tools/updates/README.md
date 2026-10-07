# 本地更新工具

当前只接受官方 GitHub **1.279.0 / versionCode 950**，默认补丁版本 **cn2**。新官方版本没有经过审核的 profile 时，工具停止并生成诊断；不会猜混淆类名、应用部分补丁、签名或安装。profile 同时检查原包签名、版本、Manifest、七个 hook 的方法与结构特征、Compose 反射成员、运行时包标识和完整 split 集合。

仓库提供自有补丁、Java/JS 源码和模型服务源码，不含官方 APK、反编译源码、签名密钥或账户资料。所有 APK、反编译缓存和签名文件应保留在仓库外；构建工具强制将工作目录及密钥放在仓库外。

## 工具链与签名

需要 Python 3.9+、JDK 17、Android SDK platform 36 / build-tools 36、用户自行准备的 apktool 3.0.3。从手机提取或安装时另需 ADB。设置 `JAVA_HOME`、`ANDROID_SDK_ROOT` 和 `APKTOOL_JAR`，也可用 `--java-home`、`--sdk`、`--apktool` 指定。`--adb` 和 `--serial` 可以选择 ADB 可执行文件及设备。

沿用已安装增强版的原签名密钥；首次创建自己的增强版时，可按 [Android 签名说明](https://developer.android.com/studio/publish/app-signing) 在仓库外创建密钥。模型服务与增强版必须使用同一密钥。不要用其他人的密钥替换已经使用的密钥，也不要为了更新先卸载增强版。

将密码放入当前进程的环境变量 `GITHUBCN_KEYSTORE_PASSWORD` 和 `GITHUBCN_KEY_PASSWORD`。工具把变量名交给 apksigner，不打印密码，不把密码写进仓库。可用 `--store-pass-env` 和 `--key-pass-env` 指定其他变量名。

## 构建

先通过 [官方 Google Play 页面](https://play.google.com/store/apps/details?id=com.github.android) 安装或更新原版 `com.github.android`。确认手机已经允许 ADB。以下命令只读取原版安装路径和 APK 文件，再在电脑上构建增强版；默认不安装。

```text
python -B tools/updates/build.py --from-device --keystore /private/clone.jks --key-alias clone --helper-apk /private/helper.apk
```

`--helper-apk` 是由仓库 `helper/` 源码构建的自有模型服务 APK。工具会用同一外部密钥为它签名。已经安装了同证书模型服务时，后续更新可省略此参数。

也可手动将官方 `base.apk` 和 `adb shell pm path com.github.android` 列出的所有 splits 放进仓库外的目录：

```text
python -B tools/updates/build.py --apks-dir /private/official-apks --keystore /private/clone.jks --key-alias clone
```

`--work-dir /private/new-build-directory` 可指定一个尚不存在的工作目录；默认使用系统临时目录。成功后该目录的 `signed/` 包含完整 APK 集合，`build-result.json` 记录版本、签名指纹和 APK 哈希；原始输入不会被改写。`--patch-version cn3` 或环境变量 `PATCH_VERSION` 可改变自有补丁版本，官方 `versionCode` 保持对应值。`--repository owner/repository` 设置应用内更新入口所访问的发布仓库。

构建会保留原 APK 的资源表和编译资源文件，仅修改资源包名及必要的账号类型 XML，并检查资源 ID 与引用。这样可保留 base 和 density split 之间的图标、颜色及 style 引用，避免 apktool 把未解析的跨包引用改成空值。

只有明确加上 `--install` 才会安装。工具先核对手机上已有增强版和模型服务的证书，以及增强版没有版本降级，再用 `adb install[-multiple] -r` 覆盖安装；不卸载、不清除数据、不读取账号或 token。相同官方版本可重新应用修复；后续官方版本应使用对应的更高 `versionCode`。Android 会保留同包同签名应用的数据；应用自身仍可能因为服务端策略要求重新登录。[Android 更新规则](https://developer.android.com/google/play/app-updates)、[ADB 安装说明](https://developer.android.com/tools/adb)

## 适配与检查

先在仓库外用 apktool 解码原版，再进行只读检查：

```text
python -B tools/updates/patch.py --decoded /private/decoded-base --profile tools/updates/profiles/github-1.279.0.json --report /private/preflight.json
```

省略 `--output` 时不写解码目录；指定 `--output /private/patched-base` 才创建独立的补丁目录。任一检查失配都会返回非零状态并列出缺失或重复的位置，原解码目录保持不变。

最小自动检查不需要官方 APK：

```text
python -B tools/updates/check.py
python -B -m unittest discover -s tools/updates/tests
```

新版本适配需要审核新的版本 profile、确认反射成员与资源标识，并实测启动、原位翻译、语言切换、恢复原文、动态内容、搜索和悬浮按钮。R8 会重命名、内联或合并代码，旧 profile 不能保证适配所有未来版本。[R8 官方说明](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization)

闭源原版的 Play 签名更新无法直接覆盖自签的 `com.github.android.chinese`。稳定流程是更新手机上的官方原版、提取完整官方 APK 集合、等待该版本 profile 支持、重新应用补丁，再沿用同一增强版密钥覆盖升级。工具不会从第三方 APK 镜像自动下载或替换官方输入。

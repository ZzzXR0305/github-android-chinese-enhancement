# 本地更新工具

当前只接受官方 GitHub **1.279.0 / versionCode 950**，本地默认补丁版本 **cn4**，当前本地产物 `final-cn4-5` 已通过两套 Instrumentation 及下述限定公开发行详情页的实际 GUI 验收。当前官方 `versionCode 952` 尚无对应 profile，不能盲套 950。新官方版本没有经过审核的 profile 时，工具停止并生成诊断；不会猜混淆类名、应用部分补丁、签名或安装。profile 同时检查原包签名、版本、Manifest、11 个 hook（涉及 7 个原版类）的方法与结构特征、Compose 反射成员、运行时包标识和完整 split 集合。`viewholders.f.c()` 与 `LegacyGitHubWebView.e()` 的 `beforeLoad` 接在实际 `WebView.loadUrl('javascript:github.load(...)')` 之前，保留 JavaScript URL 编码；两个 `afterLoad` 接入各自尾部 mention 样式 `evaluateJavascript`，在异步译文及双语重绘后回放，以保留高亮。

仓库提供自有补丁、Java/JS 源码和内置模型引擎源码，不含官方 APK、反编译源码、签名密钥或账户资料。官方 APK、反编译缓存及签名文件应保留在仓库外；构建工具强制将工作目录及密钥放在仓库外。`embedded-engine/app/build/` 是被忽略的本机构建输出，不提交容器 APK。

## 工具链与签名

需要 Python 3.9+、JDK 17、Android SDK platform 36 / build-tools 36、用户自行准备的 apktool 3.0.3。从手机提取或安装时另需 ADB。设置 `JAVA_HOME`、`ANDROID_SDK_ROOT` 和 `APKTOOL_JAR`，也可用 `--java-home`、`--sdk`、`--apktool` 指定。`--adb` 和 `--serial` 可以选择 ADB 可执行文件及设备。

沿用已安装增强版的原签名密钥；首次创建自己的增强版时，可按 [Android 签名说明](https://developer.android.com/studio/publish/app-signing) 在仓库外创建密钥。cn3/cn4 不安装独立模型服务，也不要求助手与增强版同签。不要用其他人的密钥替换已经使用的密钥，也不要为了更新先卸载增强版。

将密码放入当前进程的环境变量 `GITHUBCN_KEYSTORE_PASSWORD` 和 `GITHUBCN_KEY_PASSWORD`。工具把变量名交给 apksigner，不打印密码，不把密码写进仓库。可用 `--store-pass-env` 和 `--key-pass-env` 指定其他变量名。

## 构建

先取得 profile 支持的官方 `com.github.android` 及完整分包；[官方 Google Play 页面](https://play.google.com/store/apps/details?id=com.github.android) 的当前新版可能尚未适配，不要为了构建强行降级手机原版。确认手机已经允许 ADB。以下命令只读取原版安装路径和 APK 文件，再在电脑上构建增强版；默认不安装，设备原版不匹配现有 profile 时会停止。

先设置 `JAVA_HOME`、`ANDROID_SDK_ROOT`，使用 Gradle 9.4.1 构建内置容器：

```text
gradle -p embedded-engine :app:assembleDebug :app:lintDebug
```

产物为 `embedded-engine/app/build/outputs/apk/debug/app-debug.apk`。容器 APK 只作为增强 APK 内的 asset；隔离的 ML Kit 代码、Resources 和 native 库仍在同一增强应用 UID 中运行，绝不单独安装容器。完整说明见 [内置引擎](../../embedded-engine/README.md)。

```text
python -B tools/updates/build.py --from-device --keystore /private/clone.jks --key-alias clone --embedded-engine-apk embedded-engine/app/build/outputs/apk/debug/app-debug.apk
```

`--embedded-engine-apk` 每次构建都必填；旧 `--helper-apk` 参数已删除。`helper/` 仅保留旧版源码历史，cn3/cn4 不调用 `cn.local.repochinese`、不需要无障碍或独立同签助手。59 种语言模型按需在增强版 UID 下首次下载，需要能访问模型服务的 Wi-Fi；不会读取、复制或迁移旧助手私有模型。cn4 会在后台预热当前目标语言模型，但没有取得的页面内容无法预译；首次模型下载或未缓存的正文仍可能等待，不保证零延迟。

也可手动将官方 `base.apk` 和 `adb shell pm path com.github.android` 列出的所有 splits 放进仓库外的目录：

```text
python -B tools/updates/build.py --apks-dir /private/official-apks --keystore /private/clone.jks --key-alias clone --embedded-engine-apk embedded-engine/app/build/outputs/apk/debug/app-debug.apk
```

`--work-dir /private/new-build-directory` 可指定一个尚不存在的工作目录；默认使用系统临时目录。成功后该目录的 `signed/` 包含完整 APK 集合，`build-result.json` 记录版本、签名指纹和 APK 哈希；原始输入不会被改写。默认 `--patch-version cn4`，也可用该参数或环境变量 `PATCH_VERSION` 改变自有补丁版本，官方 `versionCode` 保持对应值。`--repository owner/repository` 设置应用内更新入口所访问的发布仓库。源码 `BuildInfo.ENGINE_SHA256` 是占位值，构建时会生成所提供引擎的实际 SHA-256 用于运行时校验。

构建会保留原 APK 的资源表和编译资源文件，仅修改资源包名及必要的账号类型 XML，并检查资源 ID 与引用。这样可保留 base 和 density split 之间的图标、颜色及 style 引用，避免 apktool 把未解析的跨包引用改成空值。

只有明确加上 `--install` 才会安装。工具先核对手机上已有增强版的证书，以及增强版没有版本降级，再用 `adb install[-multiple] -r` 覆盖安装完整增强 APK 集合；不安装独立引擎容器或助手、不卸载、不清除数据、不读取账号或 token。相同官方版本可重新应用修复；后续官方版本应使用对应的更高 `versionCode`。Android 会保留同包同签名应用的数据；应用自身仍可能因为服务端策略要求重新登录。[Android 更新规则](https://developer.android.com/google/play/app-updates)、[ADB 安装说明](https://developer.android.com/tools/adb)

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

### cn4 当前产物与验收状态

cn4 将原生正文先装入 detached DOM，译文准备完成后整页一次显示；缓存可在同页重载及新 WebView 间复用。限定的原生 `repository_description` TextView 增加 own-write guard 与同源 TextWatcher，同一原文被应用重绑时同步应用已有译文；Compose 保留原版渲染器接入。“译 / 搜 → 双语显示”按段原文在上、译文在下，默认关闭、持久化保存，切换模式复用纯译文，不为开关重新翻译。图片、代码、输入控件与原有可点击链接控件保留。

成功翻译缓存和页缓存各有 512 × 1024 字符上限；正文及缓存仅进程内保存，不写入持久化文件，不读取或持久化账号 token。ML Kit 下载的模型与这些正文缓存不同，仍按其原有机制管理。

`final-cn4-5` 两套 Android Instrumentation 均返回 `passed=true / RESULT_OK`，下述限定公开发行详情页的实际 GUI 验收也已通过。`RealRendererCheck` 用时 4526 ms，调用真实 `f.c() → loadUrl()` 13 次；12 段、13 个节点的模型已准备冷页，Java 等待 649 ms、动画帧首次完整译文 623 ms，英文/部分译文/显示后空帧均为 0。10 次缓存加载总回调耗时 `[44,63,33,34,20,11,12,10,10,12]` ms（10–63 ms），缓存页帧首次就绪 29 ms、无新增请求；新 WebView 总回调 90 ms、帧首次就绪 46 ms，缓存命中且新增请求为 0。冷页、缓存、双语三轮、原文恢复和跨 WebView 的 post-render 合成标记均回放成功。日志：`github-cn3-work/checks-cn4-5-atomic/instrumentation-result.txt`。这些是本设备合成样本测量，不包括首次模型下载；总回调含测试观测开销，不保证所有真实页面零延迟。

`RealRendererCheck` 不直接调用 `beforeLoad` 模拟入口，测试须在动画帧中采到完整译文，`firstReadyMs` 不可为 null。临时登录 Activity 无账号，仅有限豁免正文 load 后账号样式尾部精确抛出的 `IllegalStateException("no activity user returned")`，再用固定合成 `afterLoad` 标记验收生产回放包装；其他异常不豁免，标记检查不等同于真实账号的所有 mention 样式验收。完整 `ImmersiveCheck` 用时 37507 ms，通过原生同源重绑 10 次无需再次 attach、双语、精确恢复、缓存命中及字符上限、Compose、搜索、多语和按钮检查。日志：`github-cn3-work/checks-cn4-5-full/instrumentation-result.txt`。

`final-cn4-5` base APK SHA-256 为 `2140967513cbe3c820c819c7d95a1d4d32a836913dd71c3ed54f3c2af027b3d1`，与从已安装增强版拉回的 APK 相等。实际 GUI 已在公开 `microg/GmsCore` 的 `v0.3.17.252432` 发行详情页通过：纯中文正常；手动开启双语后整段灰色英文在上、中文在下，原链接保留；关闭双语后三次下滚至贡献者段仍为中文，再三次上滚回顶部与初始一致，未见英文回跳。最终为简体中文、双语关闭。仅证明该公开详情页的手动切换与滚动，不扩大到全部私人页面或所有账号样式。首次新语言模型下载仍需 Wi-Fi，内容尚未取得时不能预译；59 种语言目录不代表全部模型已逐一验证。

本机工作目录保留 `github-cn3-work/cn4-5-release-{single,bilingual,scrolled,return}.png` 与对应 XML；`verify-cn4.py` 两次返回 `PASSED`，`optimization-cn4-audit.json` 记录产物/已安装哈希、指标及四张截图 SHA。手机上的临时 QA 文件和测试 APK 已清理，不影响本机证据。

过程记录：`final-cn4-3` 合成检查曾通过，但实际 GUI 双语不生效，不可交付。旧 profile 误挂在 mention 尾部 `evaluateJavascript`，而当时合成检查直接调用 `beforeLoad`，未覆盖真实正文入口。`final-cn4-4` 修正到实际 `loadUrl` 入口后，真实 GUI 双语开/关及下滚通过；`final-cn4-5` 再补异步重绘后的 mention 高亮回放，通过上述两套检查及限定公开发行详情页 GUI 验收。另保留 `checks-cn4-full-1` 在新页驻留阶段因测试窗口失焦而失败的历史：`window_focus=false / timer_foreground=false`，未修改生产按钮逻辑或放宽断言，后续重跑通过。过程记录与当前产物的实际 GUI 验收分开记录。自有源码通过 [`v1.279.0-cn4`](https://github.com/ZzzXR0305/github-android-chinese-enhancement/releases/tag/v1.279.0-cn4) 公开；官方 APK、反编译源码、模型、账号数据、截图和签名密钥不在发行内容中。

### cn3 历史记录

cn3 修复版曾同签名 `-r` 覆盖安装，增强版 UID 保持不变；旧助手停用为 `disabled-user`。独立 `EmbeddedEngineCheck` 的 7 组检查曾通过（8184 ms），当时完整 `ImmersiveCheck` 返回 `passed=true / RESULT_OK`（38788 ms），覆盖 WebView、原生与真实 Compose 渲染、动态正文和恢复、代码保留、多语识别与模型、公开搜索、按钮拖动/长按/5 秒驻留/旋转。历史全量日志为 `github-cn3-work/checks-full-fix1/instrumentation-result.txt`。

cn3 旧版在真实 `GitHubWebView → viewholders.f` 中出现已注入但后加载正文未扫描的失败，不能以标准 WebView 合成测试掩盖。修复后的脚本通过 `poll()` 调用的闭包保留 Observer 引用，并检查、重绑当前根节点。当时 `RealRendererCheck` 在 4829 ms 内通过初译、原始 API 更换正文后的自动翻译、关闭恢复、重新开启及各阶段代码保留；历史日志为 `github-cn3-work/checks-real-complete/instrumentation-result.txt`。

### 运行器与实机验收边界

三个 runner 均通过 `tools/check-device.py --run --runner` 选择，默认是 `ImmersiveCheck`，也可选 `EmbeddedEngineCheck` 或 `RealRendererCheck`；三者运行结束或失败后都会自动清理临时测试包，不卸载增强版：

```text
python -B tools/check-device.py --keystore /private/clone.jks --key-alias clone --run --serial DEVICE_SERIAL --runner RealRendererCheck
```

历史 cn3 修复版 base APK SHA-256 为 `1be2c211f689892d85f5b40ce0ddc816fe87fbfbf2bdaa179c8977b93225dbe8`，该哈希不是 cn4 当前或最终产物哈希。当时临时测试 APK 已移除，另在用户账号内 `microg/GmsCore` 真实 README 页面确认中文、菜单关闭后的英文恢复及重新开启后的中文，三个阶段的 license 代码块字符串完全相同，旧助手保持停用。这是单独的实际页面验收，不代表所有账号页面均已验证。当时本机外部链接经用户直接回复“已跳转”确认已跳入增强版，并已只读核对 `com.github.android.chinese` MainActivity 和 `microg/GmsCore` README 页面；没有自动捕获源应用或完整启动链路，不替代 cn4 新排版的实际页面验收。

闭源原版的 Play 签名更新无法直接覆盖自签的 `com.github.android.chinese`。稳定流程是更新手机上的官方原版、提取完整官方 APK 集合、等待该版本 profile 支持、重新应用补丁，再沿用同一增强版密钥覆盖升级。工具不会从第三方 APK 镜像自动下载或替换官方输入。

不能伪造 GitHub 官方证书，也不能用增强版覆盖官方原包。cn3 历史验收中，系统用户选择将 `github.com` 受支持链接指向增强版，并关闭原版链接处理；命令级解析返回增强版 `DeepLinkAlias`，没有伪造域名 `verified` 状态或证书。当时本机外部链接经用户确认已跳入增强版，包与页面已核对；应用内自行处理的网页不一定交给系统路由，不保证所有内置 WebView 都会跳转。

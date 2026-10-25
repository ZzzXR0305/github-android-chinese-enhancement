# Android 真机检查

这套检查通过 `Instrumentation` 在增强版进程中验证 WebView 原位翻译、动态正文、恢复原文、双语切换、原生 TextView 与真实 Compose 渲染、公开仓库搜索，以及悬浮按钮的拖动、长按隐藏、5 秒驻留、页面识别、位置保存和旋转边界。原版搜索接入增强搜索的默认关闭、显式开启和排队后关闭也有检查。

检查在增强版 UID 后台反射调用 `LocalTranslation.call(Context, String, Bundle)`，不调用助手 Provider，并断言增强版未请求旧助手签名权限。当前本地默认版本为 cn4，仍仅适配官方 `1.279.0 / versionCode 950`；当前 profile 校验涉及 7 个原版类的 11 个 hook，包括原生 `viewholders.f.c()` 与 `LegacyGitHubWebView.e()` 实际 `loadUrl('javascript:github.load(...)')` 前的 `beforeLoad`，以及各自尾部 mention 样式脚本的两个 `afterLoad`。前者保留 JavaScript URL 编码，后者在延迟译文或双语重绘后回放原样式高亮。

## cn4 当前产物的检查结果（2026-10-25，final-cn4-5）

cn4 在原始 `github.load()` 显示正文前将内容放入 detached DOM 准备译文，完成后整页一次显示；动态内容和已缓存页面继续复用。限定的原生介绍 TextView 加入带 own-write guard 的 TextWatcher，相同原文被重复绑定时在当前 UI task 内同步贴回已有译文，不靠下一次 attach 救回。“译 / 搜 → 双语显示”按段原文在上、译文在下，默认关闭并持久化，切换后复用纯译文结果；关闭翻译仍精确恢复原文。图片、代码、输入控件与原有可点击链接控件保留。

`final-cn4-5` 两套 Android Instrumentation 均返回 `passed=true / INSTRUMENTATION_CODE: -1`，另已通过下述限定公开发行详情页的实际 GUI 验收。`RealRendererCheck` 用时 4526 ms，使用原版 `GitHubWebView → viewholders.f`、原始 assets 和原生高度桥，真实 `f.c() → WebView.loadUrl()` 共调用 13 次，不直接调用 `beforeLoad` 模拟接入，不手动 attach 或主动 poll 驱动扫描。日志为本机 `github-cn3-work/checks-cn4-5-atomic/instrumentation-result.txt`，结果包括：

- 12 段、13 个节点的模型已准备冷页整页一次显示：Java 等待就绪 649 ms、动画帧首次完整译文 `firstReadyMs=623`；英文帧、部分译文帧和显示后空帧均为 0。断言要求动画帧确实采到完整译文，`firstReadyMs` 不可为 null。
- 10 次同页缓存加载总回调耗时 `[44,63,33,34,20,11,12,10,10,12]` ms，即 10–63 ms；缓存页帧采样首次就绪 29 ms，新增翻译请求为 0。
- 新 WebView 复用页缓存，总回调 90 ms、帧首次就绪 46 ms，缓存命中且新增翻译请求为 0。
- 双语反复切换三轮不重复、原始 HTML 精确恢复、动态换正文后自动翻译，以及代码和链接地址保留。
- 冷页、缓存、双语、原文恢复和跨 WebView 的 post-render 合成标记回放均为 true。

临时 `SimplifiedLoginActivity` 宿主没有 activity user，真实 `f.c()` 在正文 `loadUrl` 之后的账号样式尾部可能精确抛出 `IllegalStateException("no activity user returned")`。fixture 仅对该阶段、类型及完整消息作有限豁免，其他异常仍导致失败；再通过固定合成 `afterLoad` 标记检查生产包装是否在整页替换、缓存及双语重绘后重放。该标记不是实际账号的 mention 样式数据，也不能代替所有账号样式的 GUI 验收。“冷页”仅表示该合成页面无页缓存，不是首次模型下载；总回调耗时包含测试观测开销，不是所有真实页面、所有语言或网络条件下的零延迟保证。

完整 `ImmersiveCheck` 返回 `passed=true / INSTRUMENTATION_CODE: -1`，用时 37507 ms，日志为 `github-cn3-work/checks-cn4-5-full/instrumentation-result.txt`。原生中文初译后重复 10 次 `view.setText(原文)`，同一 UI task 读回仍为中文且不再次 attach；双语原文/译文各一份，关闭、重开和英语原文恢复均通过；成功翻译缓存命中增加 3，`size <= maxSize`、上限 524288 字符；既有 WebView、真实 Compose、搜索、多语与按钮检查全部通过，设置在清理时恢复。

过程记录：`final-cn4-3` 合成测试曾通过，但真实 GUI 双语不生效，不可交付。根因是旧 profile 把 `beforeLoad` 误挂到正文之后的 mention `evaluateJavascript`，而旧 fixture 直接调用 `beforeLoad` 漏测真实入口；其通过、耗时及 APK 哈希不作为当前或最终验收依据。`final-cn4-4` 改到真实 `loadUrl` 后，实际 GUI 双语开/关与下滚通过。当前 `final-cn4-5` 增加重绘后的高亮回放，按上述真实 `f.c()` 路径完成两套测试，并另行通过限定公开发行详情页的 GUI 验收。

另保留首轮完整检查 `checks-cn4-full-1/instrumentation-result.txt` 的 `new-page dwell` 失败：当时 `window_focus=false / timer_foreground=false`，按钮按设计隐藏。未修改生产按钮逻辑或放宽断言，后续重跑通过；但快照没有抢焦点窗口身份，不能据此认定是 Native 翻译造成失焦，也不能把首轮失败表述为通过。

cn4 会后台预热当前目标模型，成功翻译缓存和页缓存分别限制为 512 × 1024 字符；正文及这些缓存仅在进程内，不落盘，不持久化 token。ML Kit 模型仍由其自身管理，第一次新语言模型需要可访问模型服务的 Wi-Fi 下载。尚未取得的内容无法提前翻译；59 种目录检查不等于已逐一下载验证全部语言模型。

`final-cn4-5` base APK SHA-256 为 `2140967513cbe3c820c819c7d95a1d4d32a836913dd71c3ed54f3c2af027b3d1`，与从已安装增强版拉回的 APK 比对一致。独立于 Instrumentation 的实际 GUI 已通过：公开 `microg/GmsCore` 的 `v0.3.17.252432` 发行详情页纯中文显示正常；手动开启双语后整段灰色英文在上、中文在下，原链接保留；关闭双语后连续三次下滚到贡献者段仍为中文，再连续三次上滚返回顶部与初始一致，未见英文回跳。最终停留简体中文、双语关闭。此结论仅覆盖该公开详情页的手动切换与滚动，不代表全部私人页面、所有账号样式或所有正文类型已验证。

本机证据为 `github-cn3-work/cn4-5-release-{single,bilingual,scrolled,return}.png` 与对应 XML，`optimization-cn4-audit.json` 保存产物/已安装哈希、测试指标及四张截图 SHA；`verify-cn4.py` 两次返回 `PASSED`。手机临时 QA 文件和测试 APK 已清理，本机证据保留。cn4 源码本轮仅保存在本地，尚未发布新公开 GitHub release。

## cn3 历史验证

2026-10-25 的 cn3 修复版曾同签名 `-r` 安装，增强版 UID 保持不变；旧助手处于 `disabled-user` 时，内置引擎、完整检查和真实原版渲染器检查均通过。以下 cn3 耗时、哈希与实际页面结果仅作历史记录，不代表 cn4 当前最终产物。

新增独立 runner `EmbeddedEngineCheck`：检查同 UID 的内置入口、59 种唯一语言及常用语言、英中互译、日语识别、重复调用和超过 24000 字符的拒绝结果；输出 `passed`、`checks`、`check_count`、`error_class`、`error_message` 等证据。它只使用合成字符串，不读取账户或旧助手私有模型。

cn3 当时发现并修复了合成标准 WebView 检查未覆盖的时序问题：旧版的真实 `GitHubWebView → viewholders.f` 已成功注入脚本，但正文后加载时 30 秒内仍为 `total=0`；追加/移除节点也未恢复，切换翻译开关强制扫描后才翻译。原先一次性绑定 MutationObserver 的方式不可靠。脚本在 `poll()` 中检查并重绑当前根节点，并通过 `poll()` 调用的闭包保留 Observer 引用；本机 Node 回归还覆盖首次没有 HTML 根、随后加载正文，以及已有条目后更换整个根节点。

新增 `RealRendererCheck` 使用原版 `GitHubWebView` 容器、真实 `viewholders.f`、原始 `markdown.js` 和原生高度桥。测试不手动调用 `Immersive.attach()`，不主动调用 `poll()` 驱动扫描，而是通过原始 `github.load()` 装入合成正文，被动观察自动翻译。初次修复检查在 3272 ms 内通过；最终扩展检查在 4829 ms 内完成初次自动翻译、原始 `github.load()` 更换正文后的自动翻译、关闭后恢复英文、重新开启后恢复中文，并在每个阶段确认代码保持不变，返回 `passed=true / INSTRUMENTATION_CODE: -1`。日志位于本机工作目录 `github-cn3-work/checks-real-complete/instrumentation-result.txt`。

cn3 修复后的完整 `ImmersiveCheck` 返回 `passed=true / INSTRUMENTATION_CODE: -1`，耗时 38788 ms，覆盖 WebView 原位及动态翻译/还原、代码保留、原生介绍与原始 Compose 渲染器、59 语言目录、英德法识别、中英双向及英法模型、实际 `codex++` API 搜索、搜索接入开关，以及按钮拖动/长按/5 秒驻留/页面识别/Activity 旋转。日志位于 `github-cn3-work/checks-full-fix1/instrumentation-result.txt`；当时测试 APK 已移除。该历史 cn3 base APK SHA-256 为 `1be2c211f689892d85f5b40ce0ddc816fe87fbfbf2bdaa179c8977b93225dbe8`，不是 cn4 当前或最终产物哈希。此前独立引擎 7 组检查曾以 8184 ms 通过，但旧标准 WebView 全量结果不再作为真实渲染器已接通的依据。

## 构建和运行检查

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

手机需要已允许 ADB，并已安装带内置引擎的适配版 `com.github.android.chinese`。不需要独立 `cn.local.repochinese`、无障碍服务或同签名模型助手；`embedded-engine` 容器只能打包进增强 APK，不能单独安装。首次模型下载发生在增强版 UID 下，需要可访问模型服务的 Wi-Fi，不迁移助手私有模型；公开仓库搜索检查也需要网络。工具只读取增强版安装路径和 APK 来核对签名，不读取账号、token 或应用私有数据。签名不一致或增强版未安装时停止，不安装测试包。

`tools/check-device.py --run` 默认执行完整 `ImmersiveCheck`。`--runner` 可选 `ImmersiveCheck`、`EmbeddedEngineCheck` 或 `RealRendererCheck`，三者都由工具在结束或失败后清理临时测试包。例如运行真实原版渲染器检查：

```text
python -B tools/check-device.py --keystore /private/clone.jks --key-alias clone --run --serial DEVICE_SERIAL --runner RealRendererCheck
```

运行独立引擎检查时将 `--runner` 改为 `EmbeddedEngineCheck` 即可。验证独立性时应记录旧助手原状态，在其已停用或未安装的条件下运行引擎、完整与真实渲染器检查；不要为测试读取助手私有文件或卸载增强版，并恢复为验收暂改的助手状态。

检查使用合成正文和临时登录 Activity 作为宿主，不登录账号。测试可临时点亮屏幕、保持测试窗口亮屏、在锁屏上显示合成内容并旋转宿主 Activity；不会解锁手机或修改系统熄屏时间，窗口退出后亮屏标志自动移除。测试修改的翻译、双语、悬浮位置及搜索开关设置会在清理时恢复。当前仅有官方 `1.279.0 / versionCode 950` 的 profile，官方 `952` 不可盲套；其他混淆版本需先更新适配与测试。59 种目录检查不代表逐一下载并验证全部语言模型。

完整运行器的总截止时间是 180 秒；所有 `--runner` 的工具外层 ADB 最多等待 190 秒，留出启动与退出时间。首次语言模型下载也受此次运行的时限约束。工具结果保存到仓库外的 `instrumentation-result.txt`，只有 `passed=true` 且 `RESULT_OK` 才返回成功。运行结束或检查失败后，工具只卸载临时测试包 `cn.local.githubcn.check`，不卸载增强版、不清除其数据。

上述 runner 仍使用合成正文，不等同于真实已登录仓库页面验收。cn3 历史验收另在用户账号内的 `microg/GmsCore` README 实际页面观察到中文，使用真实菜单关闭后恢复英文、重新开启后恢复中文；三阶段读到的 license 代码块字符串完全相同，旧助手始终停用。这一实际页面结果与合成渲染器检查分开记录，不代表所有私人仓库、全部正文类型或 cn4 新排版均已验证。

cn3 历史验收中，系统用户选择把 `github.com` 受支持链接指向增强版并关闭原版链接处理，默认解析返回增强版 `DeepLinkAlias`。未伪造官方证书、官方包身份或域名 `verified` 状态，也没有覆盖官方原包。当时本机外部链接经用户直接回复“已跳转”确认已跳入增强版，并已只读核对 `com.github.android.chinese` MainActivity 和 `microg/GmsCore` README 页面。该结论包含用户确认与当时包/页面核对，不是自动捕获了源应用或完整启动链路，也不保证所有应用的内置 WebView 都会交给系统路由。

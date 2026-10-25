# GitHub Android Chinese Enhancement

为 GitHub Android 加入正文原位翻译、仓库名称增强搜索与可移动工具按钮。

## 功能

- README 和讨论主帖、评论、回复在原页面替换译文；动态正文自动处理，关闭后恢复原文。cn4 在原生正文加载前接入：先在脱离页面的 DOM 中准备整页译文，再一次显示，避免逐段闪回原文；已有页缓存可在再次加载或新 WebView 中复用。
- 项目介绍支持原版 Compose 渲染器和原生 TextView。cn4 为限定的原生介绍控件增加同源回写监听，应用重复绑定相同原文时同步重贴已有译文，无需等待下次扫描。图片、代码、输入控件、链接地址与原有可点击控件保留。
- “译 / 搜 → 双语显示”可切换原文与译文，按段原文在上、译文在下，保留原有可点击链接；默认关闭并保存选择。关闭双语后只显示译文，关闭翻译后恢复原文。切换显示模式复用纯译文结果，不为模式切换重新翻译。
- 英文正文提供 59 种语言选项：58 种翻译目标及英语原文，包含中、日、韩、法、德、西、葡、俄、阿拉伯、印地等语言。cn3 将 ML Kit 引擎、资源与 native 库内置在同一个增强 APK，使用隔离类加载器在增强版 UID 中运行，不依赖独立“仓库中文助手”、无障碍服务或同签名翻译服务。语言模型按需下载；增强版首次准备模型需要可访问模型服务的 Wi-Fi，不读取或迁移旧助手的私有模型。[官方语言列表](https://developers.google.com/ml-kit/language/translation/translation-language-support)
- 内置仓库搜索统一 `codex++`、`codex plus plus`、`CodexPlusPlus` 与单 `plus` 写法；按名称相似度排序并展示 owner/name，保留高级查询。
- 增强搜索可开启多语言互译：例如 `马尾辫skill` 补搜 `ponytail skill`，合并原关键词与互译关键词的结果。来源和补搜语言均支持 59 种选项，可自动识别或手选；来源短词识别不准时可手选。默认英语补搜中文，其他来源补搜英语。开关与语言选择会保存；高级限定符、仓库链接及 plus 别名不互译。普通搜索最多发送两次公开 REST 请求。
- 原版搜索默认保持原入口。工具菜单可开启或关闭“原版搜索接入增强搜索”；关闭时不会自动跳转，也可以从“译 / 搜”菜单单独打开增强搜索。
- “译 / 搜”按钮可拖动并记住位置；长按隐藏，当前页面继续停留 5 秒后重新显示。切换页面与进入后台重置驻留计时；工具菜单可以立即恢复按钮。
- 更新页面检查增强版发行信息，并保留官方 GitHub 的商店更新入口。

cn4 在后台预热当前目标语言模型，并提供各自上限为 512 × 1024 字符的成功翻译缓存和页缓存；正文及缓存仅保存在进程内，不落盘，进程退出后需重新建立。模型自身仍由 ML Kit 管理。第一次使用新语言需要可访问模型服务的 Wi-Fi 下载；尚未取得的页面内容无法预译，首次页面也仍需准备时间，不保证零延迟或所有网络条件下的即时显示。

这是独立的社区补丁项目。仓库包含我们新增的代码及工具；GitHub 官方 Android APK、反编译代码、账号数据、翻译模型和签名私钥不在仓库内。需要从自己的设备提供已安装的官方 APK。

## 目录

```text
payload/          新增 Java 与 DOM 处理代码
embedded-engine/  打包进增强 APK 的隔离 ML Kit 构建容器，不单独安装
helper/           旧版独立助手源码，仅保留历史，不是 cn3/cn4 安装依赖
tools/updates/    从官方 APK 提取、预检、应用补丁、构建与签名
tests/            无额外测试框架的 Java、Node 与 Python 检查
.github/workflows/  开源代码持续检查
```

## 适配版本与验收状态

当前 profile 仅适配官方 GitHub **1.279.0 / versionCode 950**，本地默认增强补丁 **cn4**。当前发现的官方 `versionCode 952` 尚无对应 profile，不能盲套 950 的接入点；使用未知版本输入时工具会停止。当前 cn4 profile 校验 11 个 hook、涉及 7 个原版类：在 `viewholders.f.c()` 与 `LegacyGitHubWebView.e()` 实际 `WebView.loadUrl('javascript:github.load(...)')` 前接入 `beforeLoad`，保留 JavaScript URL 编码；另有两个 `afterLoad` 接入尾部 mention 样式脚本，供异步译文及双语重绘后回放，避免丢失原有高亮。

### cn4 验证与发行（2026-10-25）

当前本地产物为 `final-cn4-5`，两套 Android Instrumentation 及下述限定公开发行详情页的实际 GUI 验收均通过，不代表全部私人页面已验证。`RealRendererCheck` 总耗时 4526 ms，真实 `f.c() → loadUrl()` 调用 13 次；模型已准备的冷页含 12 段、13 个节点，Java 等待整页就绪 649 ms、动画帧首次完整译文 623 ms，采样英文帧、部分译文帧和显示后空帧均为 0。10 次缓存加载总回调耗时 10–63 ms，缓存页帧首次就绪 29 ms、无新增翻译请求；新 WebView 总回调 90 ms、帧首次就绪 46 ms，缓存命中且新增请求为 0。这些是本设备合成样本结果，不含首次模型下载，总回调时间包含测试观测开销，不保证所有真实页面零延迟。

`RealRendererCheck` 不再直接调用 `beforeLoad` 代替原生接入，并要求动画帧采样确实出现完整译文、`firstReadyMs` 非空。冷页、缓存、双语三轮、原文恢复及跨 WebView 的 post-render 合成标记回放均通过。临时登录 Activity 没有账号，仅对原生加载后账号样式尾部的精确 `IllegalStateException("no activity user returned")` 作有限豁免；合成标记不等于已验证真实账号的所有 mention 样式。完整 `ImmersiveCheck` 用时 37507 ms，通过原生介绍同源回写 10 次无需再次 attach、双语、原文恢复、缓存命中及边界、Compose、搜索、多语与按钮检查。两套日志分别为本机 `github-cn3-work/checks-cn4-5-atomic/instrumentation-result.txt` 与 `github-cn3-work/checks-cn4-5-full/instrumentation-result.txt`。

`final-cn4-5` base APK SHA-256 为 `2140967513cbe3c820c819c7d95a1d4d32a836913dd71c3ed54f3c2af027b3d1`，与从已安装增强版拉回的 APK 比对一致。实际 GUI 已在公开 `microg/GmsCore` 的 `v0.3.17.252432` 发行详情页验收：纯中文显示正常；菜单开启双语后整段灰色英文在上、中文在下，原有链接保留；关闭双语后三次下滚到贡献者段仍为中文，再三次上滚回顶部与初始显示一致，未见英文回跳。最终停留简体中文、双语关闭。此结论只覆盖该公开详情页的手动切换与滚动，不扩大到所有私人页面或所有账号样式。

本机证据为 `github-cn3-work/cn4-5-release-{single,bilingual,scrolled,return}.png` 及对应 XML；`verify-cn4.py` 返回 `PASSED`，`optimization-cn4-audit.json` 记录产物、已安装 APK 哈希、指标和四张截图 SHA。上述本机证据不公开；自有源码通过 [`v1.279.0-cn4`](https://github.com/ZzzXR0305/github-android-chinese-enhancement/releases/tag/v1.279.0-cn4) 公开，发行内容不含官方 APK、反编译源码、模型、账号数据、截图或签名密钥。

过程记录：`final-cn4-3` 的合成检查曾通过，但真实 GUI 双语不生效，不能交付。随后查明旧 profile 把入口误挂在正文加载之后的 mention `evaluateJavascript`，而旧合成测试直接调用 `beforeLoad` 漏掉了该问题；其耗时和哈希均不是最终交付证据。`final-cn4-4` 修正到真实 `loadUrl` 入口后，实际 GUI 的双语开/关与下滚已通过；当前 `final-cn4-5` 补齐异步重绘后的高亮回放，并通过上述两套测试及限定公开发行详情页 GUI 验收。另保留 cn4 首轮完整检查的窗口失焦失败：`checks-cn4-full-1` 的 `new-page dwell` 超时快照为 `window_focus=false / timer_foreground=false`；未修改生产按钮逻辑或放宽断言，后续重跑通过。这些过程记录与当前产物的实际 GUI 验收分开记录。

### cn3 历史验证

2026-10-25，`1.279.0-cn3` 修复版曾沿用原增强版签名以 `-r` 覆盖安装，增强版 UID 保持不变，没有卸载或清除数据。旧助手处于 `disabled-user` 状态时，独立 `EmbeddedEngineCheck` 的 7 组检查曾通过（8184 ms）；当时完整 `ImmersiveCheck` 返回 `passed=true / RESULT_OK`（38788 ms）。该轮覆盖内置引擎同 UID 后台调用、WebView 动态正文及原文恢复、代码保留、真实 Compose 与原生介绍、中英互译、英德法识别、英法模型、59 种语言目录、实际 `codex++` 公开搜索，以及按钮拖动、长按隐藏、5 秒驻留和真实 Activity 旋转。

此前真实 `GitHubWebView → viewholders.f` 暴露出正文后加载而未自动扫描的问题，不能用合成标准 WebView 的通过结果替代。修复通过 `poll()` 调用的闭包保留 MutationObserver 引用并检查、重绑当前根节点。新增 `RealRendererCheck` 不手动 attach 或主动 poll，使用原版容器、原始 assets 和 `github.load()`，在 4829 ms 内通过初译、换正文自动翻译、关闭恢复、重新开启与各阶段代码保留检查。

该 cn3 历史产物的 base APK SHA-256 为 `1be2c211f689892d85f5b40ce0ddc816fe87fbfbf2bdaa179c8977b93225dbe8`，不是 cn4 当前或最终产物哈希。当时另在用户账号内的 `microg/GmsCore` 真实 README 页面验收了中文显示、菜单关闭恢复英文、重新开启恢复中文；三阶段 license 代码块字符串完全相同，旧助手保持停用。这与合成测试分开记录，不代表所有账户页面均已验证。当时本机外部链接经用户直接回复“已跳转”确认已跳入增强版，并已只读核对 `com.github.android.chinese` MainActivity 及 `microg/GmsCore` README 页面；没有自动捕获源应用或完整启动链路，也不能代替 cn4 新排版的实际页面验收。

旧 cn2 曾在 Huawei nova 11 / Android 12 / arm64 上检查 WebView 动态正文及恢复、真实 Compose 与原生介绍、公开搜索、中英互译、英德法识别、英法模型、按钮拖动与 5 秒驻留、Activity 旋转及搜索接入开关；当时仍依赖外置模型助手。检查使用合成正文和公开仓库，没有登录私人账户或验收该账户的全部仓库页面。59 种语言目录不代表已逐一下载、验证全部语言模型。

搜索通过公开 GitHub REST API 获取仓库，受其请求限额约束；返回原版搜索可以使用其他搜索类型。搜索不会读取或导出账号令牌。

## 构建

需要 Python 3、JDK 17、Android SDK build-tools 36 / platform 36、Node.js、apktool 3.0.3，以及已授权的 ADB 设备。内置引擎构建另需要 Gradle 9.4.1 / Android Gradle Plugin 9.2.0。

先构建 `embedded-engine/`，再向更新工具提供必填参数 `--embedded-engine-apk embedded-engine/app/build/outputs/apk/debug/app-debug.apk`。这个 APK 只是嵌入构建容器，绝不单独安装；旧 `--helper-apk` 参数已删除。

工具链路径通过 `JAVA_HOME`、`ANDROID_SDK_ROOT` 和 `APKTOOL_JAR` 指定。沿用增强版原签名文件，保存在仓库外，密码通过环境变量提供；不再需要为独立模型助手安排同签名安装。

具体命令、版本适配及检查流程见 [更新工具说明](tools/updates/README.md)。不要将原版 APK 或签名文件提交到仓库。

## Updates

官方原版 `com.github.android` 继续从 [Google Play](https://play.google.com/store/apps/details?id=com.github.android) 更新，增强版 `com.github.android.chinese` 保持独立。Android 覆盖升级要求相同应用 ID、兼容签名及不降低版本号，官方签名的 APK 无法直接覆盖本机签名的增强版。[Android 更新机制](https://developer.android.com/google/play/app-updates)

官方原版更新后：连接电脑 → 使用更新工具提取当前官方分包 → 等待对应 profile 并校验全部接入点 → 构建内置引擎及完整增强版 → 用保存的签名以 `-r` 覆盖安装。正常同包同签名覆盖安装保留增强版登录、设置和按钮位置；不要为了升级先卸载。

目前只保证已验证 profile。GitHub 的混淆名称和渲染结构可能随新版变化；未知版本会生成诊断并停止，更新适配 profile 通过检查后才生成安装包。工具不会安装缺少翻译或搜索接入点的部分修改版，也不代表已经验证尚未发布的官方版本。

增强版不能伪造 GitHub 官方证书，也不能覆盖官方原包。cn3 历史验收中，系统用户选择将 `github.com` 受支持链接指向增强版，关闭原版对此类链接的处理；命令级解析返回增强版 `DeepLinkAlias`。这不是伪造域名 `verified` 状态或官方身份。当时本机外部链接经用户确认已跳入增强版，包与页面已核对；这不保证所有应用都会交给系统处理其内置网页。

## 检查

Node 检查覆盖 DOM 原位替换、动态正文、代码保留、原文恢复、正文刷新与重试时的旧结果取消、整页准备及双语缓存，以及 Observer 按实际目标触发、首次缺根后加载和整个根节点更换。Java 检查覆盖搜索名称、互译术语、59 种语言全集、词典按目标语言处理、原生译文显示派生、页面 5 秒驻留和版本比较；Python 检查覆盖 11 个 hook 的补丁预检、失败不改写和原版资源保留。

GitHub Actions 不需要原版 APK 或账号凭据。真实 APK 与真机检查在本地进行。

[tests/android/](tests/android/README.md) 包含 `ImmersiveCheck`、`EmbeddedEngineCheck` 和 `RealRendererCheck` 三个 runner。`final-cn4-5` 的两套最终日志与限定公开发行详情页 GUI 通过范围见上述记录；cn4 中间版本和 cn3 的 `checks-full-fix1`、`checks-real-complete` 只作过程或历史依据。runner 使用合成正文及公开仓库，不读取账号数据；测试会恢复翻译、双语、按钮位置与搜索接入设置。`tools/check-device.py` 可用外部签名密钥构建，只有显式 `--run` 才访问设备，可用 `--runner` 选择检查；所有 runner 结束或失败后都自动移除临时测试 APK，不卸载增强版。真实账号内 README 与外部跳转的 cn3 历史验收范围已另列，不作为 cn4 所有页面与新排版均已验收的证明。

## 发布

cn4 已获维护者授权并通过公开仓库的 `v1.279.0-cn4` 发行。后续版本继续使用以下审计与发布流程。

发布自有补丁及内置引擎源码，原版 GitHub 内容留在本机。稳定签名文件应保存好并备份在仓库外，后续覆盖更新依赖它。[Android 应用签名](https://developer.android.com/studio/publish/app-signing)

新增代码使用 [MIT License](LICENSE)。ML Kit、jsoup 和 Android 工具链遵循各自的许可证；本项目的 MIT 许可不扩展到 GitHub 官方应用。

仓库维护者可在源码提交并检查后，通过已登录的 [GitHub CLI](https://cli.github.com/) 发布：`python tools/publish.py --check` 先校验登录与源码，`python tools/publish.py` 创建公开仓库、推送 main 并建立源码发行页。脚本核对当前账号与预设维护者，不覆盖其他 origin，也不上传 APK 或密钥。

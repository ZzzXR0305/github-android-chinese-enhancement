# GitHub Android Chinese Enhancement

为 GitHub Android 加入正文原位翻译、仓库名称增强搜索与可移动工具按钮。

## 功能

- README 和讨论主帖、评论、回复在原页面替换译文；动态正文自动处理，关闭后恢复原文。
- 项目介绍支持原版 Compose 渲染器和原生 TextView；代码、输入控件和链接地址保留。
- 英文正文提供 59 种语言选项：58 种翻译目标及英语原文，包含中、日、韩、法、德、西、葡、俄、阿拉伯、印地等语言。ML Kit 在本机处理正文，语言模型按需下载，首次准备模型需要 Wi-Fi。[官方语言列表](https://developers.google.com/ml-kit/language/translation/translation-language-support)
- 内置仓库搜索统一 `codex++`、`codex plus plus`、`CodexPlusPlus` 与单 `plus` 写法；按名称相似度排序并展示 owner/name，保留高级查询。
- 增强搜索可开启多语言互译：例如 `马尾辫skill` 补搜 `ponytail skill`，合并原关键词与互译关键词的结果。来源和补搜语言均支持 59 种选项，可自动识别或手选；来源短词识别不准时可手选。默认英语补搜中文，其他来源补搜英语。开关与语言选择会保存；高级限定符、仓库链接及 plus 别名不互译。普通搜索最多发送两次公开 REST 请求。
- 原版搜索默认保持原入口。工具菜单可开启或关闭“原版搜索接入增强搜索”；关闭时不会自动跳转，也可以从“译 / 搜”菜单单独打开增强搜索。
- “译 / 搜”按钮可拖动并记住位置；长按隐藏，当前页面继续停留 5 秒后重新显示。切换页面与进入后台重置驻留计时；工具菜单可以立即恢复按钮。
- 更新页面检查增强版发行信息，并保留官方 GitHub 的商店更新入口。

这是独立的社区补丁项目。仓库包含我们新增的代码及工具；GitHub 官方 Android APK、反编译代码、账号数据、翻译模型和签名私钥不在仓库内。需要从自己的设备提供已安装的官方 APK。

## 目录

```text
payload/          新增 Java 与 DOM 处理代码
helper/           本机翻译模型服务 Android 源码
tools/updates/    从官方 APK 提取、预检、应用补丁、构建与签名
tests/            无额外测试框架的 Java、Node 与 Python 检查
.github/workflows/  开源代码持续检查
```

## 已验证版本

当前适配官方 GitHub **1.279.0 / versionCode 950**，增强补丁 **cn2**。

已在 Huawei nova 11 / Android 12 / arm64 设备通过综合真机检查：目标应用 UID 的签名权限调用、WebView 原位替换、动态段落、原文恢复、真实 Compose 项目介绍重组、原生介绍翻译、实际 `codex++` 搜索、中英关键词双向翻译与补搜、英德法语言识别、真实英法模型翻译、按钮拖动及位置保存、长按隐藏和前台 5 秒驻留、页面识别、真实 Activity 旋转后的边界，以及原版搜索默认保留和接入开关。

59 种语言目录已与 SDK 实际列表核对。模型实测覆盖中英双向和英法，其他语言按需下载；没有预装并逐一测试全部模型。检查使用合成正文和公开仓库，未登录私人账户或验证该账户的全部仓库页面。

搜索通过公开 GitHub REST API 获取仓库，受其请求限额约束；返回原版搜索可以使用其他搜索类型。搜索不会读取或导出账号令牌。

## 构建

需要 Python 3、JDK 17、Android SDK build-tools 36 / platform 36、Node.js、apktool 3.0.3，以及已授权的 ADB 设备。模型服务另需要 Gradle 9.4.1 / Android Gradle Plugin 9.2.0。

工具链路径通过 `JAVA_HOME`、`ANDROID_SDK_ROOT` 和 `APKTOOL_JAR` 指定。签名文件放在仓库外，通过环境变量指定；同一修改版及其模型服务必须使用同一证书。

具体命令、版本适配及检查流程见 [更新工具说明](tools/updates/README.md)。不要将原版 APK 或签名文件提交到仓库。

## Updates

官方原版 `com.github.android` 继续从 [Google Play](https://play.google.com/store/apps/details?id=com.github.android) 更新，增强版 `com.github.android.chinese` 保持独立。Android 覆盖升级要求相同应用 ID、兼容签名及不降低版本号，官方签名的 APK 无法直接覆盖本机签名的增强版。[Android 更新机制](https://developer.android.com/google/play/app-updates)

官方原版更新后：连接电脑 → 使用更新工具提取当前官方分包 → 校验对应版本与全部接入点 → 构建完整增强版及模型服务 → 用保存的签名覆盖安装。正常覆盖安装保留增强版登录、设置和按钮位置。

目前只保证已验证 profile。GitHub 的混淆名称和渲染结构可能随新版变化；未知版本会生成诊断并停止，更新适配 profile 通过检查后才生成安装包。工具不会安装缺少翻译或搜索接入点的部分修改版，也不代表已经验证尚未发布的官方版本。

## 检查

Node 检查覆盖 DOM 原位替换、动态正文、代码保留、原文恢复、正文刷新与重试时的旧结果取消。Java 检查覆盖搜索名称、互译术语、59 种语言全集、词典按目标语言处理、页面 5 秒驻留和版本比较；Python 检查覆盖补丁预检、失败不改写和原版资源保留。

GitHub Actions 不需要原版 APK 或账号凭据。真实 APK 与真机检查在本地进行。

[tests/android/](tests/android/README.md) 包含运行在增强版 UID 的真实 WebView、Compose、双向模型、多语识别、搜索、手势、旋转和路由检查源码。检查使用合成正文及公开仓库，不读取账号数据；测试会恢复翻译、按钮位置与路由设置。`tools/check-device.py` 可以用外部签名密钥构建，只有显式 `--run` 才访问设备。

## 发布

发布源代码及自有模型服务，原版 GitHub 内容留在本机。稳定签名文件应保存好并备份在仓库外，后续覆盖更新依赖它。[Android 应用签名](https://developer.android.com/studio/publish/app-signing)

新增代码使用 [MIT License](LICENSE)。ML Kit、jsoup 和 Android 工具链遵循各自的许可证；本项目的 MIT 许可不扩展到 GitHub 官方应用。

仓库维护者可在源码提交并检查后，通过已登录的 [GitHub CLI](https://cli.github.com/) 发布：`python tools/publish.py --check` 先校验登录与源码，`python tools/publish.py` 创建公开仓库、推送 main 并建立源码发行页。脚本核对当前账号与预设维护者，不覆盖其他 origin，也不上传 APK 或密钥。

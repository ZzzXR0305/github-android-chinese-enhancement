# APK 内置翻译引擎

这是构建容器，不安装为独立应用，也不把它的 Manifest 合并到增强版。入口为同进程的 `cn.local.githubcn.engine.Translation.call(Context, String, Bundle)`，支持 `languages`、`identify`、`translate`，返回协议与旧翻译 Provider 一致。初始化显式提供 ML Kit 注册器，无需助手、Provider、无障碍服务或 Google Play 服务 APK。

宿主把整个 `app/build/outputs/apk/debug/app-debug.apk` 当作 asset 打包，复制到自己的私有只读代码缓存，使用 `DexClassLoader` 加载，parent 使用 Android boot classloader，不能用原 GitHub 的 application classloader。另把容器 `lib/arm64-v8a/*.so` 提取到宿主私有 native 目录，将该目录传给 DexClassLoader 的 librarySearchPath；不能只 `System.load` 两个库而不提供搜索路径。

宿主须使用 `PackageManager.getPackageArchiveInfo()` 读取容器的 ApplicationInfo，设 sourceDir/publicSourceDir 为私有 engine APK 路径，再用 `getResourcesForApplication()` 获得容器 Resources。传给入口的 Context 必须覆盖 `getResources()` 和 `getAssets()` 返回容器资源及 assets，同时保留宿主包名、UID、文件和系统服务。翻译 SDK 直接引用容器的 `R.raw.translate_models_metadata` 和 `R.xml.rapid_response_client_defaults`，不能误用宿主 0x7f 资源 ID。入口自行再包装 Context，将 getClassLoader 指向隔离 loader，并保持 getApplicationContext 不丢失包装。宿主须已有 INTERNET、ACCESS_NETWORK_STATE 权限与正确的 GMS version metadata；不加入助手的 TRANSLATE 权限或 package queries。

调用 translate/identify 必须在后台线程。普通语言识别最长等待 20 秒，翻译/首次模型准备最长等待 120 秒。翻译模型属于宿主 UID，首次需能访问 Google 模型服务的 Wi-Fi；不会读取旧助手私有模型或账户数据。

构建：设置 JAVA_HOME、ANDROID_SDK_ROOT，使用 Gradle 9.4.1 运行 `gradle --offline :app:assembleDebug :app:lintDebug`。本模块仅输出本机 arm64-v8a 所需 native 库；不得把此容器直接追加到宿主根 DEX，否则会与官方应用的混淆依赖冲突。

无框架自检：用 JDK 17 将 TextTools.java 和 tests/TextToolsCheck.java 编译到临时目录，以 `java -ea cn.local.githubcn.engine.TextToolsCheck` 执行。真机还需验证隔离 loader、语言识别、模型下载、中英互译、正文原位替换，并在助手禁用/未安装时确认仍正常。

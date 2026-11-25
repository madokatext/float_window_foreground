# Float Window Foreground

ColorOS 15 的 libxposed API 100 模块。小窗收成原厂贴边图标后，在屏幕解锁且应用进程仍在运行时，模块让这个任务继续满足失焦小窗的多窗口生命周期条件。全屏应用可以照常获得焦点和输入。

## 适用范围

- 以一加 PLC110 的 ColorOS `15.0.2.211(CN01)`、Android 15 为开发目标。启动时核对 Android 版本和所挂钩的框架成员，任一成员缺失时回退到原厂行为。
- 需要支持现代 **libxposed API 100** 的 LSPosed 实现，在 LSPosed 中启用 **系统框架** 作用域并重启。
- 当前针对默认显示屏上的独立小窗任务。贴边期间应用还需能以普通多窗口方式运行。设备锁定、休眠及进程退出时，生命周期按系统规则执行。

## 编译和安装

在 GitHub 的 **Actions → Build APK** 中运行工作流，下载 `float-window-foreground-debug` 构建产物并安装其中的 APK。推送到 `main` 时也会自动编译。

本地使用 JDK 17 与 Android SDK 35。

```sh
./gradlew :app:assembleDebug
```

Windows 可运行 `gradlew.bat :app:assembleDebug`。APK 路径为 `app/build/outputs/apk/debug/app-debug.apk`。

## 运行方式

模块按任务识别原厂贴边过渡。原厂继续创建图标、执行收起动画与还原任务。模块在图标存在期间保留任务的逻辑可见性，让 Android 自己决定 Activity 是否继续处于 `RESUMED`。任务的合成层仍隐藏，也会被排除在焦点候选之外。原厂展开、删除或淘汰图标时，模块结束这段生命周期处理。

验收时可比较同一个 App 的两种状态。先让普通小窗挂着并操作全屏 App，再把小窗贴边并重复同一操作。两次的全屏 App 都应是 `top-resumed`。能在普通小窗中维持多窗口 `RESUMED` 的 App，贴边后也应维持这一状态。当前实现仍需用目标固件真机验证。

构建依赖中的 API 100 JAR 只参与编译，由上游 [libxposed API 源码](https://github.com/libxposed/api/tree/e2588ee24bbef12c7c2e07666efa60e58e1ee6b3) 编译而来，许可证随 JAR 提供。

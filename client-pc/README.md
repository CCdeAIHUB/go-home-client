# PC 客户端

PC 客户端目标平台为 Windows、macOS、Linux。当前目录是平台壳骨架，后续实现中：

- UI 复用 `client-ui` 的构建产物。
- 每个平台拥有独立网络能力模块。
- Windows 优先使用 Wintun/TAP 能力。
- macOS/Linux 优先使用 TUN/TAP 与系统路由配置。
- JSAPI 通过 Chromium/WebView 注入 `window.GoHomeAPI`。

当前 `GoHome.Pc` 是可编译占位程序，用于保留项目边界。GitHub Actions 会优先发布 Windows `win-x64` 输出并附带 `client-ui` 构建产物。

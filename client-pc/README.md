# PC 客户端

PC 客户端目标平台为 Windows、macOS、Linux。当前目录是平台壳骨架，后续实现中：

- UI 复用 `client-ui` 的构建产物。
- 每个平台拥有独立网络能力模块。
- Windows 优先使用 Wintun/TAP 能力。
- macOS/Linux 优先使用 TUN/TAP 与系统路由配置。
- JSAPI 通过 Chromium/WebView 注入 `window.GoHomeAPI`。

当前 `GoHome.Pc` 仍是 Windows UI 壳入口，GitHub Actions 发布 Windows `win-x64` 输出时会同时附带 `client-ui` 构建产物和 `go-home-client-core.exe`。客户端核心已经负责真实 WebSocket 设备鉴权、P2P UDP 打洞握手、SM2 会话密钥保护和 SM4 加密保活；后续 Windows 壳会把 JSAPI 与该核心的虚拟网卡能力接起来。

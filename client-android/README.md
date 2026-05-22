# Android 客户端

Android 客户端当前提供可编译的 Kotlin + WebView 壳：

- GitHub Actions 先构建 `client-ui`。
- 构建后的前端文件复制到 Android assets。
- WebView 通过 `WebViewAssetLoader` 以 `https://appassets.androidplatform.net` 加载 assets 中的模块化前端。
- Android APK 通过 Actions Artifacts 提供下载。
- 原生层通过 `GoHomeNative` 桥接 WebSocket 信令、UDP 直连和系统 VPN 能力。

当前已接入：

- `VpnService` 家庭网段分流。
- UDP 打洞、SM2 会话密钥封装和 SM4-GCM 隧道帧。
- 家庭真实网段冲突检测、备用 `/24` 虚拟网段映射、设备映射与隧道流量状态。

后续继续补齐移动端前后台保活、断线宽限期恢复、日志和 OTA 原生交互。

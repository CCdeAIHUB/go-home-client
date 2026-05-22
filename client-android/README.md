# Android 客户端

Android 客户端当前提供可编译的 Kotlin + WebView 壳：

- GitHub Actions 先构建 `client-ui`。
- 构建后的前端文件复制到 Android assets。
- Android APK 通过 Actions Artifacts 提供下载。
- 原生层预留 `GoHomeAPI` 桥接入口。

后续实现继续接入：

- `VpnService` 家庭网段分流。
- UDP 打洞与国密协议。
- 设备映射、状态、日志与 OTA 原生能力。

当前 APK 是客户端壳产物，不包含完整隧道数据面。

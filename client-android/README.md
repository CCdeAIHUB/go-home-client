# Android 客户端

Android 客户端后续使用 Kotlin + WebView + VpnService：

- WebView 加载 `client-ui` 构建产物。
- VpnService 提供虚拟网络和家庭网段分流。
- UDP 打洞与国密协议由原生层实现，通过 JSAPI 暴露给前端。
- 不承诺完整二层透明能力，超出能力的数据由家庭服务器过滤。

本目录当前保留平台工程边界，完整 Gradle 工程将在实现移动端阶段生成。


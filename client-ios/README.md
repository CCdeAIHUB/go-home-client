# iOS 客户端

iOS 客户端后续使用 Swift + WebView + Network Extension：

- WebView 加载 `client-ui` 构建产物。
- Network Extension 提供系统 VPN/TUN 能力。
- 默认启用家庭网段分流，不接管互联网默认流量。
- 不承诺完整二层透明能力，超出能力的数据由家庭服务器过滤。

本目录当前保留平台工程边界，完整 Xcode 工程将在实现移动端阶段生成。


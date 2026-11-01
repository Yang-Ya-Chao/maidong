# Release 2.1.7 验证

- 正式包：`com.local.ktv`，versionCode 10，versionName 2.1.7，minSdk 21；ARM32/ARM64。
- Release 变体 90 项单元测试通过，R8/资源收缩与 Release 构建通过。
- APK：`Maidong-KTV-2.1.7.apk`，52,751,944 字节。
- SHA-256：`db68cfc99dc8970238deb5b0fd061cd7ef1bb7f5e1ec52aeb67898cac4b469b0`。
- 已使用 apksigner 分别校验已发布 2.1.6 APK 与新包，两包签名有效，证书 SHA-256 均为 `2bec00e463874bf553ba01f2fa97007883a8cb13b2b22bf3ccbee3e3826f3016`。沿用原签名配置，未修改 keystore；包名保持一致，版本号递增。
- APK ZIP 中没有 `decoder-samples` 测试媒体。
- 模拟器上先安装已发布 2.1.6，再直接覆盖安装 2.1.7，两次安装均成功；未卸载、未清除应用数据。
- 播放与账号/下载本机检查详见本目录 README 和对照日志。既有 Lint 错误未作为本次修复范围。
- 对 4 个 issue 的关闭说明区分已复现修复与本轮未复现结果，全部关联发布标签 `2.1.7`。用户发布说明见 `docs/releases/2.1.7.md`。

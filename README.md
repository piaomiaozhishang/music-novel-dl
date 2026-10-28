# 书歌

<img src="app/src/main/res/drawable-nodpi/app_logo.webp" alt="书歌" width="160" />

小说与音乐聚合 Android 应用，最低支持 Android 6.0。

## 功能

- 小说搜索、阅读书源 JSON / 阅读分享链接导入、目录与正文解析、书源管理和测试阅读。
- 小说下载及 TXT / EPUB / HTML / PDF 导出；部分章节失败时可以导出已下载内容，标注缺章并保留补下载能力。
- 下载任务和章节缓存恢复；启动后台初始化；首页介绍卡片可关闭并记忆。
- B 站音乐搜索与音轨播放、下载；音乐收藏、歌词和封面标签写入框架。
- 洛雪第三方脚本导入及接口适配；兼容性取决于脚本接口与对应平台。
- 自定义更新清单、手动检查与定期后台检查。

## 编译

使用 JDK 17、Android SDK 35。配置本机 `local.properties` 中的 `sdk.dir`，不要提交该文件。

```sh
./gradlew assembleDebug testDebugUnitTest
```

Windows 使用 `gradlew.bat`。生成文件位于 `app/build/outputs/apk/debug/`。发布请使用自己持有并持续沿用的签名证书；仓库不包含私钥、签名材料、个人书源或第三方音源脚本。

部分在线设备测试依赖本地样本和显式测试参数，默认不启用；不应把编译成功视为所有第三方网站都可用。

## 更新配置

参见 [更新发布说明](更新发布说明.md) 与 [update.json](update.json)。默认检查地址为项目 Gitee 的 `master` 分支根目录 `update.json`；配置内容通过公开原始文件链接读取。新 APK 的 versionCode 和更新清单需保持一致。

## 来源与许可

Android 基础参考 [whykang/sonovel--Android](https://github.com/whykang/sonovel--Android)，小说规则与设计参考 [freeok/so-novel](https://github.com/freeok/so-novel)。阅读规则兼容参考 [HapeLee/legado-with-MD3](https://github.com/HapeLee/legado-with-MD3)，第三方音源接口参考 [lyswhut/lx-music-desktop](https://github.com/lyswhut/lx-music-desktop)。

本项目保留 [AGPL-3.0 许可证](LICENSE)；音频标签组件完整源码与许可证见 [tagger](tagger/UPSTREAM.md)，其他组件说明见 [THIRD_PARTY-0.3.md](THIRD_PARTY-0.3.md)。

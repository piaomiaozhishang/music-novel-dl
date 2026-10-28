# 第三方组件及接口参考

- 小说应用基线：[whykang/sonovel--Android](https://github.com/whykang/sonovel--Android)，AGPL-3.0；保留根目录 LICENSE 和上游 README。
- 音频标签：[AdrienPoupa/jaudiotagger](https://github.com/AdrienPoupa/jaudiotagger)，LGPL-2.1-or-later。源码位于 tagger/src/main/java，许可证位于 tagger/LICENSE；原说明位于 tagger/UPSTREAM.md。替换该模块后可用 Gradle 重新构建 APK。
- JavaScript：[Mozilla Rhino 1.7.15](https://github.com/mozilla/rhino/tree/Rhino1_7_15_Release)，MPL-2.0。
- JSONPath：[Jayway JsonPath 2.9.0](https://github.com/json-path/JsonPath)，Apache-2.0。
- 后台任务：[AndroidX WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent)，Apache-2.0。
- 洛雪接口参考：[官方自定义源预加载接口](https://github.com/lyswhut/lx-music-desktop/blob/master/src/main/modules/userApi/renderer/preload.js)。
- 阅读规则参考：[HapeLee/legado-with-MD3](https://github.com/HapeLee/legado-with-MD3)。本应用实现独立的受限规则解释器，未嵌入完整阅读应用。

本地测试使用用户提供的两个 JS 文件及 418 条来源 JSON，均不进入分发源码或正式应用资源。音频测试文件为自行生成的正弦波。

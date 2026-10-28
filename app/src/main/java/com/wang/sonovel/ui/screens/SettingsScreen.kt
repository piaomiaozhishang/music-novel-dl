package com.wang.sonovel.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Cookie
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wang.sonovel.BuildConfig
import com.wang.sonovel.R
import com.wang.sonovel.core.UpdateResult
import com.wang.sonovel.data.AppSettings
import com.wang.sonovel.data.ExportFormat
import com.wang.sonovel.data.LangType
import com.wang.sonovel.data.ThemeMode
import com.wang.sonovel.graph
import com.wang.sonovel.ui.components.ChoiceDialog
import com.wang.sonovel.ui.components.ConfirmDialog
import com.wang.sonovel.ui.components.InputDialog
import com.wang.sonovel.ui.components.SectionTitle
import com.wang.sonovel.ui.components.SettingItem
import com.wang.sonovel.ui.components.SwitchItem
import com.wang.sonovel.ui.components.ThemeColorDialog
import com.wang.sonovel.ui.components.themeColorLabel
import com.wang.sonovel.ui.components.formatSize
import com.wang.sonovel.ui.rememberSnack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface SettingDialog {
    data class Choice<T>(
        val title: String, val options: List<T>, val selected: T, val label: (T) -> String, val onSelect: (T) -> Unit,
    ) : SettingDialog

    data class Input(
        val title: String, val initial: String, val hint: String? = null, val numeric: Boolean = false,
        val validate: (String) -> String? = { null }, val onConfirm: (String) -> Unit,
    ) : SettingDialog
}

private fun intValidator(min: Int, max: Int): (String) -> String? = { s ->
    val v = s.toIntOrNull()
    if (v == null || v < min || v > max) "请输入 $min ~ $max 之间的整数" else null
}

@Composable
fun SettingsScreen(onAbout: () -> Unit, onReferences: () -> Unit = {}, onSources: () -> Unit = {}, onDownloads: () -> Unit = {}) {
    val context = LocalContext.current
    val g = context.graph
    val s by g.settings.state.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<SettingDialog?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var showThemeColor by remember { mutableStateOf(false) }
    var cacheSize by remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    val snack = rememberSnack()
    val music by g.musicPreferences.state.collectAsStateWithLifecycle()
    var category by rememberSaveable { mutableStateOf("通用设置") }
    val settingsScroll=rememberScrollState()
    LaunchedEffect(category) { settingsScroll.scrollTo(0) }
    fun set(block: (AppSettings) -> AppSettings) = g.settings.update(block)

    val chapterCache = File(context.cacheDir, "chapters")
    LaunchedEffect(Unit) {
        cacheSize = withContext(Dispatchers.IO) { chapterCache.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        MediaHeader("设置", "让阅读与聆听，更合你的习惯")
        MediaFilters(category, listOf("通用设置", "音乐设置", "小说设置")) { category = it }
        Column(Modifier.weight(1f).verticalScroll(settingsScroll)) {

        if (category == "通用设置") {
        SectionTitle("外观")
        SettingItem("主题", s.themeMode.label, Icons.Outlined.DarkMode, onClick = {
            dialog = SettingDialog.Choice("主题", ThemeMode.entries, s.themeMode, { it.label }) { v -> set { it.copy(themeMode = v) } }
        })
        SettingItem("主题色", themeColorLabel(s), Icons.Outlined.Palette, onClick = { showThemeColor = true }) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        }
        SwitchItem("纯黑背景", "深色模式下使用纯黑背景，OLED 屏幕更省电", s.pureBlack, Icons.Outlined.Contrast) { v -> set { it.copy(pureBlack = v) } }

        SectionTitle("内容与离线")
        SettingItem("来源管理", "小说书源、B 站与第三方源接口", Icons.Outlined.Cloud, onClick = onSources)
        SettingItem("下载管理", "查看小说与音乐的下载进度", Icons.Outlined.FolderOpen, onClick = onDownloads)
        SectionTitle("应用")
        SettingItem("恢复默认设置", "保留外观、收藏与来源", Icons.Outlined.RestartAlt, onClick = { confirmReset = true })
        SettingItem("关于", "版本 ${BuildConfig.VERSION_NAME}", Icons.Outlined.Info, onClick = onAbout)
        SettingItem("借鉴的项目", "开源项目与许可证致谢", Icons.Outlined.Code, onClick = onReferences)
        }
        if (category == "音乐设置") {
        SectionTitle("下载与文件")
        SwitchItem("内嵌歌词", "下载完成后将音源提供的歌词写入音乐文件", music.embedLyrics, Icons.Outlined.TextFields) { value ->
            g.musicPreferences.update { it.copy(embedLyrics = value) }
        }
        SwitchItem("内嵌封面", "下载完成后将专辑或视频封面写入音乐文件", music.embedCover, Icons.Outlined.Image) { value ->
            g.musicPreferences.update { it.copy(embedCover = value) }
        }
        Text("对新建下载生效。支持 MP3、FLAC、M4A、OGG；音源未提供的歌词或封面会在下载列表中说明。", Modifier.padding(horizontal=20.dp, vertical=10.dp), style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
        SwitchItem("移动网络下载音乐", "关闭后，新建音乐下载仅通过 Wi-Fi 进行", music.metered, Icons.Outlined.Cloud) { value ->
            g.musicPreferences.update { it.copy(metered = value) }
        }
        SectionTitle("音乐来源")
        SettingItem("音源管理", "B 站与第三方源接口", Icons.Outlined.Cloud, onClick=onSources)
        SettingItem("音乐下载", "查看进度、歌词与封面写入结果", Icons.Outlined.FolderOpen, onClick=onDownloads)
        }
        if (category == "小说设置") {
        SectionTitle("书源")
        SettingItem("书源管理与导入", "阅读书源、内置书源与兼容情况", Icons.Outlined.Cloud, onClick=onSources)
        SectionTitle("小说下载")
        SettingItem("小说默认格式", s.format.label, Icons.Outlined.Description, onClick = {
            dialog = SettingDialog.Choice("默认格式", ExportFormat.entries, s.format, { it.label }) { v -> set { it.copy(extName = v.ext) } }
        })
        SettingItem("TXT 编码", s.txtEncoding + if (s.txtEncoding == "GBK") "（兼容旧设备）" else "", Icons.Outlined.TextFields, onClick = {
            dialog = SettingDialog.Choice("TXT 编码", listOf("UTF-8", "GBK"), s.txtEncoding, { it }) { v -> set { it.copy(txtEncoding = v) } }
        })
        SwitchItem("保存副本到 下载/SoNovel", "下载完成后自动复制到公共下载目录，方便其他应用访问", s.saveToPublic, Icons.Outlined.FolderOpen) { v ->
            set { it.copy(saveToPublic = v) }
        }
        SwitchItem("获取高清封面", "从起点、纵横、七猫匹配更清晰的封面", s.fetchBetterCover, Icons.Outlined.Image) { v ->
            set { it.copy(fetchBetterCover = v) }
        }
        SettingItem("内容语言", LangType.label(LangType.normalize(s.language)) + "（当前：${LangType.label(s.targetLanguage)}）", Icons.Outlined.Language, onClick = {
            val opts = listOf("", LangType.ZH_CN, LangType.ZH_HANT, LangType.ZH_TW)
            dialog = SettingDialog.Choice("内容语言（简繁转换）", opts, LangType.normalize(s.language) ?: "", { LangType.label(it.ifEmpty { null }) }) { v ->
                set { it.copy(language = v) }
            }
        })

        SectionTitle("小说搜索")
        SettingItem("每个书源显示条数", if (s.searchLimit <= 0) "全部" else "前 ${s.searchLimit} 条", Icons.AutoMirrored.Outlined.ViewList, onClick = {
            dialog = SettingDialog.Input("每个书源显示条数", s.searchLimit.toString(), "0 表示不限制", true, intValidator(0, 500)) { v ->
                set { it.copy(searchLimit = v.toInt()) }
            }
        })
        SwitchItem("过滤低相似度结果", "聚合搜索时隐藏与关键字相似度过低的结果", s.searchFilter, Icons.Outlined.FilterAlt) { v -> set { it.copy(searchFilter = v) } }

        SectionTitle("小说抓取")
        SettingItem("并发上限", if (s.concurrency <= 0) "自动（最多 50）" else "${s.concurrency}", Icons.Outlined.Speed, onClick = {
            dialog = SettingDialog.Input("并发上限", if (s.concurrency <= 0) "0" else s.concurrency.toString(), "0 表示自动；书源规则中的推荐值优先", true, intValidator(0, 100)) { v ->
                set { it.copy(concurrency = v.toInt().let { n -> if (n <= 0) -1 else n }) }
            }
        })
        SettingItem("请求间隔", "${s.minInterval} ~ ${s.maxInterval} 毫秒（随机）", Icons.Outlined.Timer, onClick = {
            dialog = SettingDialog.Input(
                "请求间隔（毫秒）", "${s.minInterval}-${s.maxInterval}", "格式：最小-最大，例如 200-400", false,
                { t -> val p = t.split('-').mapNotNull { it.trim().toIntOrNull() }; if (p.size != 2 || p[0] < 0 || p[1] < p[0]) "格式错误" else null },
            ) { v ->
                val p = v.split('-').map { it.trim().toInt() }
                set { it.copy(minInterval = p[0], maxInterval = p[1]) }
            }
        })
        SwitchItem("失败重试", if (s.enableRetry) "失败章节最多重试 ${s.maxRetries} 次" else "关闭后任一章节失败即中断下载", s.enableRetry, Icons.Outlined.Replay) { v ->
            set { it.copy(enableRetry = v) }
        }
        SettingItem("最大重试次数", "${s.maxRetries} 次", null, enabled = s.enableRetry, onClick = {
            dialog = SettingDialog.Input("最大重试次数", s.maxRetries.toString(), null, true, intValidator(1, 20)) { v -> set { it.copy(maxRetries = v.toInt()) } }
        })
        SettingItem("重试间隔", "${s.retryMinInterval} ~ ${s.retryMaxInterval} 毫秒（逐次递增）", null, enabled = s.enableRetry, onClick = {
            dialog = SettingDialog.Input(
                "重试间隔（毫秒）", "${s.retryMinInterval}-${s.retryMaxInterval}", "格式：最小-最大", false,
                { t -> val p = t.split('-').mapNotNull { it.trim().toIntOrNull() }; if (p.size != 2 || p[0] < 0 || p[1] < p[0]) "格式错误" else null },
            ) { v ->
                val p = v.split('-').map { it.trim().toInt() }
                set { it.copy(retryMinInterval = p[0], retryMaxInterval = p[1]) }
            }
        })

        SectionTitle("小说网络与高级选项")
        SwitchItem("HTTP 代理", if (s.proxyEnabled) "${s.proxyHost}:${s.proxyPort}" else "用于需要代理的书源", s.proxyEnabled, Icons.Outlined.Router) { v ->
            set { it.copy(proxyEnabled = v) }
        }
        SettingItem("代理地址", "${s.proxyHost}:${s.proxyPort}", null, onClick = {
            dialog = SettingDialog.Input(
                "代理地址", "${s.proxyHost}:${s.proxyPort}", "格式：主机:端口，例如 127.0.0.1:7890", false,
                { t -> if (Regex("^[^:\\s]+:\\d{1,5}$").matches(t.trim())) null else "格式错误" },
            ) { v -> set { it.copy(proxyHost = v.substringBeforeLast(':'), proxyPort = v.substringAfterLast(':').toInt()) } }
        })
        SettingItem("Cloudflare 绕过服务", s.cfBypass.ifBlank { "未设置（CloudflareBypassForScraping 服务地址）" }, Icons.Outlined.Cloud, onClick = {
            dialog = SettingDialog.Input("cf-bypass 地址", s.cfBypass, "例如 http://192.168.1.10:8000，留空表示不使用") { v -> set { it.copy(cfBypass = v) } }
        })
        SettingItem("起点 Cookie", if (s.qidianCookie.isBlank()) "未设置（填写 w_tsfp=xxx 以获取起点最新封面）" else "已设置", Icons.Outlined.Cookie, onClick = {
            dialog = SettingDialog.Input("起点 Cookie", s.qidianCookie, "w_tsfp=xxx") { v -> set { it.copy(qidianCookie = v) } }
        })
        SwitchItem("忽略 SSL 证书错误", "仅在书源证书异常时开启", s.ignoreSsl, Icons.Outlined.Lock) { v -> set { it.copy(ignoreSsl = v) } }

        SectionTitle("其他")
        SettingItem("清除章节缓存", "未完成或有失败章节的下载会保留缓存以便续传 · ${formatSize(cacheSize)}", Icons.Outlined.DeleteOutline, onClick = {
            scope.launch {
                withContext(Dispatchers.IO) { chapterCache.deleteRecursively() }
                cacheSize = 0
                snack("已清除章节缓存")
            }
        })
        }
        Spacer(Modifier.height(24.dp))
        }
    }

    when (val d = dialog) {
        is SettingDialog.Choice<*> -> {
            @Suppress("UNCHECKED_CAST")
            val c = d as SettingDialog.Choice<Any?>
            ChoiceDialog(c.title, c.options, c.selected, c.label, onDismiss = { dialog = null }, onSelect = c.onSelect)
        }
        is SettingDialog.Input -> InputDialog(
            d.title, d.initial, d.hint, d.numeric, validate = d.validate, onDismiss = { dialog = null }, onConfirm = d.onConfirm,
        )
        null -> Unit
    }

    if (showThemeColor) {
        ThemeColorDialog(
            settings = s,
            onChange = { dynamic, seed -> set { it.copy(dynamicColor = dynamic, themeSeed = seed) } },
            onDismiss = { showThemeColor = false },
        )
    }

    if (confirmReset) {
        ConfirmDialog("恢复默认设置？", "除外观设置外，所有设置项将恢复为默认值。", onDismiss = { confirmReset = false }, onConfirm = {
            g.settings.reset()
            g.musicPreferences.reset()
            snack("已恢复默认设置")
        })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updateStatus by context.graph.updater.status.collectAsStateWithLifecycle()
    val checkingUpdate = updateStatus.checking
    fun open(url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("关于") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))
            Image(
                painterResource(R.drawable.ic_launcher_foreground), null,
                Modifier.size(88.dp).clip(RoundedCornerShape(22.dp))
                    .background(androidx.compose.ui.graphics.Color(0xFF1F6F5C)),
            )
            Spacer(Modifier.height(12.dp))
            Text("音阅聚合", style = MaterialTheme.typography.headlineMedium)
            Text("版本 ${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "音阅将小说与音乐整合为共同的发现、下载、收藏和来源管理。支持 B 站音轨、第三方源接口，以及阅读书源导入。音乐文件可内嵌歌词与封面。项目遵循 AGPL-3.0 开源许可。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "免责声明：本应用仅供学习与技术交流使用，所有内容均来自第三方网站，与本应用无关。请支持正版阅读，勿将下载内容用于商业用途。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            SettingItem("项目开源地址", "piaomiaozhishang / music-novel-dl · GitHub", Icons.Outlined.Code, onClick = { open("https://github.com/piaomiaozhishang/music-novel-dl") })
            if (context.graph.updater.enabled) {
                SettingItem(
                    "检查更新", if (checkingUpdate) "正在检查…" else if (updateStatus.message.startsWith("检查失败")) "暂时无法检查更新，请稍后重试" else updateStatus.message,
                    Icons.Outlined.SystemUpdate, enabled = !checkingUpdate,
                    onClick = {
                        scope.launch {
                            when (val r = context.graph.updater.check()) {
                                is UpdateResult.Available -> context.graph.updater.show(r.info)
                                UpdateResult.Latest -> Toast.makeText(context, "已是最新版本", Toast.LENGTH_SHORT).show()
                                is UpdateResult.Failed -> Toast.makeText(context, "检查更新失败：${r.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                )
            }
            Row(Modifier.navigationBarsPadding().height(24.dp)) {}
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReferencedProjectsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    fun open(url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("借鉴的项目") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Text("感谢以下开源项目。对应许可证及版权声明保留在项目源码中。", Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium)
            SettingItem("小说项目来源 · AGPL-3.0", "github.com/whykang/sonovel--Android", Icons.Outlined.Code, onClick = { open("https://github.com/whykang/sonovel--Android") })
            SettingItem("阅读书源兼容参考", "HapeLee / legado-with-MD3", Icons.Outlined.Code, onClick = {open("https://github.com/HapeLee/legado-with-MD3")})
            SettingItem("洛雪自定义源接口参考", "lyswhut / lx-music-desktop", Icons.Outlined.Code, onClick = {open("https://github.com/lyswhut/lx-music-desktop")})
            SettingItem("音频标签 · LGPL-2.1+", "AdrienPoupa / jaudiotagger", Icons.Outlined.Code, onClick = {open("https://github.com/AdrienPoupa/jaudiotagger")})
            SettingItem("脚本与 JSON 规则", "Rhino · MPL-2.0 / JsonPath · Apache-2.0", Icons.Outlined.Code, onClick = {open("https://github.com/mozilla/rhino")})
            SettingItem("书源说明", "各书源的适用场景与注意事项", Icons.Outlined.Description, onClick = {
                open("https://github.com/freeok/so-novel/blob/main/BOOK_SOURCES.md")
            })
            SettingItem("推荐阅读器", "EPUB 推荐 Koodo Reader、Readest、静读天下 等", Icons.Outlined.Info)
            Spacer(Modifier.navigationBarsPadding().height(24.dp))
        }
    }
}

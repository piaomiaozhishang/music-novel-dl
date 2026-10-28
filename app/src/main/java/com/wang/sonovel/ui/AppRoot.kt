package com.wang.sonovel.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.wang.sonovel.ExternalEvent
import com.wang.sonovel.graph
import com.wang.sonovel.ui.components.UpdateDialog
import com.wang.sonovel.ui.screens.AboutScreen
import com.wang.sonovel.ui.screens.BatchScreen
import com.wang.sonovel.ui.screens.BookDetailScreen
import com.wang.sonovel.ui.screens.LibraryScreen
import com.wang.sonovel.ui.screens.LinkDialog
import com.wang.sonovel.ui.screens.ReaderScreen
import com.wang.sonovel.ui.screens.SearchScreen
import com.wang.sonovel.ui.screens.ReferencedProjectsScreen
import com.wang.sonovel.ui.screens.SettingsScreen
import com.wang.sonovel.ui.screens.SourcesScreen
import com.wang.sonovel.ui.screens.MusicScreen
import com.wang.sonovel.ui.screens.MusicPlayerBar
import com.wang.sonovel.ui.screens.DiscoverScreen
import com.wang.sonovel.ui.screens.CollectionScreen
import com.wang.sonovel.ui.screens.DownloadsScreen
import com.wang.sonovel.ui.screens.SourceHubScreen
import com.wang.sonovel.ui.screens.SongDetailScreen
import com.wang.sonovel.ui.screens.LocalOpenSong
import com.wang.sonovel.music.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

val LocalSnackbar = staticCompositionLocalOf { SnackbarHostState() }

enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    SEARCH("search", "发现", Icons.Outlined.Search),
    DOWNLOADS("downloads", "下载", Icons.Outlined.Download),
    LIBRARY("library", "收藏", Icons.AutoMirrored.Outlined.LibraryBooks),
    SOURCES("sources", "来源", Icons.Outlined.Hub),
    SETTINGS("settings", "设置", Icons.Outlined.Settings),
}

object Routes {
    fun book(key: String?, url: String, name: String = "", author: String = "") =
        "book?key=${Uri.encode(key.orEmpty())}&url=${Uri.encode(url)}&name=${Uri.encode(name)}&author=${Uri.encode(author)}"

    fun reader(path: String) = "reader?path=${Uri.encode(path)}"
}

@Composable
fun AppRoot(events: MutableStateFlow<ExternalEvent?>) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf(Tab.SEARCH) }
    var linkDialogUrl by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val event by events.collectAsStateWithLifecycle()
    val pendingUpdate by context.graph.updater.pending.collectAsStateWithLifecycle()

    // 启动时检测一次新版本（失败时静默）
    LaunchedEffect(Unit) { context.graph.updater.checkOnStartup() }

    LaunchedEffect(event) {
        when (val e = event) {
            is ExternalEvent.SharedUrl -> {
                if (Regex("BV[0-9A-Za-z]{10}").containsMatchIn(e.url)) {
                    try {
                        val song=context.graph.musicSources.source("bilibili").search(e.url).songs.first()
                        nav.navigate("song?key="+Uri.encode(context.graph.media.remember(song)))
                    } catch (error: Exception) { snackbar.showSnackbar(error.message ?: "无法打开 B 站内容") }
                } else {
                    val rule = context.graph.rules.matchByUrl(e.url)
                    if (rule != null) nav.navigate(Routes.book(rule.key, e.url)) else linkDialogUrl = e.url
                }
            }
            is ExternalEvent.OpenBook -> {
                tab = Tab.LIBRARY
                nav.popBackStack("home", inclusive = false)
                nav.navigate(Routes.reader(e.path))
            }
            is ExternalEvent.OpenTab -> {
                if (e.tab == "music") {
                    val playing = com.wang.sonovel.music.MusicPlaybackService.playback.value.songKey
                    if (playing.isNotBlank()) nav.navigate("song?key=" + Uri.encode(playing))
                    else tab = Tab.SEARCH
                }
                Tab.entries.firstOrNull { it.route == e.tab }?.let { tab = it }
                if (e.tab != "music") nav.popBackStack("home", inclusive = false)
            }
            null -> Unit
        }
        events.value = null
    }

    val openSong: (Song) -> Unit = { song ->
        val key = context.graph.media.remember(song)
        nav.navigate("song?key=" + Uri.encode(key)) { launchSingleTop = true }
    }
    CompositionLocalProvider(LocalSnackbar provides snackbar, LocalOpenSong provides openSong) {
        NavHost(navController = nav, startDestination = "home") {
            composable("home") {
                HomeScaffold(nav, tab, onTab = { tab = it }, snackbar = snackbar, onLink = { linkDialogUrl = "" })
            }
            composable("song?key={key}", arguments = listOf(navArgument("key") { type=NavType.StringType;defaultValue="" })) { entry ->
                Scaffold(snackbarHost={SnackbarHost(snackbar)}) { padding ->
                    Box(Modifier.padding(bottom=padding.calculateBottomPadding())) {
                        SongDetailScreen(entry.arguments?.getString("key").orEmpty(), {nav.popBackStack()}, {
                            tab=Tab.DOWNLOADS;nav.popBackStack("home",false)
                        })
                    }
                }
            }
            composable("book-sources") {
                Scaffold(snackbarHost={SnackbarHost(snackbar)}) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                        androidx.compose.material3.TextButton({nav.popBackStack()}) {Text("返回来源")}
                        Box(Modifier.weight(1f)) { SourcesScreen(onOpenBook={ book -> nav.navigate(Routes.book(book.sourceKey,book.url,book.bookName,book.author.orEmpty())) }) }
                        MusicPlayerBar()
                    }
                }
            }
            composable(
                "book?key={key}&url={url}&name={name}&author={author}",
                arguments = listOf(
                    navArgument("key") { type = NavType.StringType; defaultValue = "" },
                    navArgument("url") { type = NavType.StringType; defaultValue = "" },
                    navArgument("name") { type = NavType.StringType; defaultValue = "" },
                    navArgument("author") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                val a = entry.arguments!!
                Column {
                Box(Modifier.weight(1f)) { BookDetailScreen(
                    ruleKey = a.getString("key").orEmpty().ifBlank { null },
                    url = a.getString("url").orEmpty(),
                    initialName = a.getString("name").orEmpty(),
                    initialAuthor = a.getString("author").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onOpenLibrary = {
                        tab = Tab.DOWNLOADS
                        nav.popBackStack("home", inclusive = false)
                    },
                ) }
                MusicPlayerBar(navigationPadding=true)
                }
            }
            composable("batch") {
                BatchScreen(onBack = { nav.popBackStack() }, onOpenLibrary = {
                    tab = Tab.DOWNLOADS
                    nav.popBackStack("home", inclusive = false)
                })
            }
            composable("about") { AboutScreen(onBack = { nav.popBackStack() }) }
            composable("references") { ReferencedProjectsScreen(onBack = { nav.popBackStack() }) }
            composable(
                "reader?path={path}",
                arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" }),
            ) { entry ->
                Column {
                    Box(Modifier.weight(1f)) {
                        ReaderScreen(
                            path = entry.arguments?.getString("path").orEmpty(),
                            onBack = { nav.popBackStack() },
                        )
                    }
                    MusicPlayerBar(navigationPadding=true)
                }
            }
        }
    }

    pendingUpdate?.let { info ->
        UpdateDialog(
            info = info,
            onLater = { context.graph.updater.dismiss() },
            onIgnore = { context.graph.updater.ignore(info) },
        )
    }

    linkDialogUrl?.let { initial ->
        LinkDialog(
            initial = initial,
            onDismiss = { linkDialogUrl = null },
            onOpen = { key, url ->
                linkDialogUrl = null
                nav.navigate(Routes.book(key, url))
            },
        )
    }
}

@Composable
private fun HomeScaffold(
    nav: NavHostController,
    tab: Tab,
    onTab: (Tab) -> Unit,
    snackbar: SnackbarHostState,
    onLink: () -> Unit,
) {
    val context = LocalContext.current
    val tasks by context.graph.downloads.tasks.collectAsStateWithLifecycle()
    val musicTasks by context.graph.musicDownloads.tasks.collectAsStateWithLifecycle()
    val savedTabs = rememberSaveableStateHolder()
    val activeCount = tasks.count { it.status.active } + musicTasks.count { it.active }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            Column {
            MusicPlayerBar()
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { onTab(t) },
                        icon = {
                            if (t == Tab.DOWNLOADS && activeCount > 0) {
                                BadgedBox(badge = { Badge { Text("$activeCount") } }) { Icon(t.icon, null) }
                            } else Icon(t.icon, null)
                        },
                        label = { Text(t.label) },
                    )
                }
            }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AnimatedContent(tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { t ->
                savedTabs.SaveableStateProvider(t.route) {
                when (t) {
                    Tab.SEARCH -> DiscoverScreen(
                        onOpenBook = { r -> nav.navigate(Routes.book(r.sourceKey, r.url, r.bookName, r.author.orEmpty())) },
                        onBatch = { nav.navigate("batch") },
                        onLink = onLink,
                    )
                    Tab.LIBRARY -> CollectionScreen(onRead = { nav.navigate(Routes.reader(it.absolutePath)) },onBook={nav.navigate(Routes.book(it.sourceKey,it.url,it.bookName,it.author.orEmpty()))})
                    Tab.DOWNLOADS -> DownloadsScreen(onRead = { nav.navigate(Routes.reader(it.absolutePath)) })
                    Tab.SOURCES -> SourceHubScreen(onManageBooks={nav.navigate("book-sources")})
                    Tab.SETTINGS -> SettingsScreen(onAbout = { nav.navigate("about") },onReferences = { nav.navigate("references") },onSources={onTab(Tab.SOURCES)},onDownloads={onTab(Tab.DOWNLOADS)})
                }
                }
            }
        }
    }
}

/**
 * 下载前申请必要权限（通知、Android 9 及以下的存储权限），无论是否授予都继续执行
 */
@Composable
fun rememberDownloadPermissions(): (onReady: () -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pending?.invoke()
        pending = null
    }
    return remember(launcher) {
        { onReady ->
            val perms = buildList {
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                if (Build.VERSION.SDK_INT < 29 && context.graph.settings.current.saveToPublic) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.filter {
                androidx.core.content.ContextCompat.checkSelfPermission(context, it) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (perms.isEmpty()) onReady() else {
                pending = onReady
                launcher.launch(perms.toTypedArray())
            }
        }
    }
}

/** 显示 Snackbar 的便捷方法 */
@Composable
fun rememberSnack(): (String) -> Unit {
    val host = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    return remember(host) { { msg -> scope.launch { host.currentSnackbarData?.dismiss(); host.showSnackbar(msg) } } }
}

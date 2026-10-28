package com.wang.sonovel

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.wang.sonovel.core.Analytics
import com.wang.sonovel.core.ChineseConverter
import com.wang.sonovel.core.Http
import com.wang.sonovel.core.JsEngine
import com.wang.sonovel.core.UpdateChecker
import com.wang.sonovel.data.HistoryRepository
import com.wang.sonovel.data.LibraryRepository
import com.wang.sonovel.data.RuleRepository
import com.wang.sonovel.data.SettingsRepository
import com.wang.sonovel.download.DownloadManager
import com.wang.sonovel.download.DownloadService
import com.wang.sonovel.music.MusicDownloads
import com.wang.sonovel.reader.ReaderPrefsRepository
import com.wang.sonovel.reader.ReadingProgressRepository
import kotlin.concurrent.thread

/** 简单的依赖容器 */
class AppGraph(context: Context) {
    init { com.wang.sonovel.core.ReadingState.init(context.filesDir) }
    val settings = SettingsRepository(context)
    val rules = RuleRepository(context, settings)
    val history = HistoryRepository(context)
    val library = LibraryRepository(context)
    val downloads = DownloadManager(context, settings, rules, library)
    val musicDownloads = MusicDownloads(context)
    val musicPreferences = com.wang.sonovel.music.MusicPreferencesRepository(context)
    val media = com.wang.sonovel.music.MediaRepository(context)
    val musicSources = com.wang.sonovel.music.MusicSources(context)
    val readerPrefs = ReaderPrefsRepository(context)
    val progress = ReadingProgressRepository(context)
    val updater = UpdateChecker(context, settings)
}

class SoNovelApp : Application(), ImageLoaderFactory {
    val graph: AppGraph by lazy { AppGraph(this) }
    val readyGraph = kotlinx.coroutines.flow.MutableStateFlow<AppGraph?>(null)
    val startupError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    private val starting = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        ChineseConverter.init(this)
        DownloadService.createChannels(this)
        // The isolated script service does not need to parse the app's book sources.
        val process = if (android.os.Build.VERSION.SDK_INT >= 28) getProcessName() else
            (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).runningAppProcesses
                ?.firstOrNull { it.pid == android.os.Process.myPid() }?.processName.orEmpty()
        if (!process.endsWith(":lx")) initialize()
    }

    fun initialize() {
        if (!starting.compareAndSet(false, true)) return
        startupError.value = null
        thread(name = "app-init") {
            try {
                readyGraph.value = graph
                android.os.Handler(mainLooper).post { Analytics.start(this) }
                runCatching { JsEngine.init() }
            } catch (e: Exception) {
                startupError.value = "加载本地资料失败，请重试"
                android.util.Log.e("SoNovel", "Application initialization failed", e)
            } finally { starting.set(false) }
        }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { Http.client(graph.settings.current) }
        .diskCache {
            DiskCache.Builder().directory(cacheDir.resolve("covers")).maxSizeBytes(50L * 1024 * 1024).build()
        }
        .crossfade(true)
        .respectCacheHeaders(false)
        .build()
}

val Context.graph: AppGraph get() = (applicationContext as SoNovelApp).graph
fun graphOfMusic(context: Context): Boolean = context.getSharedPreferences("music_sources", Context.MODE_PRIVATE).getBoolean("metered", true)

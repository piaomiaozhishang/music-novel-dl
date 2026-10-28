package com.wang.sonovel.core

import android.content.Context
import androidx.work.*
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.wang.sonovel.BuildConfig
import com.wang.sonovel.data.SettingsRepository
import com.wang.sonovel.graph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val forceUpdate: Boolean,
    val downloadUrl: String,
    val updateMessage: String,
)

sealed interface UpdateResult {
    data class Available(val info: UpdateInfo) : UpdateResult
    data object Latest : UpdateResult
    data class Failed(val message: String) : UpdateResult
}

object UpdateManifest {
    const val INTERVAL_MS = 12 * 60 * 60 * 1000L
    fun isDue(last: Long, now: Long): Boolean = last <= 0 || now < last || now - last >= INTERVAL_MS
    fun parse(text: String, currentCode: Int): UpdateResult {
        require(text.isNotBlank()) { "更新信息尚未发布，请稍后再试" }
        val root = JsonParser.parseString(text).asJsonObject
        val code = root.get("versionCode")?.asString?.toIntOrNull()
        require(code != null && code > 0) { "更新配置缺少有效的 versionCode" }
        val name = root.get("versionName")?.asString.orEmpty().trim()
        require(name.isNotBlank()) { "更新配置缺少 versionName" }
        if (code <= currentCode) return UpdateResult.Latest
        val url = root.get("downloadUrl")?.asString.orEmpty().trim()
        require(url.toHttpUrlOrNull() != null) { "新版本尚未配置有效的 HTTP / HTTPS 下载地址" }
        return UpdateResult.Available(UpdateInfo(code, name,
            root.get("forceUpdate")?.asBoolean ?: false, url,
            root.get("updateMessage")?.asString.orEmpty()))
    }
}

data class UpdateStatus(val checkedAt: Long = 0, val message: String = "尚未检查", val checking: Boolean = false)

/** One persisted 12-hour gate is shared by foreground catch-up and background work. */
class UpdateChecker(
    private val context: Context,
    private val settings: SettingsRepository,
    private val endpoint: String = BuildConfig.UPDATE_URL,
    preferenceName: String = "update",
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val prefs = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val _pending = MutableStateFlow<UpdateInfo?>(null)
    val pending = _pending.asStateFlow()
    private val _status = MutableStateFlow(UpdateStatus(prefs.getLong("checked_at", 0), prefs.getString("message", "尚未检查").orEmpty()))
    val status = _status.asStateFlow()
    val enabled get() = endpoint.isNotBlank()

    private fun cached(): UpdateInfo? = runCatching {
        (UpdateManifest.parse(prefs.getString("available", "").orEmpty(), BuildConfig.VERSION_CODE) as? UpdateResult.Available)?.info
    }.getOrNull()

    private fun offer(info: UpdateInfo?) {
        if (info != null && (info.forceUpdate || info.versionCode != prefs.getInt("ignored_version_code", 0))) _pending.value = info
    }

    suspend fun check(): UpdateResult = mutex.withLock { fetch() }

    private suspend fun fetch(): UpdateResult = withContext(Dispatchers.IO) {
        _status.value = _status.value.copy(checking = true)
        val result = try {
            check(enabled) { "未配置更新地址" }
            val page = Http.get(Http.client(settings.current), endpoint, 15, mapOf("Cache-Control" to "no-cache"))
            check(page.code in 200..299) { "更新服务返回 HTTP ${page.code}" }
            UpdateManifest.parse(String(page.bytes, Charsets.UTF_8).removePrefix("\uFEFF"), BuildConfig.VERSION_CODE)
        } catch (e: CancellationException) {
            _status.value = _status.value.copy(checking = false)
            throw e
        } catch (e: Exception) {
            UpdateResult.Failed(e.message ?: "连接更新服务失败")
        }
        val at = now()
        val message = when (result) {
            is UpdateResult.Available -> "发现新版本 ${result.info.versionName}"
            UpdateResult.Latest -> "已是最新版本"
            is UpdateResult.Failed -> "检查失败：${result.message}"
        }
        prefs.edit().putLong("checked_at", at).putString("message", message).apply {
            when (result) {
                is UpdateResult.Available -> putString("available", Gson().toJson(result.info))
                UpdateResult.Latest -> remove("available")
                is UpdateResult.Failed -> Unit
            }
        }.apply()
        if (result == UpdateResult.Latest) _pending.value = null
        _status.value = UpdateStatus(at, message)
        result
    }

    suspend fun checkIfDue() = mutex.withLock {
        if (enabled && UpdateManifest.isDue(prefs.getLong("checked_at", 0), now())) {
            val result = fetch()
            if (result is UpdateResult.Available) offer(result.info)
        }
    }

    suspend fun checkOnStartup() {
        schedule()
        offer(cached())
        checkIfDue()
    }

    fun schedule() {
        if (!enabled) return
        val request = PeriodicWorkRequestBuilder<AppUpdateWorker>(12, TimeUnit.HOURS)
            .setInitialDelay(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun show(info: UpdateInfo) { _pending.value = info }
    fun dismiss() { _pending.value = null }
    fun ignore(info: UpdateInfo) {
        prefs.edit().putInt("ignored_version_code", info.versionCode).apply()
        dismiss()
    }
    companion object { const val WORK_NAME = "yingyue-version-check" }
}

class AppUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        applicationContext.graph.updater.checkIfDue()
        return Result.success()
    }
}

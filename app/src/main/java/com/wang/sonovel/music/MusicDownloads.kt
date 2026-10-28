package com.wang.sonovel.music

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.File
import androidx.work.WorkManager

data class MusicDownload(
    val systemId: Long,
    val song: Song,
    val quality: MusicQuality,
    val fileName: String,
    val status: Int = DownloadManager.STATUS_PENDING,
    val bytes: Long = 0,
    val total: Long = -1,
    val reason: String = "",
    val embedLyrics: Boolean = false,
    val embedCover: Boolean = false,
    val tagState: String? = null,
    val tagMessage: String? = null,
) {
    val active: Boolean get() = status in setOf(DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED)
    val complete: Boolean get() = status == DownloadManager.STATUS_SUCCESSFUL
}

class MusicDownloads(context: Context) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val prefs = app.getSharedPreferences("music_downloads", Context.MODE_PRIVATE)
    private val gson = musicGson()
    private val verified = mutableMapOf<Long, Boolean>()
    private val _tasks = MutableStateFlow<List<MusicDownload>>(runCatching {
        gson.fromJson<List<MusicDownload>>(prefs.getString("tasks", "[]"), object : TypeToken<List<MusicDownload>>() {}.type)
    }.getOrNull() ?: emptyList())
    val tasks = _tasks.asStateFlow()

    @Synchronized
    fun enqueue(song: Song, quality: MusicQuality, resource: AudioResource): Long {
        require(!_tasks.value.any { it.song.source == song.source && it.song.id == song.id && it.quality == quality && (it.active || it.complete) }) {
            "此音质已在下载列表中"
        }
        val format=resource.format.ifBlank { quality.format }.lowercase()
        require(format in listOf("mp3","flac","m4a","ogg","aac")) { "不支持的音频格式" }
        val fileName = "${safeName(song.artist)} - ${safeName(song.title)}_${song.id}_${quality.bitrate}_${System.currentTimeMillis()}.$format"
        val request = DownloadManager.Request(Uri.parse(resource.url))
            .setTitle("${song.title} · ${quality.label}").setDescription(song.artist)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(com.wang.sonovel.graphOfMusic(app))
            .setMimeType(when (format) { "mp3" -> "audio/mpeg"; "flac" -> "audio/flac"; "ogg" -> "audio/ogg"; "m4a" -> "audio/mp4"; else -> "audio/aac" })
            .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_MUSIC, fileName)
        resource.headers.forEach { (key, value) -> request.addRequestHeader(key, value) }
        val id = manager.enqueue(request)
        val options=MusicPreferencesRepository(app).state.value
        _tasks.value = listOf(MusicDownload(id, song, quality, fileName, embedLyrics=options.embedLyrics, embedCover=options.embedCover, tagState=if(options.embedLyrics || options.embedCover) "pending" else null)) + _tasks.value
        persist()
        if(options.embedLyrics || options.embedCover) MusicTagWorker.schedule(app,id)
        return id
    }

    @Synchronized
    fun cancel(id: Long) {
        val task = _tasks.value.firstOrNull { it.systemId == id } ?: return
        if (!task.active) return
        WorkManager.getInstance(app).cancelUniqueWork("music-tags-$id")
        manager.remove(id)
        _tasks.value = _tasks.value.map { if (it.systemId == id) it.copy(status = DownloadManager.STATUS_FAILED, reason = "已取消") else it }
        persist()
    }

    @Synchronized
    fun remove(id: Long) {
        require(_tasks.value.none { it.systemId == id && it.active }) { "请先取消下载" }
        WorkManager.getInstance(app).cancelUniqueWork("music-tags-$id")
        manager.remove(id)
        verified.remove(id)
        _tasks.value = _tasks.value.filterNot { it.systemId == id }
        persist()
    }

    @Synchronized
    fun retry(id: Long, resource: AudioResource) {
        val old = _tasks.value.firstOrNull { it.systemId == id } ?: return
        require(!old.active && !old.complete)
        // Only retire the old record after the replacement has been accepted by Android.
        enqueue(old.song, old.quality, resource)
        manager.remove(id)
        _tasks.value = _tasks.value.filterNot { it.systemId == id }
        persist()
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val snapshot = _tasks.value
        val refreshed = snapshot.associate { task ->
            task.systemId to runCatching {
                manager.query(DownloadManager.Query().setFilterById(task.systemId)).use { cursor ->
                    if (!cursor.moveToFirst()) return@use task.copy(status = DownloadManager.STATUS_FAILED, reason = task.reason.ifBlank { "下载记录已移除，请重新下载" })
                    fun number(column: String) = cursor.getLong(cursor.getColumnIndexOrThrow(column))
                    val status = number(DownloadManager.COLUMN_STATUS).toInt()
                    val reason = number(DownloadManager.COLUMN_REASON).toInt()
                    val readable = if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        // Verify once per process; reject an HTML/JSON error page saved with an audio extension.
                        verified.getOrPut(task.systemId) { verifyAudio(task.systemId) }
                    } else true
                    task.copy(
                        status = if (readable) status else DownloadManager.STATUS_FAILED,
                        bytes = number(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                        total = number(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                        reason = when {
                            !readable -> "文件无法读取或不是音频，请重新下载"
                            status == DownloadManager.STATUS_FAILED -> when (reason) {
                                DownloadManager.ERROR_INSUFFICIENT_SPACE -> "存储空间不足"
                                DownloadManager.ERROR_HTTP_DATA_ERROR -> "连接中断，请重试"
                                else -> "下载失败（$reason），请重新获取地址"
                            }
                            status == DownloadManager.STATUS_PAUSED -> "等待网络或系统恢复下载"
                            else -> ""
                        },
                    )
                }
            }.getOrElse { task.copy(reason = "暂时无法读取下载状态") }
        }
        synchronized(this@MusicDownloads) {
            // A cancel/enqueue during the query must not be overwritten by stale results.
            _tasks.value = _tasks.value.map { current ->
                if (snapshot.firstOrNull { it.systemId == current.systemId } == current) refreshed[current.systemId] ?: current else current
            }
            persist()
        }
    }

    internal fun file(task: MusicDownload): File {
        val directory=app.getExternalFilesDir(Environment.DIRECTORY_MUSIC)!!.canonicalFile
        return File(directory,task.fileName).canonicalFile.also { require(it.parentFile==directory) }
    }
    @Synchronized
    internal fun commitTags(id: Long, candidate: File): Boolean {
        val task=_tasks.value.firstOrNull { it.systemId==id && it.complete } ?: return false
        val target=file(task)
        if(!target.isFile || candidate.parentFile?.canonicalFile != target.parentFile) return false
        return candidate.renameTo(target)
    }
    @Synchronized
    internal fun tagStatus(id: Long, state: String, message: String) {
        _tasks.value=_tasks.value.map { if(it.systemId==id) it.copy(tagState=state,tagMessage=message) else it }
        persist()
    }
    @Synchronized
    fun retryTags(id: Long) {
        val task=_tasks.value.firstOrNull { it.systemId==id && it.complete } ?: return
        if(task.tagState in listOf("pending","writing")) return
        tagStatus(id,"pending","等待补全歌词与封面")
        MusicTagWorker.schedule(app,id,retry=true)
    }

    fun uri(id: Long): Uri = manager.getUriForDownloadedFile(id) ?: throw IOException("文件不存在，请重新下载")

    private fun verifyAudio(id: Long): Boolean = runCatching {
        app.contentResolver.openInputStream(uri(id))!!.use { stream ->
            val header = ByteArray(12)
            val size = stream.read(header)
            val magic = header.take(4).toByteArray().toString(Charsets.US_ASCII)
            size >= 4 && (magic.startsWith("ID3") || magic == "fLaC" || magic == "OggS" ||
                (size >= 8 && String(header, 4, 4, Charsets.US_ASCII) == "ftyp") ||
                ((header[0].toInt() and 255) == 255 && (header[1].toInt() and 224) == 224))
        }
    }.getOrDefault(false)

    private fun persist() { prefs.edit().putString("tasks", gson.toJson(_tasks.value)).commit() }

    companion object {
        fun safeName(name: String): String = name.replace(Regex("[\\p{Cntrl}\\\\/:*?\"<>|]"), "_")
            .trim().trim('.').take(48).ifBlank { "未命名" }
    }
}

package com.wang.sonovel.music

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.DownloadManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.work.*
import com.google.gson.JsonParser
import com.wang.sonovel.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

data class CoverData(val bytes: ByteArray, val width: Int, val height: Int)

/** Write a separate candidate, read the tags back, then atomically replace the original. */
object MusicTagWriter {
    fun write(file: File, song: Song, lyrics: String?, cover: CoverData?, commit: (File) -> Boolean = { it.renameTo(file) }) {
        require(file.extension.lowercase() in listOf("mp3", "flac", "m4a", "ogg")) { "此音频格式暂不支持内嵌标签" }
        if (lyrics.isNullOrBlank() && cover == null) return
        val candidate = File(file.parentFile, ".tags-${file.name}")
        try {
            file.copyTo(candidate, overwrite=true)
            val audio = AudioFileIO.read(candidate)
            val tag = audio.tagOrCreateAndSetDefault
            tag.setField(FieldKey.TITLE, song.title)
            tag.setField(FieldKey.ARTIST, song.artist)
            if (song.album.isNotBlank()) tag.setField(FieldKey.ALBUM, song.album)
            if (!lyrics.isNullOrBlank()) tag.setField(FieldKey.LYRICS, lyrics)
            if (cover != null) {
                val art = object: org.jaudiotagger.tag.images.AndroidArtwork() {
                    // BitmapFactory already decoded this image and supplied its dimensions.
                    override fun setImageFromData() = width>0 && height>0
                }.apply {
                    binaryData = cover.bytes; mimeType = "image/jpeg"; pictureType = 3
                    width = cover.width; height = cover.height; description = "Cover"
                }
                tag.deleteArtworkField()
                tag.setField(art)
            }
            AudioFileIO.write(audio)
            val checked = AudioFileIO.read(candidate).tag
            check(lyrics.isNullOrBlank() || checked.getFirst(FieldKey.LYRICS) == lyrics) { "歌词写入校验失败" }
            check(cover == null || checked.firstArtwork?.binaryData?.contentEquals(cover.bytes) == true) { "封面写入校验失败" }
            check(commit(candidate)) { "下载已移除或文件替换失败" }
        } finally { candidate.delete() }
    }
}

internal object MusicMetadata {
    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    private fun bytes(url: String, limit: Int, referer: String? = null): ByteArray {
        require(url.startsWith("https://") || url.startsWith("http://"))
        val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0")
        referer?.let { request.header("Referer", it) }
        return client.newCall(request.build()).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val out = ByteArrayOutputStream()
            response.body!!.byteStream().use { stream ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = stream.read(buffer); if (n < 0) break
                    require(out.size() + n <= limit) { "元数据过大" }; out.write(buffer, 0, n)
                }
            }
            out.toByteArray()
        }
    }
    // Public builds only obtain lyrics from a user-supplied source through MusicSources.metadata.
    fun lyrics(@Suppress("UNUSED_PARAMETER") song: Song): String? = null

    fun cover(song: Song): CoverData? {
        if (song.cover.isBlank()) return null
        val data = bytes(song.cover, 5*1024*1024, if(song.source=="bilibili") "https://www.bilibili.com/" else null)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        require(bounds.outWidth in 1..20000 && bounds.outHeight in 1..20000) { "封面格式无效" }
        val options = BitmapFactory.Options().apply {
            inSampleSize=1
            while (bounds.outWidth/inSampleSize>1200 || bounds.outHeight/inSampleSize>1200) inSampleSize*=2
        }
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, options) ?: error("封面无法解码")
        try {
            val out=ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out))
            return CoverData(out.toByteArray(), bitmap.width, bitmap.height)
        } finally { bitmap.recycle() }
    }
}

internal fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val out=ByteArrayOutputStream(); val buffer=ByteArray(8192)
    while(true) { val n=read(buffer); if(n<0) break; require(out.size()+n<=limit) { "内容超过大小限制" }; out.write(buffer,0,n) }
    return out.toByteArray()
}

class MusicTagWorker(context: Context, params: WorkerParameters): CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val downloads=applicationContext.graph.musicDownloads
        val id=inputData.getLong("id", -1)
        downloads.refresh()
        val task=downloads.tasks.value.firstOrNull { it.systemId==id } ?: return@withContext Result.success()
        if (task.active) return@withContext Result.retry()
        if (!task.complete || task.tagState in listOf("done", "partial", "failed") || (!task.embedLyrics && !task.embedCover)) return@withContext Result.success()
        downloads.tagStatus(id,"writing","正在写入歌词与封面…")
        val notes=mutableListOf<String>()
        val sources=applicationContext.graph.musicSources
        val lyrics=if(task.embedLyrics) runCatching { runCatching { sources.metadata(task.song,"lyric") }.getOrNull()?.takeIf { it.isNotBlank() } ?: MusicMetadata.lyrics(task.song) }
            .onFailure { notes += "歌词获取失败" }.getOrNull().also { if(it.isNullOrBlank() && notes.none { n->n.startsWith("歌词") }) notes += "音源未提供歌词" } else null
        val cover=if(task.embedCover) runCatching {
            val url=runCatching { sources.metadata(task.song,"pic") }.getOrNull()
            MusicMetadata.cover(if(url.isNullOrBlank()) task.song else task.song.copy(cover=url))
        }
            .onFailure { notes += "封面获取失败" }.getOrNull().also { if(it==null && notes.none { n->n.startsWith("封面") }) notes += "音源未提供封面" } else null
        if (isStopped) return@withContext Result.retry()
        runCatching {
            MusicTagWriter.write(downloads.file(task),task.song,lyrics,cover) { candidate ->
                if (isStopped) false else downloads.commitTags(id,candidate)
            }
        }.fold({
            val written=listOfNotNull(if(!lyrics.isNullOrBlank()) "歌词" else null, if(cover!=null) "封面" else null)
            val message=(if(written.isEmpty()) emptyList() else listOf("已内嵌"+written.joinToString("、"))) + notes
            downloads.tagStatus(id,if(notes.isEmpty()) "done" else "partial",message.joinToString("；"))
        },{
            downloads.tagStatus(id,"failed","音频已保存，标签写入失败：" + (it.message ?: "请重试").take(100))
        })
        Result.success()
    }
    companion object {
        fun schedule(context: Context, id: Long, retry:Boolean=false) {
            val request=OneTimeWorkRequestBuilder<MusicTagWorker>().setInputData(workDataOf("id" to id))
                .setBackoffCriteria(BackoffPolicy.LINEAR,30,TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("music-tags-$id", if(retry) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,request)
        }
    }
}

class MusicDownloadReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if(intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id=intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        if(context.graph.musicDownloads.tasks.value.any { it.systemId==id && (it.embedLyrics || it.embedCover) }) MusicTagWorker.schedule(context,id)
    }
}

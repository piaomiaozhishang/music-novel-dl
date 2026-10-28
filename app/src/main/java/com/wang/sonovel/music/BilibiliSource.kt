package com.wang.sonovel.music

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Public video catalog and audio tracks; server permissions and availability are preserved. */
class BilibiliSource : MusicSource {
    override val id = "bilibili"
    override val name = "哔哩哔哩"
    private val client = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()
    private val headers = mapOf("Referer" to "https://www.bilibili.com/", "User-Agent" to
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36")
    @Volatile private var cookie = ""

    private fun api(path: String, params: Map<String, String>): JsonObject {
        val url = ("https://api.bilibili.com/$path").toHttpUrl().newBuilder()
        params.forEach { (k,v) -> url.addQueryParameter(k,v) }
        val request = Request.Builder().url(url.build())
        headers.forEach { (k,v) -> request.header(k,v) }
        if (cookie.isNotBlank()) request.header("Cookie", cookie)
        return client.newCall(request.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("B 站暂时无法访问（${response.code}）")
            val json = JsonParser.parseString(response.body!!.string()).asJsonObject
            if (json.get("code")?.asInt != 0) throw IOException("B 站：${json.get("message")?.asString ?: "请求受限"}，可尝试直接搜索 BV 号")
            json.getAsJsonObject("data") ?: throw IOException("B 站没有返回内容")
        }
    }

    override suspend fun search(keyword: String, page: Int): MusicPage = withContext(Dispatchers.IO) {
        val bv = Regex("BV[0-9A-Za-z]{10}").find(keyword)?.value
        if (bv != null) return@withContext MusicPage(listOf(detail(Song(bv,bv,"","","",0,emptyList(),id))), false)
        if (cookie.isBlank()) runCatching {
            val request = Request.Builder().url("https://www.bilibili.com/")
            headers.forEach { (k,v) -> request.header(k,v) }
            client.newCall(request.build()).execute().use { cookie = it.headers.values("Set-Cookie").joinToString("; ") { c -> c.substringBefore(';') } }
        }
        val data = api("x/web-interface/search/type", mapOf("search_type" to "video", "keyword" to keyword, "page" to "${page+1}", "page_size" to "20"))
        val songs = data.getAsJsonArray("result")?.mapNotNull { el ->
            val r = el.asJsonObject
            val bvid = r.get("bvid")?.asString ?: return@mapNotNull null
            fun s(key: String) = r.get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            Song(bvid, Jsoup.parse(s("title")).text(), s("author"), "B 站音轨", s("pic").let { if (it.startsWith("//")) "https:$it" else it },
                durationSeconds(s("duration")), emptyList(), id, s("description"), "https://www.bilibili.com/video/$bvid")
        }.orEmpty()
        MusicPage(songs, page+1 < (data.get("numPages")?.asInt ?: 1))
    }

    override suspend fun detail(song: Song): Song = withContext(Dispatchers.IO) {
        val data = api("x/web-interface/view", mapOf("bvid" to song.id))
        val cid = data.get("cid").asLong
        val base = song.copy(title = data.get("title").asString, artist = data.getAsJsonObject("owner").get("name").asString,
            cover = data.get("pic").asString, duration = data.get("duration").asInt, cid = cid,
            description = data.get("desc")?.asString.orEmpty(), webUrl = "https://www.bilibili.com/video/${song.id}")
        val tracks = audio(base)
        base.copy(qualities = tracks.map { MusicQuality("m4a", it.get("bandwidth").asInt / 1000) }.distinct().sortedByDescending { it.bitrate })
    }

    private fun audio(song: Song): List<JsonObject> {
        val data = api("x/player/playurl", mapOf("bvid" to song.id, "cid" to song.cid.toString(), "fnval" to "16", "fnver" to "0", "fourk" to "0"))
        return data.getAsJsonObject("dash")?.getAsJsonArray("audio")?.map { it.asJsonObject }?.filter {
            it.get("codecs")?.asString?.startsWith("mp4a") == true
        }?.takeIf { it.isNotEmpty() } ?: throw IOException("此视频未提供可用音轨，可能需要登录或不支持音频播放")
    }

    override suspend fun resolve(song: Song, quality: MusicQuality): AudioResource = withContext(Dispatchers.IO) {
        val ready = if (song.cid == 0L) detail(song) else song
        val track = audio(ready).firstOrNull { it.get("bandwidth").asInt / 1000 == quality.bitrate }
            ?: throw IOException("此音质已变化，请重新打开详情选择音质")
        AudioResource(track.get("baseUrl")?.asString ?: track.get("base_url").asString, "m4a", headers)
    }

    companion object {
        fun durationSeconds(text: String): Int = text.split(':').fold(0) { acc, p -> acc * 60 + (p.trim().toIntOrNull() ?: 0) }
    }
}

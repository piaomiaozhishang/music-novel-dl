package com.wang.sonovel.music

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SavedLxSource(val id: String, val name: String, val qualities: List<String>, val actions: List<String>)

data class MusicSourceSettings(val kuwo: Boolean = false, val bilibili: Boolean = true, val lxEnabled: Boolean = false, val lxName: String = "", val lxQualities: List<String> = emptyList())

class MusicSources(private val context: Context) {
    private val prefs = context.getSharedPreferences("music_sources", Context.MODE_PRIVATE)
    private val script = File(context.filesDir, "lx-source.js")
    private val _settings = MutableStateFlow(MusicSourceSettings(false, prefs.getBoolean("bilibili",true), prefs.getBoolean("lx",false), prefs.getString("lxName","").orEmpty(), prefs.getString("qualities","").orEmpty().split(',').filter { it.isNotBlank() }))
    val settings = _settings.asStateFlow()
    private val directory=File(context.filesDir,"lx-sources").apply { mkdirs() }
    private val _scripts=MutableStateFlow(readScripts())
    val scripts=_scripts.asStateFlow()
    private fun readScripts(): List<SavedLxSource> = directory.listFiles().orEmpty().filter { it.extension=="json" }.mapNotNull {
        runCatching { Gson().fromJson(it.readText(),SavedLxSource::class.java) }.getOrNull()
    }.sortedBy { it.name }
    private fun saveScript(text:String,name:String,qualities:List<String>,actions:List<String>): SavedLxSource {
        val id=java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        val record=SavedLxSource(id,name,qualities,actions)
        File(directory,"$id.js").writeText(text)
        File(directory,"$id.json").writeText(Gson().toJson(record))
        return record
    }
    fun selectScript(id:String) {
        val record=_scripts.value.firstOrNull { it.id==id } ?: return
        val temp=File(context.filesDir,"lx-source.tmp")
        temp.writeText(File(directory,"$id.js").readText())
        check(temp.renameTo(script)) { "无法保存音源" }
        prefs.edit().putString("activeLx",id).putString("lxName",record.name).putString("qualities",record.qualities.joinToString(","))
            .putString("actions",record.actions.joinToString(",")).putBoolean("lx",true).commit()
        _settings.value=_settings.value.copy(lxName=record.name,lxQualities=record.qualities,lxEnabled=true)
    }
    val sources: List<MusicSource> = listOf(BilibiliSource())
    fun enabled() = sources.filter { if (it.id == "kuwo") _settings.value.kuwo else _settings.value.bilibili }
    fun source(id: String) = sources.firstOrNull { it.id == id } ?: error("来源已不可用")
    fun setEnabled(id: String, enabled: Boolean) {
        prefs.edit().putBoolean(id,enabled).apply()
        _settings.value = when(id) { "kuwo" -> _settings.value.copy(kuwo=enabled); "bilibili" -> _settings.value.copy(bilibili=enabled); else -> _settings.value.copy(lxEnabled=enabled) }
    }
    suspend fun importScript(text: String): String {
        require(text.length in 1..512000) { "脚本为空或超过 512 KB" }
        val init = LxRuntime.execute(context,text,null)
        val kw = com.google.gson.JsonParser.parseString(init).asJsonObject.getAsJsonObject("sources")?.getAsJsonObject("kw")
        require(kw != null && kw.getAsJsonArray("actions")?.any { it.asString == "musicUrl" } == true) { "脚本未提供酷我 musicUrl；当前先支持酷我曲库的洛雪解析" }
        val qualities = kw.getAsJsonArray("qualitys")?.map { it.asString }?.filter { it in listOf("128k","320k","flac","flac24bit") }.orEmpty()
        require(qualities.isNotEmpty()) { "脚本没有支持的音质（128k / 320k / flac）" }
        val name = Regex("@name\\s+([^\\r\\n*]+)").find(text)?.groupValues?.get(1)?.trim() ?: "洛雪自定义源"
        withContext(Dispatchers.IO) {
            if(script.exists() && prefs.getString("activeLx","").isNullOrBlank())
                saveScript(script.readText(),_settings.value.lxName,_settings.value.lxQualities,listOf("musicUrl"))
            val saved=saveScript(text,name,qualities,kw.getAsJsonArray("actions").map { it.asString })
            _scripts.value=readScripts()
            selectScript(saved.id)
        }
        return "已启用 $name · ${qualities.joinToString(" / ")}"
    }
    fun removeScript() {
        val id=prefs.getString("activeLx","").orEmpty()
        if(id.matches(Regex("[a-f0-9]{64}"))) { File(directory,"$id.js").delete();File(directory,"$id.json").delete() }
        script.delete(); prefs.edit().remove("activeLx").remove("lxName").remove("qualities").remove("actions").putBoolean("lx",false).apply()
        _scripts.value=readScripts()
        _settings.value = _settings.value.copy(lxName="",lxQualities=emptyList(),lxEnabled=false)
    }
    fun qualities(song: Song): List<MusicQuality> = if (song.source == "kuwo" && _settings.value.lxEnabled) _settings.value.lxQualities.map {
        when(it) { "flac24bit" -> MusicQuality("flac",3000); "flac" -> MusicQuality("flac",2000); "320k" -> MusicQuality("mp3",320); else -> MusicQuality("mp3",128) }
    } else song.qualities
    suspend fun resolve(song: Song, quality: MusicQuality): AudioResource {
        if (song.source != "kuwo" || !_settings.value.lxEnabled) return source(song.source).resolve(song,quality)
        require(script.exists()) { "请重新导入洛雪脚本" }
        val type = if (quality.format == "flac") { if(quality.bitrate==3000) "flac24bit" else "flac" } else "${quality.bitrate}k"
        require(type in _settings.value.lxQualities) { "当前洛雪源不支持此音质" }
        val url = request(song,"musicUrl",type)
        require(url.startsWith("https://") || url.startsWith("http://")) { "洛雪源未返回音频地址" }
        return AudioProbe.check(AudioResource(url,quality.format))
    }
    private suspend fun request(song:Song,action:String,type:String="128k"):String {
        val types=qualities(song).map { q->mapOf("type" to (if(q.format=="flac") { if(q.bitrate==3000) "flac24bit" else "flac" } else "${q.bitrate}k"),"size" to q.size) }
        val info = mapOf("source" to "kw", "action" to action, "info" to mapOf("type" to type, "musicInfo" to mapOf(
            "id" to song.id, "songmid" to song.id, "name" to song.title, "singer" to song.artist, "albumName" to song.album,
            "source" to "kw", "img" to song.cover, "interval" to "%02d:%02d".format(song.duration/60,song.duration%60), "types" to types, "_types" to types.associate { it.getValue("type") to mapOf("size" to it["size"]) })))
        return LxRuntime.execute(context,withContext(Dispatchers.IO) { script.readText() },Gson().toJson(info))
    }
    suspend fun metadata(song:Song,action:String):String? {
        if(song.source!="kuwo" || !_settings.value.lxEnabled || action !in prefs.getString("actions","").orEmpty().split(',')) return null
        val value=request(song,action)
        if(action=="pic") return value.takeIf { it.startsWith("http") }
        return runCatching { JsonParser.parseString(value).asJsonObject.get("lyric")?.asString }.getOrNull() ?: value
    }
}

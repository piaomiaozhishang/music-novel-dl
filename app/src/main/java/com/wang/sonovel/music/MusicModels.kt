package com.wang.sonovel.music

data class MusicQuality(val format: String, val bitrate: Int, val size: String = "") {
    val label: String get() = when {
        format == "flac" && bitrate == 3000 -> "Hi-Res · 24bit FLAC"
        format == "flac" -> "无损 FLAC"
        else -> "${format.uppercase()} · ${bitrate}kbps"
    }
}

data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val cover: String,
    val duration: Int,
    val qualities: List<MusicQuality>,
    val source: String = "kuwo",
    val description: String = "",
    val webUrl: String = "",
    val cid: Long = 0,
)

data class MusicPage(val songs: List<Song>, val hasMore: Boolean)
data class AudioResource(val url: String, val format: String, val headers: Map<String, String> = emptyMap())

interface MusicSource {
    val id: String
    val name: String
    suspend fun search(keyword: String, page: Int = 0): MusicPage
    suspend fun detail(song: Song): Song = song
    suspend fun resolve(song: Song, quality: MusicQuality): AudioResource
}

fun musicGson(): com.google.gson.Gson = com.google.gson.GsonBuilder()
    .registerTypeAdapter(Song::class.java, com.google.gson.InstanceCreator<Song> { Song("","","","","",0,emptyList()) }).create()

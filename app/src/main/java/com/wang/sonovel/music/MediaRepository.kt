package com.wang.sonovel.music

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.wang.sonovel.data.SearchResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SavedMedia(val songs: List<Song> = emptyList(), val books: List<SearchResult> = emptyList())

class MediaRepository(context: Context) {
    private val prefs = context.getSharedPreferences("media_library", Context.MODE_PRIVATE)
    private val gson = musicGson()
    private val _saved = MutableStateFlow(runCatching { gson.fromJson(prefs.getString("saved", "{}"), SavedMedia::class.java) }.getOrNull() ?: SavedMedia())
    val saved = _saved.asStateFlow()
    private val known = runCatching { gson.fromJson<MutableMap<String, Song>>(prefs.getString("songs", "{}"), object : TypeToken<MutableMap<String, Song>>() {}.type) }.getOrNull() ?: mutableMapOf()
    fun remember(song: Song): String {
        val key = "${song.source}:${song.id}"
        known.remove(key); known[key] = song
        while (known.size > 100) known.remove(known.keys.first())
        prefs.edit().putString("songs", gson.toJson(known)).apply()
        return key
    }
    fun song(key: String): Song? = known[key] ?: _saved.value.songs.firstOrNull { "${it.source}:${it.id}" == key }
    fun toggle(song: Song) {
        remember(song)
        val old = _saved.value
        val exists = old.songs.any { it.source == song.source && it.id == song.id }
        _saved.value = old.copy(songs = if (exists) old.songs.filterNot { it.source == song.source && it.id == song.id } else listOf(song)+old.songs)
        persist()
    }
    fun toggle(book: SearchResult) {
        val old = _saved.value
        _saved.value = old.copy(books = if (old.books.any { it.url == book.url && it.sourceKey == book.sourceKey }) old.books.filterNot { it.url == book.url && it.sourceKey == book.sourceKey } else listOf(book)+old.books)
        persist()
    }
    private fun persist() { prefs.edit().putString("saved", gson.toJson(_saved.value)).apply() }
}

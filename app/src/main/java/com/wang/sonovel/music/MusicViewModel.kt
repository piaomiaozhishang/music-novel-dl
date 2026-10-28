package com.wang.sonovel.music

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wang.sonovel.graph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

data class MusicSearchState(
    val query: String = "", val searched: String = "", val songs: List<Song> = emptyList(),
    val searching: Boolean = false, val page: Int = 0, val hasMore: Boolean = false,
    val error: String? = null, val busy: String? = null, val message: String? = null,
)

class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val sources = app.graph.musicSources
    val downloads = app.graph.musicDownloads
    private val _state = MutableStateFlow(MusicSearchState())
    val state = _state.asStateFlow()
    private var searchJob: Job? = null

    fun query(text: String) { _state.value = _state.value.copy(query = text) }
    fun clearMessage() { _state.value = _state.value.copy(message = null) }

    fun search(more: Boolean = false) {
        val keyword = if (more) _state.value.searched else _state.value.query.trim()
        if (keyword.isBlank()) return
        if (more && (_state.value.searching || !_state.value.hasMore)) return
        searchJob?.cancel()
        val page = if (more) _state.value.page + 1 else 0
        _state.value = _state.value.copy(searching = true, error = null, searched = keyword,
            songs = if (more) _state.value.songs else emptyList(), hasMore = if (more) _state.value.hasMore else false)
        searchJob = viewModelScope.launch {
            try {
                val enabled = sources.enabled().filter { !Regex("BV[0-9A-Za-z]{10}").containsMatchIn(keyword) || it.id=="bilibili" }
                require(enabled.isNotEmpty()) { "请先在来源页开启音乐源" }
                _state.value = _state.value.copy(hasMore=false)
                coroutineScope { enabled.map { source -> async {
                    try {
                        val result = source.search(keyword,page)
                        _state.value = _state.value.copy(songs = (_state.value.songs + result.songs).distinctBy { it.source to it.id }, hasMore = _state.value.hasMore || result.hasMore)
                    }
                    catch(e: CancellationException) { throw e }
                    catch(e: Exception) { _state.value = _state.value.copy(error = listOfNotNull(_state.value.error,source.name+"："+e.message).joinToString("\n")) }
                } }.awaitAll() }
                _state.value = _state.value.copy(searching = false, page = page)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.value = _state.value.copy(searching = false, error = e.message ?: "搜索失败，请重试") }
        }
    }

    fun act(song: Song, quality: MusicQuality, download: Boolean) = operation(song.id) {
        val resource = sources.resolve(song, quality)
        if (download) {
            downloads.enqueue(song, quality, resource)
            "已加入下载，可在“下载”中查看"
        } else {
            val key = app.graph.media.remember(song)
            MusicPlaybackService.play(app, song.title, song.artist, resource.url, resource.headers, key)
            "正在打开 ${song.title}"
        }
    }

    fun retry(task: MusicDownload) = operation(task.song.id) {
        downloads.retry(task.systemId, sources.resolve(task.song, task.quality))
        "已重新获取地址并下载"
    }

    fun playLocal(task: MusicDownload) = operation(task.song.id) {
        MusicPlaybackService.play(app, task.song.title, task.song.artist, downloads.uri(task.systemId).toString(), songKey = app.graph.media.remember(task.song))
        "正在播放已下载歌曲"
    }

    fun share(task: MusicDownload) = operation(task.song.id) {
        val intent = Intent(Intent.ACTION_SEND).setType("audio/*")
            .putExtra(Intent.EXTRA_STREAM, downloads.uri(task.systemId))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        app.startActivity(Intent.createChooser(intent, "分享音乐文件").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        null
    }

    private fun operation(id: String, block: suspend () -> String?) {
        if (_state.value.busy != null) return
        _state.value = _state.value.copy(busy = id)
        viewModelScope.launch {
            try {
                val message = block()
                _state.value = _state.value.copy(message = message)
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.value = _state.value.copy(message = e.message ?: "操作失败，请重试") }
            finally { _state.value = _state.value.copy(busy = null) }
        }
    }
}

package com.wang.sonovel.music

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MusicPreferences(val embedLyrics: Boolean = true, val embedCover: Boolean = true, val metered: Boolean = true)

class MusicPreferencesRepository(context: Context) {
    private val prefs = context.getSharedPreferences("music_sources", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(MusicPreferences(prefs.getBoolean("embedLyrics", true), prefs.getBoolean("embedCover", true), prefs.getBoolean("metered", true)))
    val state = mutable.asStateFlow()
    fun update(change: (MusicPreferences) -> MusicPreferences) {
        val value = change(mutable.value)
        prefs.edit().putBoolean("embedLyrics", value.embedLyrics).putBoolean("embedCover", value.embedCover).putBoolean("metered", value.metered).apply()
        mutable.value = value
    }
    fun reset() = update { MusicPreferences() }
}

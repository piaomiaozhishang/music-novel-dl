package com.wang.sonovel.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.wang.sonovel.MainActivity
import com.wang.sonovel.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MusicPlaybackState(val title: String = "", val artist: String = "", val loading: Boolean = false, val playing: Boolean = false, val error: String? = null, val songKey: String = "", val position: Int = 0, val duration: Int = 0)

class MusicPlaybackService : Service(), AudioManager.OnAudioFocusChangeListener {
    private var player: MediaPlayer? = null
    private lateinit var session: MediaSession
    private lateinit var audio: AudioManager
    private var focus: AudioFocusRequest? = null
    private var resumeAfterFocus = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (!state.value.loading) runCatching { player?.let { state.value = state.value.copy(position = it.currentPosition, duration = it.duration) } }
            handler.postDelayed(this, 500)
        }
    }
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { pause() }
    }

    override fun onCreate() {
        super.onCreate()
        handler.post(ticker)
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        session = MediaSession(this, "书歌").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { resume() }
                override fun onPause() { pause() }
                override fun onStop() { stopSelf() }
                override fun onSeekTo(pos: Long) { player?.seekTo(pos.toInt()) }
            })
            isActive = true
        }
        if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CHANNEL, "音乐播放", NotificationManager.IMPORTANCE_LOW),
            )
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes).setOnAudioFocusChangeListener(this).build()
        }
        ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            PLAY -> {
                player?.release()
                player = null
                state.value = MusicPlaybackState(intent.getStringExtra("title").orEmpty(), intent.getStringExtra("artist").orEmpty(), loading = true, songKey = intent.getStringExtra("songKey").orEmpty())
                startForeground(NOTIFICATION, notification())
                try {
                    val uri = Uri.parse(requireNotNull(intent.getStringExtra("url")))
                    val next = MediaPlayer()
                    player = next
                    next.setAudioAttributes(attributes)
                    val bundle = intent.getBundleExtra("headers")
                    val headers = bundle?.keySet()?.associateWith { bundle.getString(it).orEmpty() }.orEmpty()
                    next.setDataSource(this, uri, headers)
                    next.setOnPreparedListener {
                        if (it === player) {
                            state.value = state.value.copy(loading = false)
                            resume()
                        }
                    }
                    next.setOnCompletionListener { stopSelf() }
                    next.setOnErrorListener { _, _, _ -> fail("此音频无法播放，请更换音质或歌曲"); true }
                    next.prepareAsync()
                } catch (e: Exception) { fail("无法打开音频：${e.message.orEmpty()}") }
            }
            TOGGLE -> if (state.value.playing) pause() else resume()
            SEEK -> if (!state.value.loading) player?.seekTo(intent.getIntExtra("position",0))
            STOP -> stopSelf()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun resume() {
        val current = player ?: return
        if (state.value.loading) return
        @Suppress("DEPRECATION")
        val granted = if (Build.VERSION.SDK_INT >= 26) audio.requestAudioFocus(focus!!) else
            audio.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        if (granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            state.value = state.value.copy(error = "其他应用正在使用音频，请稍后播放")
            update()
            return
        }
        try {
            current.setVolume(1f, 1f)
            current.start()
            state.value = state.value.copy(playing = true, error = null)
            update()
        } catch (e: Exception) { fail("播放失败，请重新打开歌曲") }
    }

    private fun pause() {
        resumeAfterFocus = false
        if (!state.value.playing) return
        player?.pause()
        state.value = state.value.copy(playing = false)
        update()
    }

    override fun onAudioFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> { pause(); abandonFocus() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                val wasPlaying = state.value.playing
                pause()
                resumeAfterFocus = wasPlaying
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player?.setVolume(.2f, .2f)
            AudioManager.AUDIOFOCUS_GAIN -> {
                player?.setVolume(1f, 1f)
                if (resumeAfterFocus) { resumeAfterFocus = false; resume() }
            }
        }
    }

    private fun fail(message: String) {
        state.value = state.value.copy(error = message, playing = false, loading = false)
        stopSelf()
    }

    private fun update() {
        val current = state.value
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP)
            .setState(if (current.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, 0, if (current.playing) 1f else 0f).build())
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION, notification())
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 70, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_TAB, "music"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun action(name: String, code: Int) = PendingIntent.getService(this, code, Intent(this, MusicPlaybackService::class.java).setAction(name), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val current = state.value
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(current.title).setContentText(if (current.loading) "正在载入…" else current.artist)
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(current.playing || current.loading)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(if (current.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (current.playing) "暂停" else "播放", action(TOGGLE, 71))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", action(STOP, 72))
            .build()
    }

    @Suppress("DEPRECATION")
    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= 26) focus?.let { audio.abandonAudioFocusRequest(it) } else audio.abandonAudioFocus(this)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        abandonFocus()
        session.release()
        unregisterReceiver(noisy)
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        state.value = MusicPlaybackState(error = state.value.error)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "music_playback"
        private const val NOTIFICATION = 700
        private const val PLAY = "music.PLAY"
        private const val TOGGLE = "music.TOGGLE"
        private const val STOP = "music.STOP"
        private const val SEEK = "music.SEEK"
        private val state = MutableStateFlow(MusicPlaybackState())
        val playback = state.asStateFlow()
        fun play(context: Context, title: String, artist: String, url: String, headers: Map<String, String> = emptyMap(), songKey: String = "") {
            ContextCompat.startForegroundService(context, Intent(context, MusicPlaybackService::class.java)
                .setAction(PLAY).putExtra("title", title).putExtra("artist", artist).putExtra("url", url)
                .putExtra("songKey",songKey).putExtra("headers",android.os.Bundle().apply { headers.forEach { (k,v) -> putString(k,v) } }))
        }
        fun seek(context: Context, position: Int) { context.startService(Intent(context, MusicPlaybackService::class.java).setAction(SEEK).putExtra("position",position)) }
        fun toggle(context: Context) { context.startService(Intent(context, MusicPlaybackService::class.java).setAction(TOGGLE)) }
        fun stop(context: Context) { context.stopService(Intent(context, MusicPlaybackService::class.java)) }
    }
}

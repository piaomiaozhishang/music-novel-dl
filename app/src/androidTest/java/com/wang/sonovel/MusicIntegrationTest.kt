package com.wang.sonovel

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wang.sonovel.music.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class MusicIntegrationTest {
    @Test fun downloadsRestoreAndPlayWithSystemService() = verifyDownload("test-tone.mp3", "mp3")
    @Test fun m4aDownloadsAndStreamsWithRequiredReferer() = verifyDownload("test-tone.m4a", "m4a")

    private fun verifyDownload(asset: String, format: String) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        val payload = instrumentation.context.assets.open(asset).use { it.readBytes() }
        val image=android.graphics.Bitmap.createBitmap(24,24,android.graphics.Bitmap.Config.ARGB_8888)
        image.eraseColor(android.graphics.Color.BLUE)
        val imageBytes=java.io.ByteArrayOutputStream().also { image.compress(android.graphics.Bitmap.CompressFormat.JPEG,90,it) }.toByteArray()
        image.recycle()
        val preferences=context.graph.musicPreferences
        val previousPreferences=preferences.state.value
        preferences.update { it.copy(embedLyrics=false,embedCover=format=="m4a") }
        val headers = if(format=="m4a") mapOf("Referer" to "https://www.bilibili.com/") else emptyMap()
        val running = AtomicBoolean(true)
        val server = ServerSocket(0)
        val serving = thread(isDaemon = true) {
            while (running.get()) {
                runCatching {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader()
                        var line = reader.readLine()
                        val received = mutableListOf<String>()
                        while (line != null && line.isNotEmpty()) { received += line;line = reader.readLine() }
                        val out = socket.getOutputStream()
                        if(received.firstOrNull()?.contains("/cover.jpg")==true) {
                            out.write(("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: "+imageBytes.size+"\r\nConnection: close\r\n\r\n").toByteArray())
                            out.write(imageBytes);out.flush();return@use
                        }
                        if(format=="m4a" && received.none { it.equals("Referer: https://www.bilibili.com/",true) }) {
                            out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            return@use
                        }
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: audio/mpeg\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(payload)
                        out.flush()
                    }
                }
            }
        }
        val downloads = context.graph.musicDownloads
        val quality = MusicQuality(format, 128)
        val song = Song("900000001", "集成测试音频", "本地生成", "测试", "http://127.0.0.1:"+server.localPort+"/cover.jpg", 6, listOf(quality),source="bilibili")
        var id: Long? = null
        try {
            val resource = AudioResource("http://127.0.0.1:"+server.localPort+"/"+asset, format, headers)
            id = downloads.enqueue(song, quality, resource)
            preferences.update { it.copy(embedLyrics=false,embedCover=false) }
            if (InstrumentationRegistry.getArguments().getString("forceDownloadJob") == "true") {
                // Some emulators cannot validate Google's connectivity probe. Only in this
                // opt-in test mode, ask the scheduler to run the loopback fixture job now.
                Thread.sleep(750)
                instrumentation.uiAutomation.executeShellCommand("cmd jobscheduler run -f com.android.providers.downloads $id").close()
            }
            val deadline = System.currentTimeMillis() + 30000
            while (System.currentTimeMillis() < deadline) {
                downloads.refresh()
                if (downloads.tasks.value.first { it.systemId == id }.complete) break
                Thread.sleep(200)
            }
            val task = downloads.tasks.value.first { it.systemId == id }
            assertEquals("${task.reason}", DownloadManager.STATUS_SUCCESSFUL, task.status)
            assertEquals(payload.size.toLong(), task.bytes)
            if(format=="m4a") {
                val tagDeadline=System.currentTimeMillis()+65000
                while(System.currentTimeMillis()<tagDeadline && downloads.tasks.value.first { it.systemId==id }.tagState !in listOf("done","partial","failed")) Thread.sleep(200)
                val tagged=downloads.tasks.value.first { it.systemId==id }
                assertTrue("Download retains its own cover preference",tagged.embedCover)
                assertEquals(tagged.tagMessage,"done",tagged.tagState)
                assertNotNull(org.jaudiotagger.audio.AudioFileIO.read(downloads.file(tagged)).tag.firstArtwork)
            } else {
                assertFalse(task.embedLyrics);assertFalse(task.embedCover)
                assertArrayEquals("With both options off the file is untouched",payload,downloads.file(task).readBytes())
            }
            val restored = MusicDownloads(context)
            restored.refresh()
            assertTrue(restored.tasks.value.any { it.systemId == id && it.complete })
            if(format=="m4a") {
                instrumentation.runOnMainSync { MusicPlaybackService.play(context,song.title,song.artist,resource.url,resource.headers) }
                waitUntil { MusicPlaybackService.playback.value.playing }
                assertTrue("M4A remote playback with Referer",MusicPlaybackService.playback.value.playing)
                instrumentation.runOnMainSync { MusicPlaybackService.stop(context) }
                waitUntil { MusicPlaybackService.playback.value.title.isBlank() }
            }
            instrumentation.runOnMainSync { MusicPlaybackService.play(context, song.title, song.artist, downloads.uri(requireNotNull(id)).toString()) }
            waitUntil { MusicPlaybackService.playback.value.playing }
            assertTrue(MusicPlaybackService.playback.value.playing)
            // Reading/navigation is independent from the foreground media service.
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_OPEN_TAB, "library"))
            instrumentation.waitForIdleSync()
            assertTrue(MusicPlaybackService.playback.value.playing)
            instrumentation.runOnMainSync { MusicPlaybackService.toggle(context) }
            waitUntil { !MusicPlaybackService.playback.value.playing }
            assertFalse(MusicPlaybackService.playback.value.playing)
            instrumentation.runOnMainSync { MusicPlaybackService.stop(context) }
            waitUntil { MusicPlaybackService.playback.value.title.isBlank() }
            assertTrue(MusicPlaybackService.playback.value.title.isBlank())
        } finally {
            preferences.update { previousPreferences }
            instrumentation.runOnMainSync { MusicPlaybackService.stop(context) }
            id?.let {
                (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(it)
                downloads.refresh()
                downloads.remove(it)
            }
            running.set(false)
            server.close()
            serving.join(1000)
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
    }
}

package com.wang.sonovel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wang.sonovel.music.BilibiliSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BilibiliLiveTest {
    @Test fun searchBvDetailsAndAudioHeader()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveMusic")=="true")
        val source=BilibiliSource()
        val results=source.search("piano")
        assertTrue(results.songs.isNotEmpty())
        val song=source.search("BV1GJ411x7h7").songs.single()
        assertTrue(song.cid>0)
        assertTrue(song.qualities.isNotEmpty())
        val resource=source.resolve(song,song.qualities.last())
        assertEquals("m4a",resource.format)
        assertEquals("https://www.bilibili.com/",resource.headers["Referer"])
        val request=Request.Builder().url(resource.url).header("Range","bytes=0-15")
        resource.headers.forEach{(k,v)->request.header(k,v)}
        OkHttpClient.Builder().callTimeout(20,TimeUnit.SECONDS).build().newCall(request.build()).execute().use{response->
            assertTrue(response.isSuccessful)
            val bytes=ByteArray(12)
            assertEquals(12,response.body!!.byteStream().read(bytes))
            assertEquals("ftyp",String(bytes,4,4,Charsets.US_ASCII))
        }
    }
}

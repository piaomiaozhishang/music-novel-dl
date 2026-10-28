package com.wang.sonovel.music

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object AudioProbe {
    private val client=OkHttpClient.Builder().callTimeout(12,TimeUnit.SECONDS).build()
    fun format(header:ByteArray):String? {
        val first=String(header.take(4).toByteArray(),Charsets.US_ASCII)
        return when {
            first.startsWith("ID3")->"mp3"
            first=="fLaC"->"flac"
            first=="OggS"->"ogg"
            header.size>=8 && String(header,4,4,Charsets.US_ASCII)=="ftyp"->"m4a"
            header.size>=2 && header[0].toInt() and 255 == 255 && header[1].toInt() and 246 == 240 -> "aac"
            header.size>=2 && header[0].toInt() and 255 == 255 && header[1].toInt() and 224 == 224 -> "mp3"
            else->null
        }
    }
    suspend fun check(resource:AudioResource):AudioResource = suspendCancellableCoroutine { continuation ->
        val builder=Request.Builder().url(resource.url).header("Range","bytes=0-15")
        resource.headers.forEach { (k,v)->builder.header(k,v) }
        val call=client.newCall(builder.build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object:Callback {
            override fun onFailure(call:Call,e:IOException) { if(continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call:Call,response:Response) {
                val result=runCatching { response.use { r ->
                    check(r.isSuccessful) { "音源音频地址返回 HTTP ${r.code}" }
                    val buffer=ByteArray(16);var size=0
                    r.body!!.byteStream().use { input->while(size<buffer.size) { val n=input.read(buffer,size,buffer.size-size);if(n<0)break;size+=n } }
                    val format=format(buffer.copyOf(size)) ?: error("音源返回的内容不是可识别的音频")
                    require(resource.format!="flac" || format=="flac") { "音源返回了 ${format.uppercase()}，未提供所选的无损格式" }
                    resource.copy(format=format)
                } }
                if(continuation.isActive) result.fold({continuation.resume(it)},{continuation.resumeWithException(it)})
            }
        })
    }
}

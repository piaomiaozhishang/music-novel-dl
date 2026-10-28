package com.wang.sonovel.core

import com.wang.sonovel.music.readBytesLimited
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/** Shared bounded decoder for both import entry points. Call on an IO dispatcher. */
object SourceImportInput {
    private const val LIMIT = 16 * 1024 * 1024
    private val client = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()
    fun decode(input: InputStream): String {
        val bytes=input.readBytesLimited(LIMIT)
        val charset=when {
            bytes.size>=2 && bytes[0]==0xff.toByte() && bytes[1]==0xfe.toByte() -> Charsets.UTF_16LE
            bytes.size>=2 && bytes[0]==0xfe.toByte() && bytes[1]==0xff.toByte() -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        return String(bytes,charset).trim().removePrefix("\uFEFF").trim()
    }
    fun address(input:String):String {
        val text=input.trim().removePrefix("\uFEFF").trim()
        if(!text.startsWith("legado://",true)) return text
        val uri=URI(text)
        require(uri.host=="import" && uri.path.trim('/')=="bookSource") { "请选择阅读的书源导入链接" }
        val src=uri.rawQuery.orEmpty().split('&').firstOrNull { it.substringBefore('=')=="src" }?.substringAfter('=')
        require(!src.isNullOrBlank()) { "阅读分享链接缺少书源地址" }
        return URLDecoder.decode(src,"UTF-8")
    }
    fun resolve(input:String):String {
        val text=address(input)
        val result=if(text.startsWith("http://") || text.startsWith("https://")) {
            client.newCall(Request.Builder().url(text).build()).execute().use { response ->
                require(response.isSuccessful) { "下载书源失败：HTTP "+response.code }
                decode(requireNotNull(response.body) { "书源文件为空" }.byteStream())
            }
        } else text
        require(result.length<=LIMIT) { "源文件超过 16 MB" }
        require(result.isNotBlank()) { "源文件为空" }
        require(!result.startsWith("<!DOCTYPE",true) && !result.startsWith("<html",true)) { "这个地址返回网页，请复制书源 JSON 下载链接" }
        return result
    }
}

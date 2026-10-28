package com.wang.sonovel.music

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.whl.quickjs.wrapper.QuickJSContext
import com.whl.quickjs.wrapper.JSCallFunction
import com.wang.sonovel.core.JsEngine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal object LxEvaluator {
    fun evaluate(script: String, request: String?): String {
        JsEngine.init()
        val gson=Gson()
        val client=OkHttpClient.Builder().callTimeout(15,TimeUnit.SECONDS).build()
        val ctx=QuickJSContext.create()
        val calls=ConcurrentHashMap<Int,Call>()
        val replies=ConcurrentLinkedQueue<Map<String,Any?>>()
        val serial=AtomicInteger()
        try {
            ctx.setMemoryLimit(48*1024*1024); ctx.setMaxStackSize(1024*1024)
            val global=ctx.globalObject
            global.setProperty("__nativeUtil",JSCallFunction { args ->
                gson.toJson(runCatching { mapOf("value" to LxUtilities.call(args[0].toString())) }.getOrElse { mapOf("error" to it.message) })
            })
            global.setProperty("__poll",JSCallFunction { _ ->
                val result=mutableListOf<Map<String,Any?>>()
                while(true) result += replies.poll() ?: break
                gson.toJson(result)
            })
            global.setProperty("__abort",JSCallFunction { args -> calls.remove(args[0].toString().toDouble().toInt())?.cancel(); null })
            global.setProperty("__http",JSCallFunction { args ->
                val id=serial.incrementAndGet()
                require(id<=80) { "音源请求次数过多" }
                runCatching {
                    val p=JsonParser.parseString(args[0].toString()).asJsonObject
                    val url=p.get("url").asString
                    require(url.startsWith("http://") || url.startsWith("https://")) { "仅支持 HTTP 音源请求" }
                    val o=p.getAsJsonObject("options")
                    val b=Request.Builder().url(url)
                    o?.getAsJsonObject("headers")?.entrySet()?.forEach { (k,v) -> b.header(k,v.asString) }
                    val method=o?.get("method")?.asString?.uppercase() ?: "GET"
                    val form=o?.getAsJsonObject("form")
                    val multipart=o?.getAsJsonObject("formData")
                    val body=when {
                        form!=null -> FormBody.Builder().apply { form.entrySet().forEach { (k,v)->add(k,v.asString) } }.build()
                        multipart!=null -> MultipartBody.Builder().setType(MultipartBody.FORM).apply { multipart.entrySet().forEach { (k,v)->addFormDataPart(k,v.asString) } }.build()
                        else -> o?.get("body")?.let {
                            if(it.isJsonObject && it.asJsonObject.get("type")?.asString=="Buffer")
                                it.asJsonObject.getAsJsonArray("data").map { n->n.asInt.toByte() }.toByteArray().toRequestBody()
                            else (if(it.isJsonPrimitive) it.asString else it.toString()).toRequestBody((b.build().header("Content-Type") ?: "application/json").toMediaType())
                        }
                    }
                    b.method(method,if(method in listOf("GET","HEAD")) null else body ?: ByteArray(0).toRequestBody())
                    val call=client.newCall(b.build())
                    o?.get("timeout")?.takeIf { it.isJsonPrimitive }?.asLong?.let { call.timeout().timeout(it.coerceIn(1,15000),TimeUnit.MILLISECONDS) }
                    calls[id]=call
                    call.enqueue(object: Callback {
                        override fun onFailure(call:Call,e:java.io.IOException) { if(calls.remove(id)!=null) replies.add(mapOf("id" to id,"error" to e.message)) }
                        override fun onResponse(call:Call,response:Response) {
                            val result=runCatching { response.use { r ->
                                val data=r.body?.byteStream()?.use { it.readBytesLimited(4*1024*1024) } ?: byteArrayOf()
                                val raw=String(data,r.body?.contentType()?.charset() ?: Charsets.UTF_8)
                                val parsed=runCatching { JsonParser.parseString(raw) }.getOrNull()
                                mapOf("id" to id,"statusCode" to r.code,"statusMessage" to r.message,
                                    "headers" to r.headers.names().associateWith { r.header(it) },
                                    "bytes" to data.map { it.toInt() and 255 }, "raw" to raw,
                                    "binary" to (o?.has("encoding")==true && o.get("encoding").isJsonNull),
                                    "body" to (parsed ?: raw))
                            } }.getOrElse { mapOf("id" to id,"error" to it.message) }
                            if(calls.remove(id)!=null) replies.add(result)
                        }
                    })
                }.onFailure { replies.add(mapOf("id" to id,"error" to (it.message ?: "网络错误"))) }
                id
            })
            ctx.evaluate(LxUtilities.javascript)
            ctx.evaluate(BOOT)
            val metadata=listOf("name","description","version","author","homepage").associateWith { key ->
                Regex("@$key\\s+([^\\r\\n*]+)").find(script)?.groupValues?.get(1)?.trim().orEmpty()
            } + ("rawScript" to script)
            ctx.evaluate("lx.currentScriptInfo="+gson.toJson(metadata))
            ctx.evaluate(script,"imported-source.js")
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(22)
            fun pumpUntil(test:()->Boolean) {
                while(System.nanoTime()<deadline) {
                    ctx.evaluate("__pump()")
                    if(test()) return
                    Thread.sleep(10)
                }
                val notice=ctx.evaluate("__updateNotice")?.toString().orEmpty()
                error(if(notice.isNotBlank()) "音源提示："+notice.take(240) else "脚本响应超时，请检查音源服务是否可用")
            }
            pumpUntil { ctx.evaluate("__init !== null") == true }
            require(ctx.evaluate("typeof __listener === 'function'")==true) { "脚本没有注册请求事件" }
            if(request==null) return ctx.evaluate("JSON.stringify(__init)")!!.toString()
            ctx.evaluate("Promise.resolve().then(()=>__listener("+request+")).then(v=>{__result=typeof v==='string'?v:JSON.stringify(v);__done=true},e=>{__error=String(e);__done=true})")
            pumpUntil { ctx.evaluate("__done") == true }
            val error=ctx.evaluate("__error")?.toString().orEmpty()
            require(error.isBlank()) { error }
            return ctx.evaluate("__result")?.toString().orEmpty()
        } finally {
            calls.values.forEach { it.cancel() }; calls.clear()
            ctx.destroy(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }
    private val BOOT="""
        var window=globalThis;var __init=null,__listener=null,__result='',__error='',__updateNotice='',__done=false,__timers={},__seq=0,__callbacks={};
        var console={log(){},warn(){},error(){},info(){},debug(){}};
        function setTimeout(f,t,...args){var id=++__seq;__timers[id]={f:()=>f(...args),at:Date.now()+Math.max(0,Number(t)||0)};return id}
        function clearTimeout(id){delete __timers[id]}
        function setInterval(f,t,...args){var id=++__seq;var tick=()=>{f(...args);if(__timers[id])__timers[id].at=Date.now()+Math.max(10,t||0)};__timers[id]={f:tick,at:Date.now()+Math.max(10,t||0),repeat:true};return id}
        var clearInterval=clearTimeout;
        function __pump(){
          for(var r of JSON.parse(__poll())){var cb=__callbacks[r.id];delete __callbacks[r.id];if(!cb)continue;
            if(r.error)cb(Error(r.error),null,null);else{r.bytes=Buffer.from(r.bytes);if(r.binary)r.body=r.bytes;cb(null,r,r.body)}}
          for(var id of Object.keys(__timers)){var t=__timers[id];if(t&&t.at<=Date.now()){if(!t.repeat)delete __timers[id];t.f()}}
        }
        var lx={env:'mobile',version:'2.0.0',currentScriptInfo:{},
          EVENT_NAMES:{request:'request',inited:'inited',updateAlert:'updateAlert'},
          on(n,f){if(n==='request')__listener=f;return Promise.resolve()},
          send(n,d){if(n==='inited')__init=d;if(n==='updateAlert')__updateNotice=String(d&&d.log||'音源要求更新');return Promise.resolve()},
          request(url,options,cb){if(typeof options==='function'){cb=options;options={}}
            var id=__http(JSON.stringify({url,options:options||{}}));__callbacks[id]=cb;
            return ()=>{delete __callbacks[id];__abort(id)}},
          utils:__utils
        };
    """.trimIndent()
}

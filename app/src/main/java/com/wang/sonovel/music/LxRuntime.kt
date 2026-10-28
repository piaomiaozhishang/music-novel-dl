package com.wang.sonovel.music

import android.app.Service
import android.content.*
import android.os.*
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.whl.quickjs.wrapper.QuickJSContext
import com.whl.quickjs.wrapper.JSCallFunction
import com.wang.sonovel.core.JsEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object LxRuntime {
    private val mutex = Mutex()
    suspend fun execute(context: Context, script: String, request: String?): String = mutex.withLock {
        withContext(Dispatchers.Main) {
            withTimeout(28_000) {
                suspendCancellableCoroutine { cont ->
                    var remote: Messenger? = null
                    var bound = false
                    lateinit var connection: ServiceConnection
                    fun close(abort: Boolean = false) {
                        if(abort) runCatching { remote?.send(Message.obtain(null,2)) }
                        if (bound) { runCatching { context.unbindService(connection) }; bound=false }
                    }
                    val reply = Messenger(Handler(Looper.getMainLooper()) { msg ->
                        if (cont.isActive) {
                            val error = msg.data.getString("error")
                            if (error != null) cont.resumeWithException(IllegalArgumentException(error))
                            else cont.resume(msg.data.getString("result").orEmpty())
                        }
                        close(); true
                    })
                    connection = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                            remote = Messenger(binder)
                            if (!cont.isActive) { close(); return }
                            remote!!.send(Message.obtain(null,1).apply {
                                replyTo=reply; data=Bundle().apply { putString("script",script); putString("request",request) }
                            })
                        }
                        override fun onServiceDisconnected(name: ComponentName?) {
                            if (cont.isActive) cont.resumeWithException(IllegalStateException("脚本超时或运行失败，请更换音源脚本"))
                            close()
                        }
                    }
                    bound=context.bindService(Intent(context,LxScriptService::class.java),connection,Context.BIND_AUTO_CREATE)
                    if (!bound && cont.isActive) cont.resumeWithException(IllegalStateException("无法启动音源脚本"))
                    cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { close(true) } }
                }
            }
        }
    }
}

/** Disposable process with a deadline; imported code receives no filesystem or Android bridge. */
class LxScriptService : Service() {
    @Volatile private var running = false
    private val worker=Executors.newSingleThreadExecutor()
    private val handler=Handler(Looper.getMainLooper())
    private val kill=Runnable { android.os.Process.killProcess(android.os.Process.myPid()) }
    private val messenger=Messenger(Handler(Looper.getMainLooper()) { msg ->
        if (msg.what == 2) { handler.post(kill); true }
        else {
            val script=msg.data.getString("script").orEmpty()
            val request=msg.data.getString("request")
            val reply=msg.replyTo
            handler.postDelayed(kill,25_000)
            running = true
            worker.execute {
                val result=runCatching { evaluate(script,request) }
                running = false
                handler.removeCallbacks(kill)
                runCatching { reply.send(Message.obtain().apply { data=Bundle().apply {
                    result.fold({ putString("result",it) },{ putString("error","洛雪脚本：" + it.message?.take(240)) })
                } }) }
            }
            true
        }
    })
    override fun onBind(intent: Intent?)=messenger.binder
    override fun onDestroy() {
        if(running) android.os.Process.killProcess(android.os.Process.myPid())
        else handler.removeCallbacks(kill)
        worker.shutdownNow(); super.onDestroy()
    }

    private fun evaluate(script: String, request: String?): String = LxEvaluator.evaluate(script,request)
}

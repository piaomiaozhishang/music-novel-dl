package com.wang.sonovel.download

import android.content.Context
import androidx.core.content.ContextCompat
import com.wang.sonovel.core.Analytics
import com.wang.sonovel.core.BookParser
import com.wang.sonovel.core.ChapterParser
import com.wang.sonovel.core.ChapterProcessor
import com.wang.sonovel.core.CoverFetcher
import com.wang.sonovel.core.SourceContext
import com.wang.sonovel.core.TocParser
import com.wang.sonovel.core.export.Exporters
import com.wang.sonovel.data.BookInfo
import com.wang.sonovel.data.ChapterContent
import com.wang.sonovel.data.ChapterRef
import com.wang.sonovel.data.ExportFormat
import com.wang.sonovel.data.LibraryRepository
import com.wang.sonovel.data.RuleRepository
import com.wang.sonovel.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class DownloadRequest(
    val url: String,
    val ruleKey: String?,
    val book: BookInfo? = null,
    /** 要下载的章节；为 null 表示全本 */
    val chapters: List<ChapterRef>? = null,
    val rangeLabel: String = "全本",
    val format: ExportFormat,
    /** 目标语言；null 表示使用设置 */
    val language: String? = null,
    val concurrency: Int? = null,
)

enum class TaskStatus(val label: String) {
    QUEUED("排队中"), PREPARING("解析中"), DOWNLOADING("下载中"), EXPORTING("生成中"),
    PARTIAL("部分完成"), DONE("已完成"), FAILED("失败"), CANCELLED("已取消");

    val active get() = this == QUEUED || this == PREPARING || this == DOWNLOADING || this == EXPORTING
}

data class DownloadTask(
    val id: Long,
    val request: DownloadRequest,
    val bookName: String,
    val author: String,
    val coverUrl: String? = null,
    val sourceName: String = "",
    val status: TaskStatus = TaskStatus.QUEUED,
    val done: Int = 0,
    val total: Int = 0,
    val failed: Int = 0,
    val message: String? = null,
    val output: File? = null,
    val failedChapters: List<String> = emptyList(),
    val speed: Double = 0.0,
    val cacheKey: String? = null,
) {
    val progress: Float get() = if (total <= 0) 0f else (done + failed).toFloat() / total
}

private data class DownloadCheckpoint(
    val id:Long, val request:DownloadRequest, val cacheKey:String,
    val bookName:String, val author:String, val coverUrl:String?, val sourceName:String,
)

/**
 * 下载队列：逐本下载（避免触发源站限流），每本书内部按并发数抓取章节；章节缓存支持断点续传。
 */
class DownloadManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val rules: RuleRepository,
    private val library: LibraryRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    /** 任务结束（成功或失败）事件，用于发送通知；成功的任务随后会自动从列表中移除 */
    private val _finished = MutableSharedFlow<DownloadTask>(extraBufferCapacity = 32)
    val finished: SharedFlow<DownloadTask> = _finished.asSharedFlow()
    private val ids = AtomicLong(System.currentTimeMillis())
    private val cacheRoot = File(context.filesDir, "chapter-downloads").apply { mkdirs() }
    private val checkpointDir = File(context.filesDir, "download-checkpoints").apply { mkdirs() }
    private val exportOnly = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    init { restoreCheckpoints() }
    private var worker: Job? = null
    private var currentJob: Job? = null
    private var currentId: Long = -1

    /** 公共目录副本保存失败时的提示（如缺少存储权限） */
    var onPublicCopyFailed: ((String) -> Unit)? = null

    val hasActive: Boolean get() = _tasks.value.any { it.status.active }

    fun enqueue(req: DownloadRequest, bookName: String, author: String, coverUrl: String?): Long {
        val id = ids.getAndIncrement()
        val rule = req.ruleKey?.let { rules.byKey(it) } ?: rules.matchByUrl(req.url)
        Analytics.event("download", mapOf("format" to req.format.ext, "source" to rule?.displayName.orEmpty(), "range" to if (req.chapters == null) "full" else "partial"))
        _tasks.update {
            it + DownloadTask(
                id = id, request = req, bookName = bookName.ifBlank { "解析中…" }, author = author,
                coverUrl = coverUrl, sourceName = rule?.displayName.orEmpty(),
            )
        }
        startWorker()
        return id
    }

    fun cancel(id: Long) {
        if (id == currentId) currentJob?.cancel()
        update(id) { if (it.status.active) it.copy(status = TaskStatus.CANCELLED, message = null) else it }
    }

    fun retry(id: Long) {
        if (get(id)?.status?.active != false) return
        update(id) { it.copy(status = TaskStatus.QUEUED, message = null, failed = 0, failedChapters = emptyList()) }
        startWorker()
    }

    /** Export saved chapters without any source/network request, including cancelled tasks. */
    fun exportDownloaded(id:Long) {
        val task=get(id) ?: return
        if(task.status.active || task.done<=0 || task.cacheKey==null) return
        exportOnly.add(id)
        update(id) { it.copy(status=TaskStatus.QUEUED, message="等待导出已下载章节…") }
        startWorker()
    }

    fun remove(id: Long) {
        cancel(id)
        _tasks.update { list -> list.filterNot { it.id == id } }
        scope.launch { File(checkpointDir,"$id.json").delete() }
    }

    fun clearFinished() {
        val finished=_tasks.value.filterNot { it.status.active }.map { it.id }
        _tasks.update { list -> list.filter { it.status.active } }
        scope.launch { finished.forEach { File(checkpointDir,"$it.json").delete() } }
    }

    private fun update(id: Long, block: (DownloadTask) -> DownloadTask) =
        _tasks.update { list -> list.map { if (it.id == id) block(it) else it } }

    private fun get(id: Long) = _tasks.value.firstOrNull { it.id == id }

    @Synchronized
    private fun startWorker() {
        runCatching {
            ContextCompat.startForegroundService(context, android.content.Intent(context, DownloadService::class.java))
        }
        if (worker?.isActive == true) return
        worker = scope.launch {
            try {
                while (true) {
                    val next = _tasks.value.firstOrNull { it.status == TaskStatus.QUEUED } ?: break
                    currentId = next.id
                    val job = launch { if(exportOnly.remove(next.id)) exportCached(next.id) else runTask(next.id) }
                    currentJob = job
                    job.join()
                    currentId = -1
                }
            } finally {
                synchronized(this@DownloadManager) {
                    currentId=-1;currentJob=null;worker=null
                    if(_tasks.value.any { it.status==TaskStatus.QUEUED }) startWorker()
                }
            }
        }
    }

    private suspend fun runTask(id: Long) {
        val task = get(id) ?: return
        val req = task.request
        try {
            val s0 = settings.current
            val rule = req.ruleKey?.let { rules.byKey(it) } ?: rules.matchByUrl(req.url)
                ?: throw IOException("找不到与链接匹配的书源：${req.url}")
            val s = s0.copy(
                extName = req.format.ext,
                language = req.language ?: s0.language,
                concurrency = req.concurrency ?: s0.concurrency,
            )
            val ctx = SourceContext(rule, s)
            update(id) { it.copy(status = TaskStatus.PREPARING, message = "正在解析书籍详情…", sourceName = rule.displayName) }

            val langChanged = req.language != null && req.language != s0.targetLanguage
            val book = (if (langChanged && task.cacheKey==null) null else req.book) ?: BookParser(ctx).parse(req.url)
            update(id) { it.copy(bookName = book.bookName, author = book.author, coverUrl = it.coverUrl ?: book.coverUrl, message = "正在解析章节目录…") }

            val toc = req.chapters ?: TocParser(ctx).parseAll(req.url)
            if (toc.isEmpty()) throw IOException("源站章节目录为空，中止下载（可能有反爬限制）")

            val cacheKey=task.cacheKey ?: md5("${rule.key}|${req.url}|${ctx.targetLang}")
            val cacheDir = File(cacheRoot,cacheKey).apply { mkdirs() }
            fun cacheFile(c: ChapterRef) = chapterFile(cacheDir,c)
            update(id) { it.copy(cacheKey=cacheKey, request=req.copy(book=book,chapters=toc,language=ctx.targetLang)) }
            saveCheckpoint(requireNotNull(get(id)))

            val total = toc.size
            val pending = toc.filter { readCached(cacheFile(it),it)==null }
            val done = AtomicInteger(total - pending.size)
            val failedList = java.util.Collections.synchronizedList(ArrayList<String>())
            val crawl = ctx.crawl
            // Reading sources often share cookies / rate limits; avoid the old 50-request burst.
            val reading=rule.legadoJson!=null
            val requested=if(crawl.concurrency<=0) (if(reading) 2 else 50) else crawl.concurrency
            val maxConcurrent=minOf(requested,total,if(reading) 4 else 100).coerceAtLeast(1)
            val startTime = System.currentTimeMillis()
            val startDone = done.get()
            update(id) { it.copy(status = TaskStatus.DOWNLOADING, done = done.get(), total = total, message = "最大并发 $maxConcurrent") }

            val parser = ChapterParser(ctx)
            val processor = ChapterProcessor(ctx)
            val lastUi = AtomicLong(0)
            fun report(force: Boolean = false) {
                val now = System.currentTimeMillis()
                if (!force && now - lastUi.get() < 250) return
                lastUi.set(now)
                val secs = (now - startTime) / 1000.0
                val speed = if (secs > 0) (done.get() - startDone) / secs else 0.0
                update(id) { it.copy(done = done.get(), failed = failedList.size, speed = speed) }
            }

            coroutineScope {
                val cursor=AtomicInteger(0)
                repeat(minOf(maxConcurrent,pending.size)) {
                    launch(Dispatchers.IO) {
                        while(true) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            val c=pending.getOrNull(cursor.getAndIncrement()) ?: break
                            val content = fetchWithRetry(ctx, parser, processor, c)
                            if (content != null) {
                                val target=cacheFile(c)
                                val temp=File(cacheDir,target.name+".part")
                                temp.writeText(content.title + "\n" + content.html)
                                check(temp.renameTo(target)) { "无法保存章节缓存" }
                                done.incrementAndGet()
                            } else {
                                failedList += c.title
                            }
                            report()
                        }
                    }
                }
            }
            report(force = true)
            if (done.get() == 0) throw IOException("全部章节下载失败，可能被源站限流，请稍后重试或更换书源")
            if(failedList.isNotEmpty()) {
                update(id) { it.copy(status=TaskStatus.PARTIAL,failedChapters=failedList.toList(),
                    message="已保存 ${done.get()}/$total 章，${failedList.size} 章失败。可导出已下载内容，或重试补全。") }
                get(id)?.let { _finished.tryEmit(it) }
                return
            }

            update(id) { it.copy(status = TaskStatus.EXPORTING, message = "正在生成 ${req.format.label}…") }
            val coverUrl = if (s.fetchBetterCover && !rule.needProxy) {
                runCatching { CoverFetcher.best(book, s) }.getOrNull() ?: book.coverUrl
            } else book.coverUrl
            val coverBytes = coverUrl?.let { u ->
                runCatching { CoverFetcher.download(u, s) }.getOrNull()?.takeIf { it.size > 1024 }
            }
            val output = library.outputFile(book, req.format)
            val finalBook = book.copy(coverUrl = coverUrl)
            val chapters = {
                toc.asSequence().mapNotNull { c ->
                    val f = cacheFile(c)
                    if (!f.exists()) null else {
                        val text = f.readText()
                        val nl = text.indexOf('\n')
                        if (nl < 0) null else ChapterContent(c.order, text.substring(0, nl), text.substring(nl + 1))
                    }
                }
            }
            val charset = runCatching { Charset.forName(s.txtEncoding.ifBlank { "UTF-8" }) }.getOrDefault(Charsets.UTF_8)
            val tempOutput=File(output.parentFile,output.name+".part")
            try {
                withContext(Dispatchers.IO) {
                    Exporters.export(req.format, finalBook, chapters, coverBytes, tempOutput, charset)
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    check(tempOutput.renameTo(output)) { "无法保存导出的小说" }
                }
            } finally { tempOutput.delete() }
            library.saveCover(output, coverBytes)
            library.refresh()

            var publicMsg: String? = null
            if (s.saveToPublic) {
                publicMsg = runCatching { "已保存至 " + library.copyToPublic(output) }
                    .getOrElse { e ->
                        onPublicCopyFailed?.invoke(e.message ?: "")
                        "未能保存到公共下载目录：${e.message}"
                    }
            }
            if (failedList.isEmpty()) {
                cacheDir.deleteRecursively()
                File(checkpointDir,"$id.json").delete()
            }
            val msg = buildString {
                val secs = (System.currentTimeMillis() - startTime) / 1000.0
                append("耗时 %.1f 秒".format(secs))
                if (failedList.isNotEmpty()) append("，${failedList.size} 章失败，可点击重试补全")
                publicMsg?.let { append("\n").append(it) }
            }
            update(id) {
                it.copy(
                    status = TaskStatus.DONE, output = output, message = msg, coverUrl = coverUrl ?: it.coverUrl,
                    failed = failedList.size, failedChapters = failedList.toList(), done = done.get(),
                )
            }
            get(id)?.let { _finished.tryEmit(it) }
            // 下载完成后自动清除任务，书籍直接进入书架；有失败章节时保留以便补全
            if (failedList.isEmpty()) {
                _tasks.update { list -> list.filterNot { it.id == id } }
            }
        } catch (e: CancellationException) {
            update(id) { if (it.status.active && it.status!=TaskStatus.QUEUED) it.copy(status = TaskStatus.CANCELLED) else it }
            throw e
        } catch (e: Throwable) {
            update(id) { it.copy(status = TaskStatus.FAILED, message = e.message ?: e.javaClass.simpleName) }
            get(id)?.let { _finished.tryEmit(it) }
        }
    }

    /** A chapter failure must not cancel sibling downloads, even when retries are disabled. */
    private suspend fun fetchWithRetry(
        ctx: SourceContext, parser: ChapterParser, processor: ChapterProcessor, c: ChapterRef,
    ): ChapterContent? {
        fun process(raw:String):ChapterContent {
            val content=processor.process(c.order,c.title,raw)
            require(org.jsoup.Jsoup.parse(content.html).text().isNotBlank()) { "章节正文处理后为空" }
            return content
        }
        var lastError: Throwable
        try {
            val raw = parser.fetchContent(c.url, ctx.randomInterval())
            return process(raw)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            lastError = e
            if (!ctx.crawl.enableRetry) return null
        }
        for (attempt in 1..ctx.crawl.maxRetries.coerceIn(0,10)) {
            try {
                val raw = parser.fetchContent(c.url, ctx.randomInterval(retry = true) * attempt)
                return process(raw)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastError = e
            }
        }
        android.util.Log.w("SoNovel", "章节下载失败: ${c.title} ${c.url}: ${lastError.message}")
        return null
    }

    private fun chapterFile(directory:File,c:ChapterRef)=File(directory,md5(c.url)+".txt")

    private fun readCached(file:File,c:ChapterRef):ChapterContent? = runCatching {
        if(!file.isFile) return null
        val text=file.readText(); val nl=text.indexOf('\n')
        if(nl<0 || text.substring(nl+1).isBlank()) null
        else ChapterContent(c.order,text.substring(0,nl),text.substring(nl+1))
    }.getOrNull()

    private fun saveCheckpoint(task:DownloadTask) {
        val key=task.cacheKey ?: return
        val value=DownloadCheckpoint(task.id,task.request,key,task.bookName,task.author,task.coverUrl,task.sourceName)
        val temp=File(checkpointDir,"${task.id}.part")
        temp.writeText(com.google.gson.Gson().toJson(value))
        val target=File(checkpointDir,"${task.id}.json")
        check(temp.renameTo(target)) { "无法保存下载进度" }
        if(get(task.id)==null) target.delete()
    }

    private fun restoreCheckpoints() {
        val restored=checkpointDir.listFiles { f->f.extension=="json" }.orEmpty().mapNotNull { file ->
            runCatching {
                val saved=com.google.gson.Gson().fromJson(file.readText(),DownloadCheckpoint::class.java)
                require(saved.cacheKey.matches(Regex("[a-f0-9]{32}")))
                val toc=requireNotNull(saved.request.chapters)
                val directory=File(cacheRoot,saved.cacheKey)
                val missing=toc.filter { readCached(chapterFile(directory,it),it)==null }
                DownloadTask(saved.id,saved.request,saved.bookName,saved.author,saved.coverUrl,saved.sourceName,
                    status=TaskStatus.CANCELLED,done=toc.size-missing.size,total=toc.size,failed=missing.size,
                    failedChapters=missing.map { it.title },cacheKey=saved.cacheKey,
                    message="已恢复保存的章节，可继续下载或导出已下载内容")
            }.getOrNull()
        }
        _tasks.value=restored.sortedBy { it.id }
        ids.set(maxOf(ids.get(),(restored.maxOfOrNull { it.id } ?: 0)+1))
    }

    private suspend fun exportCached(id:Long) {
        val task=get(id) ?: return
        try {
            val key=requireNotNull(task.cacheKey)
            val book=requireNotNull(task.request.book)
            val toc=requireNotNull(task.request.chapters)
            val directory=File(cacheRoot,key)
            val missing=toc.filter { readCached(chapterFile(directory,it),it)==null }
            val count=toc.size-missing.size
            require(count>0) { "没有可导出的已下载章节，请先重试下载" }
            update(id) { it.copy(status=TaskStatus.EXPORTING,message="正在导出已下载的 $count 章…") }
            val incomplete=missing.isNotEmpty()
            val exportBook=if(incomplete) book.copy(bookName=book.bookName+"（未完整）") else book
            val output=library.outputFile(exportBook,task.request.format)
            val temp=File(output.parentFile,output.name+".part")
            val chapters={ toc.asSequence().map { c ->
                readCached(chapterFile(directory,c),c) ?: ChapterContent(c.order,c.title+"（未下载）",
                    "<p>本章尚未下载成功。可回到下载任务重试补全。</p>")
            } }
            val s=settings.current
            val charset=runCatching { Charset.forName(s.txtEncoding.ifBlank { "UTF-8" }) }.getOrDefault(Charsets.UTF_8)
            try {
                withContext(Dispatchers.IO) {
                    Exporters.export(task.request.format,exportBook,chapters,null,temp,charset)
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                }
                check(temp.renameTo(output)) { "无法保存导出的小说" }
            } finally { temp.delete() }
            library.refresh()
            val publicMessage=if(s.saveToPublic) runCatching { "；已保存至 "+library.copyToPublic(output) }
                .getOrElse { "；公共目录保存失败，可从下载列表再次导出" } else ""
            update(id) { it.copy(status=TaskStatus.DONE,output=output,done=count,failed=missing.size,
                failedChapters=missing.map { c->c.title },
                message="已导出 $count/${toc.size} 章"+(if(incomplete) "，缺失章节已标注，可重试补全" else "")+publicMessage) }
            if(!incomplete) {
                File(checkpointDir,"$id.json").delete()
                directory.deleteRecursively()
            }
            get(id)?.let { _finished.tryEmit(it) }
        } catch(e:CancellationException) {
            update(id) { if(it.status==TaskStatus.QUEUED) it else it.copy(status=TaskStatus.CANCELLED) };throw e
        } catch(e:Exception) {
            update(id) { it.copy(status=TaskStatus.FAILED,message=e.message ?: "导出失败，已下载章节仍保留") }
            get(id)?.let { _finished.tryEmit(it) }
        }
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

package com.wang.sonovel.core

import com.google.gson.*
import com.wang.sonovel.data.*
import kotlinx.coroutines.delay
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

/** Import rules conservatively, retaining unsupported originals and concrete reasons. */
object LegadoImport {
    fun objectOf(value: JsonElement?): JsonObject = when {
        value == null || value.isJsonNull -> JsonObject()
        value.isJsonObject -> value.asJsonObject
        value.isJsonPrimitive -> runCatching { JsonParser.parseString(value.asString).asJsonObject }.getOrDefault(JsonObject())
        else -> JsonObject()
    }
    fun JsonObject.text(key: String): String = get(key)?.takeUnless { it.isJsonNull }?.let { if(it.isJsonPrimitive) it.asString else it.toString() }.orEmpty()
    fun convert(json: JsonObject): Rule {
        val problems=mutableListOf<String>()
        val search=objectOf(json.get("ruleSearch"))
        val info=objectOf(json.get("ruleBookInfo"))
        val toc=objectOf(json.get("ruleToc"))
        val content=objectOf(json.get("ruleContent"))
        if (json.text("bookSourceType").ifBlank { "0" } != "0") problems += "暂仅支持文字书源"
        for (key in listOf("loginCheckJs","coverDecodeJs")) if(json.text(key).isNotBlank()) problems += "需要 " + key
        if(json.text("jsLib").startsWith("http")) problems += "需要远程脚本库"
        val activeRules=mutableListOf(json.text("jsLib"),json.text("header"),json.text("searchUrl"))
        for ((label,group) in listOf("搜索" to search,"详情" to info,"目录" to toc,"正文" to content)) {
            group.entrySet().forEach { (key,value) ->
                val rule=if(value.isJsonPrimitive) value.asString else ""
                if(rule.isBlank()) return@forEach
                if (key in listOf("preUpdateJs","formatJs","webJs","sourceRegex","imageDecode","payAction","callBackJs","subContent","isVolume")) problems += label+"使用 "+key
                if(key !in listOf("checkKeyWord","isVip","isPay")) activeRules += rule
            }
        }
        val url=json.text("searchUrl")
        activeRules.forEach { rule ->
            Regex("""java\.([A-Za-z_]\w*)\s*\(""").findAll(rule).forEach { call ->
                if(call.groupValues[1] !in ReadingScript.methods) problems += "暂不支持 java."+call.groupValues[1]
            }
            if(Regex("""(?i)@java:|Packages\.|javax\.|java\.lang\.|importClass\(|importPackage\(""").containsMatchIn(rule)) problems += "需要 Java 扩展"
            if(Regex(""""webView"\s*:\s*true""").containsMatchIn(rule)) problems += "需要内嵌浏览器"
            if(Regex("""source\.getLoginInfo|cookie\.|android\.""").containsMatchIn(rule)) problems += "需要登录或设备接口"
        }
        if(search.text("bookList").isBlank() || search.text("name").isBlank() || search.text("bookUrl").isBlank()) problems += "缺少完整搜索规则"
        if(toc.text("chapterList").isBlank() || toc.text("chapterUrl").isBlank() || content.text("content").isBlank()) problems += "缺少目录或正文规则"
        return Rule().apply {
            this.url=json.text("bookSourceUrl")
            name=json.text("bookSourceName").ifBlank { this.url }
            legadoJson=json.toString()
            compatibility=problems.distinct().joinToString("；")
            disabled=problems.isNotEmpty()
            importEnabled=json.text("enabled")!="false"
            comment=if(problems.isEmpty()) "阅读 3.0 · HTML / JSON / JS 规则；连通性待检测" else "暂不可用："+compatibility
            this.search=Rule.Search().apply { this.url=url; author=search.text("author") }
            book=Rule.Book()
            this.toc=Rule.Toc()
            chapter=Rule.Chapter()
        }
    }
}

/** Reading HTML selectors, including indexed groups and XPath attribute/text tails. */
object LegadoSelector {
    private val values = setOf("text", "textNodes", "ownText", "html", "all", "href", "src", "content", "value")
    private val indexTail = Regex("(?:\\[([!\\d, :\\-]+)\\]|([.!])(-?\\d+(?::[-\\d:]*)?))$")

    private fun steps(rule: String): List<String> {
        val parts=mutableListOf<String>();var start=0;var level=0;var quote=' ';var escape=false
        for(i in rule.indices) {
            val c=rule[i]
            if(quote!=' ') { if(escape) escape=false else if(c=='\\') escape=true else if(c==quote) quote=' ' }
            else when(c) {
                '\'', '"' -> quote=c
                '[', '(' -> level++
                ']', ')' -> level--
                '@' -> if(level==0) { parts+=rule.substring(start,i);start=i+1 }
            }
        }
        parts+=rule.substring(start)
        return parts.filter { it.isNotBlank() }.map { it.trim() }
    }
    private fun indexed(found: List<Element>, expr: String, exclude: Boolean): List<Element> {
        val indexes=linkedSetOf<Int>()
        fun normalized(n:Int)=if(n<0) found.size+n else n
        for(piece in expr.split(',')) {
            val p=piece.trim().split(':')
            if(p.size==1) { p[0].toIntOrNull()?.let { indexes+=normalized(it) };continue }
            val first=normalized(p[0].toIntOrNull() ?: 0).coerceIn(0,(found.size-1).coerceAtLeast(0))
            val last=normalized(p[1].toIntOrNull() ?: -1).coerceIn(0,(found.size-1).coerceAtLeast(0))
            val step=kotlin.math.abs(p.getOrNull(2)?.toIntOrNull() ?: 1).coerceAtLeast(1)
            indexes.addAll(if(first<=last) first..last step step else first downTo last step step)
        }
        return if(exclude) found.filterIndexed { i,_->i !in indexes } else indexes.mapNotNull { found.getOrNull(it) }
    }
    fun elements(root: Element, rule: String): List<Element> {
        if(rule.isBlank()) return listOf(root)
        val clean=rule.substringBefore("##").trim().removePrefix("@@")
        if(clean.startsWith("@XPath:",true) || clean.startsWith("//") || clean.startsWith("./"))
            return root.selectXpath(clean.removePrefix("@XPath:").removePrefix("@xpath:"))
        val cssOnly=clean.startsWith("@css:",true)
        var selected=listOf(root)
        for(part in steps(clean.removePrefix("@css:").removePrefix("@CSS:"))) {
            if(part in values) break
            val index=if(cssOnly) null else indexTail.find(part)
            val selector=if(index==null) part else part.substring(0,index.range.first)
            selected=selected.flatMap { parent ->
                val found:List<Element> = when {
                    cssOnly -> parent.select(selector)
                    selector.startsWith("class.") -> {
                        val names=selector.removePrefix("class.").trim().split(Regex("\\s+"))
                        parent.getAllElements().filter { e -> names.all { e.hasClass(it) } }
                    }
                    selector.startsWith("text.") -> parent.getElementsContainingOwnText(selector.removePrefix("text."))
                    selector.startsWith("tag.") -> parent.getElementsByTag(selector.removePrefix("tag."))
                    selector.startsWith("id.") -> parent.getAllElements().filter { it.id()==selector.removePrefix("id.") }
                    selector=="children" || selector.isBlank() -> parent.children()
                    else -> parent.select(selector)
                }
                if(index==null) found else {
                    val expression=index.groupValues[1].ifBlank { index.groupValues[3] }
                    indexed(found,expression.removePrefix("!"),expression.startsWith("!") || index.groupValues[2]=="!")
                }
            }
        }
        return selected
    }
    fun text(root: Element, rule: String, html: Boolean = false): String {
        if(rule.isBlank()) return ""
        val parts=rule.split("##",limit=3)
        var selector=parts[0].trim().removePrefix("@@")
        var attr="text"
        val xpath=selector.startsWith("@XPath:",true) || selector.startsWith("//") || selector.startsWith("./")
        if(xpath) {
            val tail=Regex("(?<!/)@(textNodes|ownText|text|html|all|href|src|content|value)$").find(selector)
            if(tail!=null) { attr=tail.groupValues[1];selector=selector.substring(0,tail.range.first) }
            else if(Regex("/@[\\w:-]+$").containsMatchIn(selector)) { attr=selector.substringAfterLast("/@");selector=selector.substringBeforeLast("/@") }
            else if(selector.endsWith("/text()")) { selector=selector.removeSuffix("/text()");attr="text" }
        } else if(selector in values) { attr=selector;selector="" }
        else {
            val chunks=steps(selector.removePrefix("@css:").removePrefix("@CSS:"))
            if(chunks.size>1) { attr=chunks.last();selector=selector.substringBeforeLast('@') }
        }
        var value=elements(root,selector).map { el -> when(attr) {
            "text" -> if(html) el.wholeText() else el.text()
            "textNodes" -> el.textNodes().joinToString("\n") { it.text() }
            "ownText" -> el.ownText()
            "html" -> el.clone().apply { select("script,style").remove() }.outerHtml()
            "all" -> el.outerHtml()
            "href","src" -> el.absUrl(attr).ifBlank { el.attr(attr) }
            else -> el.attr(attr)
        } }.filter { it.isNotBlank() }.joinToString("\n").trim()
        if(parts.size>=2 && parts[1].isNotEmpty()) value=Regex(parts[1]).replace(value,parts.getOrElse(2) { "" })
        return value.trim()
    }
}

package com.wang.sonovel.core

import com.google.gson.*
import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.PathNotFoundException
import com.jayway.jsonpath.spi.json.GsonJsonProvider
import com.jayway.jsonpath.spi.mapper.GsonMappingProvider
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.Charset
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*

internal class ReadingRules(val sourceUrl:String,library:String="",private val network:(String,String,String,Map<String,String>)->FetchedPage) {
    var baseUrl=sourceUrl
    var keyword=""
    val variables=mutableMapOf<String,String>()
    private var current:Any?=""
    private var original:Any?=""
    private var depth=0
    private var cachedHtml:String?=null
    private var cachedBase=""
    private var cachedDocument:Element?=null
    private val jsonConfig=Configuration.builder().jsonProvider(GsonJsonProvider()).mappingProvider(GsonMappingProvider()).build()
    private val script=ReadingScript(library,::bridge)
    private fun json(value:Any?):Any? = when(value) {
        is Element -> value.outerHtml()
        is List<*> -> value.map { json(it) }
        else -> value
    }
    fun stringify(value:Any?):String=when(value) {
        null -> ""
        is Element -> value.outerHtml()
        is JsonElement -> when { value.isJsonNull->"";value.isJsonPrimitive->value.asString;else->value.toString() }
        is List<*> -> value.joinToString("\n") { stringify(it) }
        else -> value.toString()
    }
    fun js(code:String,input:Any?):JsonElement {
        val previous=current;current=input
        try {
            return script.evaluate(code,mapOf("result" to json(input),"src" to stringify(original),"baseUrl" to baseUrl,"url" to baseUrl,"key" to keyword,"page" to 1,"sourceUrl" to sourceUrl))
        } finally { current=previous }
    }
    fun expand(template:String,input:Any?="",url:Boolean=false,charset:String="UTF-8"):String =
        Regex("\\{\\{([\\s\\S]*?)\\}\\}").replace(template) { match ->
            val code=match.groupValues[1].trim()
            when {
                code=="key" -> if(url) URLEncoder.encode(keyword,charset) else keyword
                code=="page" -> "1"
                code.startsWith("$.") || code.startsWith("@") -> text(input,code)
                else -> stringify(js(code,input))
            }
        }
    fun text(input:Any?,rule:String,html:Boolean=false):String {
        if(rule.isBlank()) return ""
        val value=evaluate(input,rule,false,html)
        return (if(value is JsonArray) value.joinToString("\n") { stringify(it) } else stringify(value)).trim()
    }
    fun list(input:Any?,rule:String):List<Any?> {
        val value=evaluate(input,rule,true,false)
        return when(value) { is List<*> -> value;is JsonArray -> value.toList();null -> emptyList();is JsonNull->emptyList();else -> listOf(value) }
    }
    private fun evaluate(input:Any?,rawRule:String,wantList:Boolean,html:Boolean):Any? {
        if(depth==0) original=input
        require(depth++<20) { "书源规则递归过深" }
        try {
            var rule=rawRule.trim().removePrefix("@@")
            if(rule.isEmpty()) return if(wantList) listOf(input) else input
            // Script stages are separated before interpreting operators inside JavaScript.
            val marker=Regex("(?i)@js:|<js>").find(rule)
            if(marker!=null) {
                var value:Any?=if(marker.range.first==0) json(input) else evaluate(input,rule.substring(0,marker.range.first),wantList,html)
                var remaining=rule.substring(marker.range.first)
                while(remaining.isNotBlank()) {
                    if(remaining.startsWith("@js:",true)) return js(remaining.substring(4),value)
                    if(remaining.startsWith("<js>",true)) {
                        val end=remaining.indexOf("</js>",ignoreCase=true)
                        require(end>=0) { "缺少 </js>" }
                        value=js(remaining.substring(4,end),value)
                        remaining=remaining.substring(end+5).trim()
                    } else return evaluate(value,remaining,wantList,html)
                }
                return value
            }
            while(rule.startsWith("@put:")) {
                var level=0;var quoted=false;var escaped=false;var end=-1
                for(i in 5 until rule.length) {
                    val c=rule[i]
                    if(quoted) { if(escaped) escaped=false else if(c=='\\') escaped=true else if(c=='"') quoted=false }
                    else if(c=='"') quoted=true else if(c=='{') level++ else if(c=='}' && --level==0) { end=i;break }
                }
                require(end>=0) { "@put 规则格式错误" }
                JsonParser.parseString(rule.substring(5,end+1)).asJsonObject.entrySet().forEach { (k,v)->variables[k]=text(input,v.asString) }
                rule=rule.substring(end+1).trim()
            }
            val getter=Regex("@get:\\{([^}]+)\\}")
            getter.matchEntire(rule)?.let { return variables[it.groupValues[1]].orEmpty() }
            rule=getter.replace(rule) { variables[it.groupValues[1]].orEmpty() }
            if(rule.contains("{{")) return expand(rule,input)
            val replacement=rule.split("##",limit=3)
            val selector=replacement[0]
            val operator=listOf("||","&&","%%").firstOrNull { splitTop(selector,it).size>1 }
            var value:Any?=if(operator!=null) {
                val parts=splitTop(selector,operator).asSequence().map { evaluate(input,it,wantList,html) }
                if(operator=="||") parts.firstOrNull { stringify(it).isNotBlank() && it !is JsonNull && (it !is JsonArray || !it.isEmpty) && (it !is Collection<*> || it.isNotEmpty()) }
                else if(wantList) {
                    val lists=parts.map { when(it){is List<*>->it;is JsonArray->it.toList();null->emptyList();else->listOf(it)} }.toList()
                    if(operator=="%%") (0 until (lists.maxOfOrNull { it.size } ?: 0)).flatMap { i->lists.mapNotNull { it.getOrNull(i) } } else lists.flatten()
                } else parts.joinToString("\n") { stringify(it) }
            } else select(input,selector,wantList,html)
            if(replacement.size>=2 && replacement[1].isNotEmpty()) value=Regex(replacement[1]).replace(stringify(value),replacement.getOrElse(2) { "" })
            return value
        } finally {depth--}
    }
    private fun splitTop(rule:String,operator:String):List<String> {
        val result=mutableListOf<String>();var start=0;var level=0;var quote=' ';var escaped=false;var i=0
        while(i<rule.length) {
            val c=rule[i]
            if(quote!=' ') { if(escaped) escaped=false else if(c=='\\') escaped=true else if(c==quote) quote=' ' }
            else when {
                c=='\'' || c=='"' -> quote=c
                c=='[' || c=='(' -> level++
                c==']' || c==')' -> level--
                level==0 && rule.startsWith(operator,i) -> { result+=rule.substring(start,i);i+=operator.length-1;start=i+1 }
            }
            i++
        }
        result+=rule.substring(start)
        return result
    }
    private fun select(input:Any?,rule:String,wantList:Boolean,html:Boolean):Any? {
        if(rule.isBlank()) return input
        if(rule.startsWith("http://") || rule.startsWith("https://")) return rule
        if(input is Element) return if(wantList) LegadoSelector.elements(input,rule) else LegadoSelector.text(input,rule,html)
        if(input is JsonObject && input.has("__html")) {
            val body=Jsoup.parseBodyFragment(input.get("__html").asString,baseUrl).body()
            return select(body.children().firstOrNull() ?: body,rule,wantList,html)
        }
        val raw=stringify(input)
        val parsed=if(input is JsonElement && !input.isJsonPrimitive) input else if(raw.trimStart().startsWith("{") || raw.trimStart().startsWith("[")) runCatching { JsonParser.parseString(raw) }.getOrNull() else null
        if(rule.startsWith("$.") || rule.startsWith("$[") || rule.startsWith("@json:") || parsed is JsonObject || parsed is JsonArray) {
            var path=rule.removePrefix("@json:").trim()
            if(!path.startsWith("$")) path="$."+path
            return try { JsonPath.using(jsonConfig).parse(parsed ?: JsonParser.parseString(raw)).read<JsonElement>(path) }
                catch(_:PathNotFoundException) { if(wantList) emptyList<Any>() else "" }
        }
        val element=if(cachedHtml==raw && cachedBase==baseUrl) cachedDocument!! else Jsoup.parse(raw,baseUrl).also { cachedHtml=raw;cachedBase=baseUrl;cachedDocument=it }
        return if(wantList) LegadoSelector.elements(element,rule) else LegadoSelector.text(element,rule,html)
    }
    private fun bridge(method:String,args:List<JsonElement>):Any? {
        fun s(index:Int)=args.getOrNull(index)?.let { stringify(it) }.orEmpty()
        fun bytes(index:Int):ByteArray {
            val value=args.getOrNull(index)
            return if(value is JsonArray) value.map { it.asInt.toByte() }.toByteArray() else s(index).toByteArray(Charsets.UTF_8)
        }
        fun base64(text:String)=android.util.Base64.decode(text,android.util.Base64.DEFAULT)
        when(method) {
            "get" -> return variables[s(0)].orEmpty()
            "put" -> { require(variables.size<100 || variables.containsKey(s(0)));require(s(1).length<=32768);variables[s(0)]=s(1);return s(1) }
            "cacheGet" -> return ReadingState.get(sourceUrl,"__cache")[s(0)].orEmpty()
            "cachePut" -> { ReadingState.put(sourceUrl,"__cache",ReadingState.get(sourceUrl,"__cache")+(s(0) to s(1)));return s(1) }
            "getString" -> return text(args.getOrNull(1)?.takeUnless { it.isJsonNull || it.isJsonPrimitive && it.asJsonPrimitive.isBoolean } ?: current,s(0))
            "getStringList" -> return text(args.getOrNull(1)?.takeUnless { it.isJsonNull || it.isJsonPrimitive && it.asJsonPrimitive.isBoolean } ?: current,s(0)).lines().filter { it.isNotBlank() }
            "getElements" -> return list(args.getOrNull(1)?.takeUnless { it.isJsonNull } ?: current,s(0)).map { stringify(it) }
            "getElement" -> return list(args.getOrNull(1)?.takeUnless { it.isJsonNull } ?: current,s(0)).firstOrNull()?.let { stringify(it) }
            "setContent" -> { current=s(0);return null }
            "nodeText" -> return LegadoSelector.text(Jsoup.parseBodyFragment(s(0),baseUrl).body().children().first() ?: Jsoup.parse(s(0)),s(1))
            "nodeSelect" -> return LegadoSelector.elements(Jsoup.parseBodyFragment(s(0),baseUrl).body(),s(1)).map { it.outerHtml() }
            "base64Decode" -> return String(base64(s(0)),Charset.forName(s(1).ifBlank { "UTF-8" }))
            "base64DecodeToByteArray" -> return base64(s(0)).map { it.toInt() }
            "base64Encode" -> return android.util.Base64.encodeToString(bytes(0),android.util.Base64.NO_WRAP)
            "strToBytes" -> return s(0).toByteArray(Charset.forName(s(1).ifBlank { "UTF-8" })).map { it.toInt() }
            "bytesToStr" -> return String(bytes(0),Charset.forName(s(1).ifBlank { "UTF-8" }))
            "hexDecodeToByteArray","hexDecodeToString" -> {
                val b=s(0).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                return if(method=="hexDecodeToString") String(b,Charsets.UTF_8) else b.map { it.toInt() }
            }
            "hexEncodeToString" -> return bytes(0).joinToString("") { "%02x".format(it) }
            "md5Encode","md5Encode16","digestHex" -> {
                val algorithm=if(method=="digestHex") s(1).ifBlank { "MD5" } else "MD5"
                val hash=MessageDigest.getInstance(algorithm).digest(bytes(0)).joinToString("") { "%02x".format(it) }
                return if(method=="md5Encode16") hash.substring(8,24) else hash
            }
            "urlEncode","encodeURI" -> return URLEncoder.encode(s(0),s(1).ifBlank { "UTF-8" })
            "timeFormat","timeFormatUTC" -> return SimpleDateFormat(s(1).ifBlank { "yyyy-MM-dd HH:mm:ss" },Locale.CHINA).apply { if(method=="timeFormatUTC") timeZone=TimeZone.getTimeZone("UTC") }.format(Date(s(0).toDouble().toLong()))
            "randomUUID" -> return UUID.randomUUID().toString()
            "getWebViewUA" -> return LegadoEngine.READING_UA
            "toURL" -> return URI(baseUrl).resolve(s(0)).toString()
            "ajax","connect","post","head","getRedirectUrl" -> {
                val headers=if(method=="post") args.getOrNull(2) else args.getOrNull(1)
                val map=if(headers is JsonObject) headers.entrySet().associate { it.key to it.value.asString } else emptyMap()
                val response=network(s(0),if(method=="post") "POST" else if(method=="head") "HEAD" else "GET",if(method=="post") s(1) else "",map)
                return when(method) { "ajax"->response.text;"getRedirectUrl"->response.url;else->mapOf("body" to response.text,"code" to response.code,"url" to response.url) }
            }
            "log","toast","longToast" -> return null
            else -> error("暂未支持阅读接口：java.$method")
        }
    }
}

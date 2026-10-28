package com.wang.sonovel.core

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import org.mozilla.javascript.*

/** Safe ECMAScript globals and explicit JSON-only functions; no Java reflection bridge. */
internal class ReadingScript(private val library:String, private val bridge:(String,List<JsonElement>)->Any?) {
    private val gson=Gson()
    private var scope:ScriptableObject?=null
    private var deadline=0L
    private var depth=0
    private val factory=object:ContextFactory() {
        override fun makeContext():Context = super.makeContext().apply {
            optimizationLevel=-1
            languageVersion=Context.VERSION_ES6
            instructionObserverThreshold=5000
            setClassShutter { false }
            maximumInterpreterStackDepth=128
        }
        override fun observeInstructionCount(cx:Context,instructionCount:Int) {
            check(System.nanoTime()<deadline && !Thread.currentThread().isInterrupted) { "书源脚本执行超时" }
        }
    }
    fun evaluate(code:String,bindings:Map<String,Any?>):JsonElement {
        require(code.length<=512000) { "书源脚本过大" }
        require(depth<12) { "书源脚本递归过深" }
        if(depth++==0) deadline=System.nanoTime()+3_000_000_000L
        val cx=factory.enterContext()
        try {
            val env=scope ?: cx.initSafeStandardObjects().also { root ->
                scope=root
                ScriptableObject.putProperty(root,"__bridge",object:BaseFunction() {
                    override fun call(cx:Context,scope:Scriptable,thisObj:Scriptable,args:Array<out Any>):Any {
                        val input=JsonParser.parseString(Context.toString(args[0])).asJsonArray
                        return gson.toJson(bridge(input[0].asString,input.drop(1)))
                    }
                })
                cx.evaluateString(root,BOOT,"reading-bridge",1,null)
            }
            bindings.forEach { (key,value) ->
                // Data enters as plain JS values, never NativeJavaObject.
                val encoded=gson.toJson(value)
                ScriptableObject.putProperty(env,key,cx.evaluateString(env,"("+encoded+")","binding",1,null))
            }
            if(ScriptableObject.getProperty(env,"__libReady")!=true) {
                ScriptableObject.putProperty(env,"__libReady",true)
                if(library.isNotBlank()) cx.evaluateString(env,library,"source-library",1,null)
            }
            val value=cx.evaluateString(env,code,"reading-rule",1,null)
            if(value==null || value is Undefined) return com.google.gson.JsonNull.INSTANCE
            ScriptableObject.putProperty(env,"__output",value)
            val encoded=cx.evaluateString(env,"JSON.stringify(__output)","reading-result",1,null)
            return if(encoded is Undefined) com.google.gson.JsonNull.INSTANCE else JsonParser.parseString(Context.toString(encoded))
        } finally { Context.exit();depth-- }
    }
    companion object {
        val methods=setOf("ajax","connect","post","head","get","put","getString","getStringList","getElements","getElement","setContent",
            "log","toast","longToast","base64Decode","base64Encode","base64DecodeToByteArray","strToBytes","bytesToStr",
            "md5Encode","md5Encode16","digestHex","urlEncode","encodeURI","timeFormat","timeFormatUTC","randomUUID","getWebViewUA",
            "hexDecodeToByteArray","hexDecodeToString","hexEncodeToString","toURL","getRedirectUrl")
        private val BOOT="""
            function __call(){return JSON.parse(__bridge(JSON.stringify(Array.prototype.slice.call(arguments))))}
            function __list(a){a=a||[];a.get=function(i){return this[i]};a.size=function(){return this.length};a.toArray=function(){return this.slice()};a.first=function(){return this[0]};a.last=function(){return this[this.length-1]};return a}
            var java={};
            METHODS.forEach(function(n){java[n]=function(){return __call.apply(null,[n].concat(Array.prototype.slice.call(arguments)))}});
            ['log','toast','longToast'].forEach(function(n){java[n]=function(){}});
            java.getElements=function(rule,content){return __list(__call('getElements',rule,content).map(__node))};
            java.getElement=function(rule,content){var a=java.getElements(rule,content);return a[0]||null};
            java.getStringList=function(rule,content){return __list(__call('getStringList',rule,content))};
            function __node(html){return {__html:html,toString:function(){return html},text:function(){return __call('nodeText',html,'text')},html:function(){return __call('nodeText',html,'html')},attr:function(k){return __call('nodeText',html,k)},select:function(rule){return __list(__call('nodeSelect',html,rule).map(__node))}}}
            ['connect','post','head'].forEach(function(n){java[n]=function(){var r=__call.apply(null,[n].concat(Array.prototype.slice.call(arguments)));return {body:function(){return r.body},code:function(){return r.code},url:function(){return r.url},toString:function(){return r.body}}}});
            var cache={putMemory:function(k,v){return __call('cachePut',k,v)},getMemory:function(k){return __call('cacheGet',k)},put:function(k,v){return __call('cachePut',k,v)},get:function(k){return __call('cacheGet',k)}};
            cache.getFromMemory=cache.getMemory;
            var source={getKey:function(){return sourceUrl},getVariable:function(){return __call('get','__sourceVariable')},setVariable:function(v){return __call('put','__sourceVariable',v)},getLoginInfo:function(){return null}};
            var book={name:'',author:'',bookUrl:'',getVariable:function(k){return __call('get',k)},putVariable:function(k,v){return __call('put',k,v)}};
            Object.defineProperty(book,'name',{get:function(){return __call('get','_name')}});
            Object.defineProperty(book,'author',{get:function(){return __call('get','_author')}});
            Object.defineProperty(book,'bookUrl',{get:function(){return __call('get','_bookUrl')}});
            var chapter={};Object.defineProperty(chapter,'index',{get:function(){return Number(__call('get','_chapterIndex'))}});Object.defineProperty(chapter,'url',{get:function(){return __call('get','_chapterUrl')}});Object.defineProperty(chapter,'title',{get:function(){return __call('get','_chapterName')}});
            var console={log:function(){},warn:function(){},error:function(){}};
        """.trimIndent().replace("METHODS",Gson().toJson(methods))
    }
}

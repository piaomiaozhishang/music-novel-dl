package com.wang.sonovel.music

import com.wang.sonovel.core.*
import org.junit.Assert.*
import org.junit.Test
import org.jsoup.Jsoup

class CompatibilityRulesTest {
    @Test fun selectorIndicesArePositionsInTheSelectedList() {
        val doc=Jsoup.parse("<div><h2>标题</h2><p>甲</p><p>乙</p><p>丙</p></div>")
        assertEquals("甲",LegadoSelector.text(doc,"div@p.0@text"))
        assertEquals("丙",LegadoSelector.text(doc,"div@p.-1@text"))
        assertEquals("乙",LegadoSelector.text(doc,"div@p[1]@text"))
    }
    @Test fun jsonFallbacksVariablesAndMixedScripts() {
        val rules=ReadingRules("https://example.org","") { _,_,_,_->error("Unexpected network") }
        val input="""{"items":[{"title":"甲","id":1},{"title":"乙","id":2}],"empty":[]}"""
        assertEquals(2,rules.list(input,"$.empty[*]||$.items[*]").size)
        assertEquals("甲\n乙",rules.text(input,"$.items[*].title"))
        assertEquals("甲",rules.text(input,"@put:{\"title\":\"$.items[0].title\"}@get:{title}"))
        assertEquals("甲",rules.text(input,"@js:java.get('title')"))
        assertEquals("甲、乙",rules.text(input,"$.items[*].title<js>result.join('、')</js>"))
        assertEquals("https://example.org/2",rules.text("""{"id":2}""","https://example.org/{{$.id}}"))
        assertEquals("甲",rules.text(input,"$.items[?(@.id == 1 || @.id == 9)].title"))
    }
    @Test fun audioProbeRecognizesContainers() {
        assertEquals("mp3",AudioProbe.format("ID3\u0004\u0000\u0000".toByteArray()))
        assertEquals("flac",AudioProbe.format("fLaC".toByteArray()))
        assertEquals("m4a",AudioProbe.format(byteArrayOf(0,0,0,32)+"ftypmp42".toByteArray()))
        assertEquals("aac",AudioProbe.format(byteArrayOf(0xff.toByte(),0xf1.toByte())))
        assertNull(AudioProbe.format("<html>error</html>".toByteArray()))
    }
    @Test fun scriptElementsStayPortableWithoutJavaObjects() {
        val rules=ReadingRules("https://example.org","") { _,_,_,_->error("Unexpected network") }
        val page="<a href='/book'>书名</a>"
        val nodes=rules.list(page,"@js:java.getElements('a')")
        assertEquals("书名",rules.text(nodes.single(),"text"))
        assertEquals("https://example.org/book",rules.text(nodes.single(),"href"))
        assertEquals("2",rules.text(page,"a<js>result.length</js>"))
        assertEquals("1",rules.list(page,"a<js>result.length</js>").single().toString())
    }
}

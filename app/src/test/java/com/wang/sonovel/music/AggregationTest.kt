package com.wang.sonovel.music

import com.google.gson.JsonParser
import com.wang.sonovel.core.LegadoImport
import com.wang.sonovel.core.LegadoSelector
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class AggregationTest {
    private val doc=Jsoup.parse("<div class='book'><a href='/novel/1'>测试书</a><b>作者：甲</b></div><div class='book'><a href='/novel/2'>另一本</a></div>","https://example.com/")
    @Test fun readingSelectorsKeepLinksAndTextSeparate(){
        val rows=LegadoSelector.elements(doc,"class.book")
        assertEquals(2,rows.size)
        assertEquals("测试书",LegadoSelector.text(rows[0],"a@text"))
        assertEquals("https://example.com/novel/1",LegadoSelector.text(rows[0],"a@href"))
        assertEquals("甲",LegadoSelector.text(rows[0],"b@text##作者：##"))
        assertEquals("测试书",LegadoSelector.text(LegadoSelector.elements(doc,"class.book.0@tag.a").first(),"text"))
        assertEquals("另一本",LegadoSelector.text(doc,"@css:.book:eq(1) a@text"))
        assertEquals("https://example.com/novel/1",LegadoSelector.text(doc,"@XPath://a[1]/@href").lineSequence().first())
        assertEquals("测试书",LegadoSelector.text(rows[0],"a",html=true))
    }
    @Test fun readingSourcePreservesOriginalAndBlocksJava(){
        val original=JsonParser.parseString("""{"bookSourceUrl":"https://example.com","bookSourceName":"测试","enabled":false,"searchUrl":"/search?q={{key}}","ruleSearch":{"bookList":".book","name":"a@text","bookUrl":"a@href"},"ruleBookInfo":{"name":"h1@text"},"ruleToc":{"chapterList":".toc a","chapterName":"text","chapterUrl":"href"},"ruleContent":{"content":"#content@text"}}""").asJsonObject
        val rule=LegadoImport.convert(original)
        assertFalse(rule.disabled)
        assertEquals(false,rule.importEnabled)
        assertTrue(rule.compatibility.isNullOrBlank())
        assertEquals(original,JsonParser.parseString(rule.legadoJson))
        original.getAsJsonObject("ruleContent").addProperty("content","@js:java.webView(url)")
        val unsupported=LegadoImport.convert(original)
        assertTrue(unsupported.disabled)
        assertFalse(unsupported.compatibility.isNullOrBlank())
    }
    @Test fun oldSongsMigrateWithoutNullNewFields(){
        val song=musicGson().fromJson("""{"id":"1","title":"旧歌曲","artist":"作者","album":"","cover":"","duration":2,"qualities":[],"source":"kuwo"}""",Song::class.java)
        assertEquals("",song.description)
        assertEquals("",song.webUrl)
        assertEquals(0,song.cid)
    }
    @Test fun biliDurationSupportsHours(){
        assertEquals(183,BilibiliSource.durationSeconds("3:03"))
        assertEquals(3723,BilibiliSource.durationSeconds("1:02:03"))
    }
}

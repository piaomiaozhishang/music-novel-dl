package com.wang.sonovel

import com.wang.sonovel.core.*
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class ReadingAndUpdateTest {
    @Test fun multipleClassesAndOwnTextAreReadingCommands() {
        val doc=Jsoup.parse("""<div class="clear dir extra"><li><a href="/a">第一章</a></li></div><div class="dir"><clear><li>错误匹配</li></clear></div><a href="/part2">下一页</a>""","https://example.org/book")
        val rows=LegadoSelector.elements(doc,"class.dir clear@li")
        assertEquals(1,rows.size)
        assertEquals("第一章",LegadoSelector.text(rows.single(),"a@text"))
        assertEquals("https://example.org/part2",LegadoSelector.text(doc,"text.下一页@href"))
        assertEquals("",LegadoSelector.text(doc,"text.不存在@href"))
        assertEquals(1,LegadoSelector.elements(doc,"class.dir clear.0@li").size)
    }
    @Test fun htmlAndAllKeepTheContainerButHtmlRemovesScripts() {
        val doc=Jsoup.parse("""<div class="con"><p>正文</p><script>noise()</script></div>""")
        assertTrue(LegadoSelector.text(doc,".con@all").contains("<div"))
        assertTrue(LegadoSelector.text(doc,".con@all").contains("noise"))
        assertFalse(LegadoSelector.text(doc,".con@html").contains("noise"))
    }
    @Test fun readingIndexesXPathAndQuotedAttributesWork() {
        val doc=Jsoup.parse("<ul><li>A</li><li>B</li><li>C</li><li>D</li></ul><a data-id='a@b' href='/next'>下一页</a>","https://example.org")
        assertEquals("A\nC",LegadoSelector.text(doc,"li[0,2]@text"))
        assertEquals("B\nD",LegadoSelector.text(doc,"li[!0,2]@text"))
        assertEquals("D\nC\nB\nA",LegadoSelector.text(doc,"li[-1:0]@text"))
        assertEquals("B\nC",LegadoSelector.text(doc,"li.1:2@text"))
        assertEquals("https://example.org/next",LegadoSelector.text(doc,"//a[@data-id='a@b']/@href"))
        assertTrue(LegadoSelector.text(doc,"//a[@data-id='a@b']@html").contains("下一页"))
        assertEquals("下一页",LegadoSelector.text(doc,"a[data-id='a@b']@text"))
    }
    @Test fun scriptsReceiveOriginalPageAndStringListsNotElementHtml() {
        val rules=ReadingRules("https://example.org") { _,_,_,_->error("Network not expected") }
        val page="<div id='context'>原页面</div><a href='/a'>甲</a><a href='/b'>乙</a>"
        assertEquals("true",rules.text(page,"a.0@text<js>src.indexOf('原页面') >= 0</js>"))
        assertEquals("https://example.org/a|https://example.org/b",rules.text(page,"@js:java.getStringList('a@href').join('|')"))
        assertEquals("外部内容",rules.text(page,"@js:java.getString('p@text', '<p>外部内容</p>')"))
        assertEquals("甲",rules.text(page,"@@a.0@text"))
    }
    @Test fun readingShareLinksAndUtf16ImportsAreDecoded() {
        assertEquals("https://example.org/source.json",SourceImportInput.address("legado://import/bookSource?src=https%3A%2F%2Fexample.org%2Fsource.json"))
        val bytes=byteArrayOf(0xff.toByte(),0xfe.toByte())+"[{\"书源\":1}]".toByteArray(Charsets.UTF_16LE)
        assertEquals("[{\"书源\":1}]",SourceImportInput.decode(bytes.inputStream()))
    }
    @Test fun updateDownloadAddressIsOwnedByTheManifest() {
        val result=UpdateManifest.parse("""{"versionCode":9,"versionName":"0.9","downloadUrl":"https://example.org/my-release","updateMessage":"更新说明"}""",4) as UpdateResult.Available
        assertEquals("https://example.org/my-release",result.info.downloadUrl)
        assertFalse(result.info.forceUpdate)
        assertEquals(UpdateResult.Latest,UpdateManifest.parse("""{"versionCode":4,"versionName":"0.3.1","downloadUrl":""}""",4))
    }
    @Test fun invalidManifestsDoNotClaimLatestOrOpenNonWebLinks() {
        for(text in listOf("", "{}", "<html>登录</html>",
            """{"versionCode":5,"versionName":"0.4","downloadUrl":""}""",
            """{"versionCode":5,"versionName":"0.4","downloadUrl":"intent://install"}""")) {
            assertTrue(text,runCatching { UpdateManifest.parse(text,4) }.isFailure)
        }
    }
    @Test fun automaticCheckIsDueAfterTwelveHoursAndClockRollback() {
        val last=1_000_000L
        assertTrue(UpdateManifest.isDue(0,last))
        assertFalse(UpdateManifest.isDue(last,last+UpdateManifest.INTERVAL_MS-1))
        assertTrue(UpdateManifest.isDue(last,last+UpdateManifest.INTERVAL_MS))
        assertTrue(UpdateManifest.isDue(last,last-1))
    }
}

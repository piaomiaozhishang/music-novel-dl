package com.wang.sonovel

import com.google.gson.JsonParser
import com.wang.sonovel.core.UpdateManifest
import com.wang.sonovel.core.UpdateResult
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PublishedUpdateManifestTest {
    @Test fun changingOnlyVersionCodeOffersTheConfiguredDownloadPage() {
        val file=listOf(File("../update.json"),File("update.json")).first { it.isFile }
        val root=JsonParser.parseString(file.readText()).asJsonObject
        val installed=root.get("versionCode").asInt
        assertEquals(UpdateResult.Latest,UpdateManifest.parse(root.toString(),installed))
        val originalName=root.get("versionName").asString
        val address=root.get("downloadUrl").asString
        root.addProperty("versionCode",installed+1)
        val offered=UpdateManifest.parse(root.toString(),installed) as UpdateResult.Available
        assertEquals(installed+1,offered.info.versionCode)
        assertEquals(originalName,offered.info.versionName)
        assertEquals(address,offered.info.downloadUrl)
        assertTrue(address.startsWith("https://"))
        assertFalse(offered.info.forceUpdate)
    }
}

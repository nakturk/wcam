package com.wcam.app

import com.wcam.app.util.AppLanguageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class LocalizationTest {

    @Test
    fun effectiveLanguageTag_resolvesExplicitAndSystemLocales() {
        assertEquals("tr", AppLanguageManager.effectiveLanguageTag(AppLanguageManager.MODE_TR, "en"))
        assertEquals("en", AppLanguageManager.effectiveLanguageTag(AppLanguageManager.MODE_EN, "tr"))
        assertEquals("tr", AppLanguageManager.effectiveLanguageTag(AppLanguageManager.MODE_SYSTEM, "tr"))
        assertEquals("tr", AppLanguageManager.effectiveLanguageTag(AppLanguageManager.MODE_SYSTEM, "tr-TR"))
        assertEquals("en", AppLanguageManager.effectiveLanguageTag(AppLanguageManager.MODE_SYSTEM, "en"))
        assertEquals("en", AppLanguageManager.effectiveLanguageTag(AppLanguageManager.MODE_SYSTEM, "en-US"))
        assertEquals("en", AppLanguageManager.effectiveLanguageTag(AppLanguageManager.MODE_SYSTEM, "de"))
        assertEquals("en", AppLanguageManager.effectiveLanguageTag(AppLanguageManager.MODE_SYSTEM, ""))
    }

    @Test
    fun stringResources_haveExactKeyNonEmptyAndPlaceholderParityBetweenEnAndTr() {
        val resDir = resolveMainResDir()
        val enStrings = parseStringsXml(File(resDir, "values/strings.xml"), includeNonTranslatable = false)
        val trStrings = parseStringsXml(File(resDir, "values-tr/strings.xml"), includeNonTranslatable = true)

        assertTrue("Expected translatable strings in values/strings.xml", enStrings.isNotEmpty())
        assertEquals("Key mismatch between values/strings.xml and values-tr/strings.xml", enStrings.keys, trStrings.keys)

        val placeholderRegex = Regex("%([0-9]+\\$)?[-#+ 0,(]*[0-9.]*[a-zA-Z]")
        for ((key, enValue) in enStrings) {
            val trValue = trStrings.getValue(key)
            assertFalse("Empty English string for $key", enValue.isBlank())
            assertFalse("Empty Turkish string for $key", trValue.isBlank())

            val enPlaceholders = placeholderRegex.findAll(enValue).map { it.value }.sorted().toList()
            val trPlaceholders = placeholderRegex.findAll(trValue).map { it.value }.sorted().toList()
            assertEquals("Placeholder mismatch for key '$key'", enPlaceholders, trPlaceholders)
        }
    }

    private fun resolveMainResDir(): File {
        val candidates = listOf(
            File("src/main/res"),
            File("app/src/main/res")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Could not locate src/main/res from working directory ${File(".").absolutePath}")
    }

    private fun parseStringsXml(file: File, includeNonTranslatable: Boolean): Map<String, String> {
        assertTrue("Missing resource file: ${file.path}", file.isFile)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        val map = linkedMapOf<String, String>()
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as Element
            val name = el.getAttribute("name")
            val translatable = el.getAttribute("translatable")
            if (!includeNonTranslatable && translatable == "false") continue
            map[name] = el.textContent
        }
        return map
    }
}

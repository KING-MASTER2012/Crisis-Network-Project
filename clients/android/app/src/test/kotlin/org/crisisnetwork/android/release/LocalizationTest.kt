package org.crisisnetwork.android.release

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * English and Turkish must stay in step (phase A7). Lint finds a missing translation too, but only
 * in a lint run; this runs with the unit tests and also checks that both languages use the same
 * placeholders, so that a translation cannot drop the number a sentence is about.
 */
class LocalizationTest {
    private class Resources(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>)

    private fun load(relative: String): Resources {
        val document = ResourceFiles.parse(relative)
        val strings = linkedMapOf<String, String>()
        val plurals = linkedMapOf<String, Map<String, String>>()
        val children = document.documentElement.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i) as? Element ?: continue
            when (node.tagName) {
                "string" -> strings[node.getAttribute("name")] = node.textContent
                "plurals" -> {
                    val items = node.getElementsByTagName("item")
                    plurals[node.getAttribute("name")] = (0 until items.length).associate { index ->
                        val item = items.item(index) as Element
                        item.getAttribute("quantity") to item.textContent
                    }
                }
            }
        }
        return Resources(strings, plurals)
    }

    private val english = load("src/main/res/values/strings.xml")
    private val turkish = load("src/main/res/values-tr/strings.xml")

    /** `%1$s`, `%2$d`, `%d`: what has to survive a translation, in a form that does not depend on the order. */
    private fun placeholders(text: String): List<String> = Regex("%(?:(\\d+)\\$)?([sdf])").findAll(text).map { (it.groupValues[1].ifEmpty { "-" }) + it.groupValues[2] }.sorted().toList()

    @Test
    fun bothLanguagesHaveTheSameStrings() {
        assertEquals(english.strings.keys - turkish.strings.keys, emptySet<String>())
        assertEquals(turkish.strings.keys - english.strings.keys, emptySet<String>())
        assertEquals(english.plurals.keys, turkish.plurals.keys)
    }

    @Test
    fun noTextIsEmpty() {
        for ((name, text) in english.strings + turkish.strings) assertTrue("$name is empty", text.isNotBlank())
    }

    @Test
    fun translationsKeepThePlaceholders() {
        for ((name, text) in english.strings) {
            assertEquals("placeholders of $name", placeholders(text), placeholders(turkish.strings.getValue(name)))
        }
        for ((name, items) in english.plurals) {
            val other = placeholders(items.getValue("other"))
            for ((quantity, text) in turkish.plurals.getValue(name)) {
                // A language may write the number as a word in the "one" form; "other" always shows it.
                if (quantity == "other") assertEquals("placeholders of $name/$quantity", other, placeholders(text))
            }
        }
    }

    @Test
    fun everyPluralHasTheFormsOfBothLanguages() {
        // English and Turkish both use "one" and "other" (Android's rules for both languages).
        for ((name, items) in english.plurals + turkish.plurals) {
            assertTrue("$name needs 'other'", "other" in items)
            assertTrue("$name needs 'one'", "one" in items)
        }
    }
}

package org.crisisnetwork.android.release

import org.w3c.dom.Document
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Finds the files of the app module from a unit test, whether Gradle runs it from the module or from the project root. */
internal object ResourceFiles {
    private val roots = listOf(File("."), File("app"), File("clients/android/app"))

    fun find(relative: String): File =
        roots.map { File(it, relative) }.firstOrNull { it.isFile }
            ?: error("$relative not found; working directory is ${File(".").absoluteFile}")

    fun parse(relative: String): Document {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(find(relative))
    }
}

package org.crisisnetwork.android.core.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Small file helpers shared by the clock store and the secret vault. */
internal object AtomicFiles {
    /** Writes [bytes] to a temporary sibling and moves it over [target], so a crash never leaves half a file. */
    fun write(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeBytes(bytes)
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

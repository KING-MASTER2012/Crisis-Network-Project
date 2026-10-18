package org.crisisnetwork.android.core.runtime.clock

import org.crisisnetwork.android.core.runtime.AtomicFiles
import java.io.File
import java.io.IOException

/** Remembers the latest time the clock has handed out, across process restarts. */
interface HighWaterStore {
    /** The stored value, or `null` when there is none or it cannot be read. */
    fun read(): Long?

    /** Stores [valueMs]. May throw; the clock ignores failures. */
    fun write(valueMs: Long)
}

/** A [HighWaterStore] backed by one small file (put it in `noBackupFilesDir`). */
class FileHighWaterStore(private val file: File) : HighWaterStore {
    override fun read(): Long? =
        try {
            if (file.isFile) file.readText().trim().toLongOrNull() else null
        } catch (e: IOException) {
            null
        }

    override fun write(valueMs: Long) {
        AtomicFiles.write(file, valueMs.toString().encodeToByteArray())
    }
}

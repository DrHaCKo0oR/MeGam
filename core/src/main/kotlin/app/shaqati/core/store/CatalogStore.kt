package app.shaqati.core.store

import kotlinx.serialization.json.Json
import java.io.File

/** Persists the catalog as one JSON file, written atomically. */
class CatalogStore(private val file: File) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
        coerceInputValues = true
    }

    fun load(): CatalogSnapshot {
        if (!file.exists()) return CatalogSnapshot()
        return runCatching { json.decodeFromString(CatalogSnapshot.serializer(), file.readText()) }
            .getOrElse {
                // Keep the unreadable file for inspection and start fresh rather than crash.
                file.copyTo(File(file.parentFile, file.name + ".corrupt"), overwrite = true)
                CatalogSnapshot()
            }
    }

    @Synchronized
    fun save(snapshot: CatalogSnapshot) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(CatalogSnapshot.serializer(), snapshot))
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "could not replace ${file.path}" }
        }
    }
}

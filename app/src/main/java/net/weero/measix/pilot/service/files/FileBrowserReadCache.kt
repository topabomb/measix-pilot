package net.weero.measix.pilot.service.files

/** Read-only snapshots belong to one already-authorized browser session. Writes never consume this cache. */
internal class FileBrowserReadCache(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Entry<T>(val value: T, val expires: Long)
    private val lock = Any()
    private var revision = 0L
    private val directories = LinkedHashMap<String, Entry<BrowserDirectory>>(16, .75f, true)
    private val texts = LinkedHashMap<BrowserFile, Entry<BrowserTextDocument>>(4, .75f, true)

    fun invalidate() = synchronized(lock) { revision++; directories.clear(); texts.clear() }

    suspend fun directory(path: String, verify: suspend () -> Unit, load: suspend () -> BrowserDirectory): BrowserDirectory {
        verify()
        val generation = synchronized(lock) { revision }
        val cached = synchronized(lock) { directories[path]?.takeIf { it.expires > now() }?.value }
        if (cached != null) { verify(); return cached }
        val value = load()
        verify()
        synchronized(lock) {
            if (revision == generation && value.files.size <= 8192) {
                directories[path] = Entry(value, now() + 15_000)
                while (directories.size > 32 || directories.values.sumOf { it.value.files.size } > 8192)
                    directories.remove(directories.keys.first())
            }
        }
        return value
    }

    suspend fun text(file: BrowserFile, verify: suspend () -> Unit, load: suspend () -> BrowserTextDocument): BrowserTextDocument {
        verify()
        val generation = synchronized(lock) { revision }
        val cached = synchronized(lock) { texts[file]?.takeIf { it.expires > now() }?.value }
        if (cached != null) { verify(); return cached }
        val value = load()
        verify()
        synchronized(lock) {
            if (revision == generation && file.versioned && value.content.text.length <= 1_048_576) {
                texts[file] = Entry(value, now() + 30_000)
                while (texts.size > 4 || texts.values.sumOf { it.value.content.text.length.toLong() * 2 } > 4L * 1024 * 1024)
                    texts.remove(texts.keys.first())
            }
        }
        return value
    }

    suspend fun <T> writing(action: suspend () -> T): T {
        invalidate()
        return try { action() } finally { invalidate() }
    }
}

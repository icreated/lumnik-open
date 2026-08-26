import io.lumnik.hub.connector.transport.FileDescriptor

fun shouldProcess(file: FileDescriptor, source: Any?): Boolean {
    val minBytes = System.getenv("CSV_MIN_BYTES")?.toLongOrNull() ?: 100L
    return file.size() >= minBytes
}

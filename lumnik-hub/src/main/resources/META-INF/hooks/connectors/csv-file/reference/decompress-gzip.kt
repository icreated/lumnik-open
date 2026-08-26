import io.lumnik.hub.connector.transport.FileDescriptor
import java.io.InputStream
import java.util.zip.GZIPInputStream

fun transform(file: FileDescriptor, input: InputStream): InputStream =
    GZIPInputStream(input)

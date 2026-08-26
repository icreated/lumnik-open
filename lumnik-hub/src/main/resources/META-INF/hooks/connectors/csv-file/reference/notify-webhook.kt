import io.lumnik.hub.connector.transport.FileDescriptor
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

fun postProcess(file: FileDescriptor, result: Any?) {
    val url = System.getenv("INGEST_WEBHOOK_URL") ?: return
    // Generic payload — result kept as Any? to avoid hard coupling to CsvIngestResult
    val body = """{"file":"${file.path()}","name":"${file.name()}","size":${file.size()}}"""
    val client = HttpClient.newHttpClient()
    val req = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
    client.send(req, HttpResponse.BodyHandlers.discarding())
}

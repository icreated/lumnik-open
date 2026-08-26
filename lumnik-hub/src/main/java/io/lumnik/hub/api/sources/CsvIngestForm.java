package io.lumnik.hub.api.sources;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.File;
import java.util.Map;

/**
 * Multipart form for CSV ingest. The {@code fileUpload} field is used when arriving via HTTP;
 * the plain {@code file} field is a test convenience (bypasses multipart).
 *
 * <p>To ingest from a remote source (SFTP, S3), populate either:
 * <ul>
 *   <li>{@code transport} — for non-HTTP callers (tests, programmatic use)</li>
 *   <li>{@code transportJson} — the same map JSON-encoded for multipart HTTP</li>
 * </ul>
 * When no transport block is provided and a file is present, defaults to a local transport.
 */
public class CsvIngestForm {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @RestForm("file")
    public FileUpload fileUpload;

    /** Test convenience — populated directly in unit tests that don't go through HTTP. */
    public File file;

    @RestForm("name")        public String  name;
    @RestForm("scope")       public String  scope;
    @RestForm("tags")        public String  tags;
    @RestForm("sample_rows") public Integer sampleRows;
    @RestForm("delimiter")   public String  delimiter;
    @RestForm("encoding")    public String  encoding;
    @RestForm("limit")       public Integer limit;

    /** JSON-encoded transport block — used when submitting via multipart HTTP. */
    @RestForm("transport")
    public String transportJson;

    /** Convenience for non-HTTP callers (tests, programmatic use). */
    public Map<String, Object> transport;

    /** Returns the uploaded file from whichever source is set. */
    public File resolveFile() {
        if (file != null) return file;
        if (fileUpload != null) return fileUpload.uploadedFile().toFile();
        return null;
    }

    /**
     * Returns the resolved transport configuration map. Priority:
     * <ol>
     *   <li>{@code transport} field (programmatic / test)</li>
     *   <li>{@code transportJson} field (multipart HTTP)</li>
     *   <li>Implicit local transport derived from the uploaded file</li>
     * </ol>
     *
     * @throws IllegalArgumentException if neither transport block nor file is available
     */
    public Map<String, Object> resolveTransport() {
        if (transport != null) return transport;
        if (transportJson != null && !transportJson.isBlank()) {
            try {
                return MAPPER.readValue(transportJson, new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                throw new RuntimeException("invalid transport JSON: " + e.getMessage(), e);
            }
        }
        // Legacy / local-file path: derive an implicit local transport from the uploaded file.
        File f = resolveFile();
        if (f != null) {
            return Map.of("kind", "local", "path", f.getAbsolutePath());
        }
        throw new IllegalArgumentException("either file upload OR transport block required");
    }
}

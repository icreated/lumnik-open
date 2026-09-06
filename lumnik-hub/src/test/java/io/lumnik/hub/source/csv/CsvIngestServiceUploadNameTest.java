package io.lumnik.hub.source.csv;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.api.sources.CsvIngestForm;
import io.lumnik.hub.api.sources.CsvIngestResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.inject.Inject;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The HTTP upload pipeline names the table after the file the CALLER sent, never after
 * the server-side multipart temp file. The remote (FileSource) pipeline learned this
 * lesson first — its test pins "never named after the temp download"; this is the local
 * twin: RESTEasy buffers an upload as {@code resteasy-reactive…upload}, and deriving the
 * table name from that produced {@code ext.resteasy_reactive12939…upload} for the first
 * real installer. The client's filename travels in the multipart part
 * ({@code FileUpload.fileName()}) — that is the name the table carries.
 */
@QuarkusTest
class CsvIngestServiceUploadNameTest {

    @Inject
    CsvIngestService service;

    @Inject
    EntityManager em;

    @Test
    @Transactional
    void an_unnamed_upload_lands_in_a_table_named_after_the_clients_file() throws Exception {
        // The server-side buffer, named the way RESTEasy Reactive really names it.
        Path temp = Files.createTempFile("resteasy-reactive", "upload");
        Files.writeString(temp, "code,label\nA1,alpha\nB2,beta\n", StandardCharsets.UTF_8);
        temp.toFile().deleteOnExit();

        FileUpload upload = mock(FileUpload.class);
        when(upload.fileName()).thenReturn("ad_user.csv");
        when(upload.uploadedFile()).thenReturn(temp);

        CsvIngestForm form = new CsvIngestForm();
        form.fileUpload = upload;
        form.scope = "system";

        CsvIngestResult result = TenantContext.callAsSystem(() -> service.execute(form, 1L));

        assertFalse(result.tableName().contains("resteasy"),
            "the table must never be named after the multipart temp file: " + result.tableName());
        assertTrue(result.tableName().endsWith("ad_user"),
            "the table carries the caller's filename: " + result.tableName());

        // The same rule holds for every surface the ingest writes: the connector row's name
        // and the endpoint row's path both speak the caller's filename, not the buffer's.
        String connectorName = (String) em.createNativeQuery(
                "SELECT name FROM connector.connector WHERE id = :id")
            .setParameter("id", result.connectorId()).getSingleResult();
        assertTrue(connectorName.endsWith("ad_user"),
            "the connector carries the caller's filename: " + connectorName);
        String endpointPath = (String) em.createNativeQuery(
                "SELECT path FROM connector.connector_endpoint WHERE connector_id = :id")
            .setParameter("id", result.connectorId()).getSingleResult();
        assertTrue(endpointPath.contains("ad_user"),
            "the endpoint path names the caller's file: " + endpointPath);
    }

}

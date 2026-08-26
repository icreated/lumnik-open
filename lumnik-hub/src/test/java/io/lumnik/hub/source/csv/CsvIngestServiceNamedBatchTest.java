package io.lumnik.hub.source.csv;

import io.lumnik.common.tenant.CurrentTenantHolder;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.api.sources.CsvIngestForm;
import io.lumnik.hub.api.sources.CsvIngestResult;
import io.lumnik.hub.connector.mapping.writer.MultiTargetWriter;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * A11 (tribunal 2026-07-26). {@code name} makes every remote file register its own dormant
 * connector under that ONE name, so file 2 violates {@code connector_tenant_id_name_key} and the
 * whole run aborts on a raw Postgres error — and the default remote gesture is multi-file
 * ({@code lm csv ingest sftp://host/dir/} globs {@code *.csv}).
 *
 * <p><b>VERDICT: refuse, don't redesign</b> (jurisprudence of A4/A5/A9). Fusing a folder into one
 * dataset means one dormant connector for the batch, which is what a connector manifest with a
 * glob already declares on the scheduled path; the one-shot path is an <i>inspection</i> gesture.
 * Nothing is taken away — this never worked — a constraint violation at run time becomes an
 * honest refusal before a single byte is downloaded.
 */
@QuarkusTest
class CsvIngestServiceNamedBatchTest {

    @Inject CsvIngestService service;

    /** Spied to prove the refusal lands before anything is written. */
    @InjectSpy MultiTargetWriter writer;

    @AfterEach
    void clearTenant() {
        CurrentTenantHolder.clear();
    }

    @Test
    void a_named_multi_file_ingest_is_refused_before_anything_is_written() throws Exception {
        Path dir = Files.createTempDirectory("a11_refuse_");
        Files.writeString(dir.resolve("a-" + System.nanoTime() + ".csv"), "id,email\n1,alice@x\n");
        Files.writeString(dir.resolve("b-" + System.nanoTime() + ".csv"), "id,email\n2,bob@x\n");

        CsvIngestForm form = remoteForm(dir);
        form.name = "clients_" + System.nanoTime();

        var ex = assertThrows(IllegalArgumentException.class,
            () -> TenantContext.callAsSystem(() -> service.execute(form, 1L)));

        String msg = ex.getMessage();
        assertTrue(msg.contains("name"), "the refusal must name the offending option: " + msg);
        assertTrue(msg.contains("single file"), "the refusal must teach the single-file escape: " + msg);
        assertTrue(msg.contains("manifest"), "the refusal must teach the glob-manifest route: " + msg);
        verifyNoInteractions(writer);
    }

    @Test
    void a_named_single_file_ingest_still_works() throws Exception {
        Path dir = Files.createTempDirectory("a11_single_");
        Files.writeString(dir.resolve("only-" + System.nanoTime() + ".csv"), "id,email\n1,alice@x\n");

        CsvIngestForm form = remoteForm(dir);
        form.name = "clients_" + System.nanoTime();

        CsvIngestResult result = TenantContext.callAsSystem(() -> service.execute(form, 1L));

        assertEquals(1, result.rowsRead(), "one file plus a name is the supported shape");
        assertTrue(result.tableName().startsWith("ext."), "the name still drives the ext.* table");
    }

    /**
     * A blank name is an absent name — everywhere, not only where someone remembered to check.
     * The refusal treats blank as unset; {@code buildSubForm} used to pass the blank through, and
     * {@code executeLocalFile} then fell back to the name of the ephemeral <b>temp download</b>,
     * landing the rows in {@code ext.csv_ingest_remote_<random>} — a different table every run.
     */
    @Test
    void a_blank_name_falls_back_to_the_remote_filename_not_the_temp_download() throws Exception {
        Path dir   = Files.createTempDirectory("a11_blank_");
        Path alpha = dir.resolve("alpha" + System.nanoTime() + ".csv");
        Path beta  = dir.resolve("beta" + System.nanoTime() + ".csv");
        Files.writeString(alpha, "id,email\n1,alice@x\n");
        Files.writeString(beta,  "id,email\n2,bob@x\n");
        // listSince sorts by lastModified and the aggregate result keeps the LAST file's table.
        // Two files written back to back can share a coarse timestamp, and a tie leaves the order
        // to the filesystem — so the mtimes are set apart explicitly, as in the A8-bis tests.
        Files.setLastModifiedTime(alpha, FileTime.from(Instant.parse("2026-07-26T10:00:00Z")));
        Files.setLastModifiedTime(beta,  FileTime.from(Instant.parse("2026-07-26T10:05:00Z")));

        CsvIngestForm form = remoteForm(dir);
        form.name = "   ";

        CsvIngestResult result = TenantContext.callAsSystem(() -> service.execute(form, 1L));

        assertEquals(2, result.rowsRead(), "a blank name is unset, so the batch is not refused");
        assertFalse(result.tableName().contains("csv_ingest_remote"),
            "the table must never be named after the temp download: " + result.tableName());
        assertTrue(result.tableName().contains("beta"),
            "each file lands in a table named after the REMOTE file: " + result.tableName());
    }

    /** A local-transport form with an explicit glob path — the one-shot remote (FileSource) pipeline. */
    private CsvIngestForm remoteForm(Path dir) {
        CsvIngestForm form = new CsvIngestForm();
        form.scope      = "system";
        form.transport  = new HashMap<>(Map.of(
            "kind", "local",
            "path", dir.toAbsolutePath() + "/*.csv"
        ));
        return form;
    }
}

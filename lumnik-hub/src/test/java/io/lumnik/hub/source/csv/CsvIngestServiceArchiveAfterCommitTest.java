package io.lumnik.hub.source.csv;

import io.lumnik.common.tenant.CurrentTenantHolder;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.api.sources.CsvIngestForm;
import io.lumnik.hub.api.sources.CsvIngestResult;
import io.lumnik.hub.connector.transport.hook.FileSourceHookRunner;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;

/**
 * A8's twin on the one-shot path (tribunal 2026-07-26). {@code CsvIngestService.execute} <b>was</b>
 * {@code @Transactional} and the remote loop ran {@code markProcessed} — a real MOVE/DELETE on
 * the source — inside it, per file. With N files in ONE transaction, a failure on file k rolled
 * back every row while files 1..k-1 were already gone from the source: unreplayable loss, the
 * same defect A8 closed on the connector path (FileSourceCursorReaderArchiveTest).
 *
 * <p>The contract these tests pin: <b>the commit is the proof</b>. Nothing is archived until the
 * transaction that carries its rows has committed; once it has, every ingested file is archived,
 * and neither post-commit side effect may silence the other.
 */
@QuarkusTest
class CsvIngestServiceArchiveAfterCommitTest {

    private static final Instant T1 = Instant.parse("2026-07-26T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-07-26T10:05:00Z");

    @Inject CsvIngestService service;

    /** Spied, not mocked: the real hook runner still runs — we only need to witness the calls. */
    @InjectSpy FileSourceHookRunner hooks;

    @AfterEach
    void clearTenant() {
        CurrentTenantHolder.clear();
    }

    @Test
    void a_failing_file_leaves_every_source_file_in_place() throws Exception {
        Path dir   = Files.createTempDirectory("a8_twin_fail_");
        Path first = dir.resolve("1-small-" + System.nanoTime() + ".csv");
        Path huge  = dir.resolve("2-huge-" + System.nanoTime() + ".csv");

        Files.writeString(first, "id,email\n1,alice@x\n2,bob@x\n");
        writeOversized(huge);
        // listSince sorts by lastModified: the small file is ingested first, the huge one aborts.
        Files.setLastModifiedTime(first, FileTime.from(T1));
        Files.setLastModifiedTime(huge,  FileTime.from(T2));

        CsvIngestForm form = remoteForm(dir);

        assertThrows(RuntimeException.class,
            () -> TenantContext.callAsSystem(() -> service.execute(form, 1L)),
            "the oversized second file must abort the ingest");

        assertTrue(Files.exists(first),
            "A8 twin: the transaction rolled back, so the first file's rows were never committed — "
            + "its source file must still be there to replay");
    }

    @Test
    void a_successful_run_archives_every_ingested_file() throws Exception {
        Path dir    = Files.createTempDirectory("a8_twin_ok_");
        Path first  = dir.resolve("a-" + System.nanoTime() + ".csv");
        Path second = dir.resolve("b-" + System.nanoTime() + ".csv");

        Files.writeString(first,  "id,email\n1,alice@x\n");
        Files.writeString(second, "id,email\n2,bob@x\n");
        Files.setLastModifiedTime(first,  FileTime.from(T1));
        Files.setLastModifiedTime(second, FileTime.from(T2));

        CsvIngestForm form = remoteForm(dir);

        TenantContext.callAsSystem(() -> service.execute(form, 1L));

        assertFalse(Files.exists(first),  "after_process=delete must archive the first ingested file");
        assertFalse(Files.exists(second), "after_process=delete must archive the second ingested file");
    }

    @Test
    void a_failed_archive_does_not_swallow_post_process() throws Exception {
        Path dir  = Files.createTempDirectory("a8_twin_warn_");
        Path only = dir.resolve("c-" + System.nanoTime() + ".csv");
        Files.writeString(only, "id,email\n1,alice@x\n");
        // after_process=move onto an archive path that is an existing FILE: createDirectories throws,
        // so markProcessed fails — after the commit, with the rows already durable.
        Path blocker = Files.createTempFile("a8_blocker_", ".txt");

        CsvIngestForm form = remoteForm(dir);
        form.transport.put("after_process", "move");
        form.transport.put("archive_path", blocker.toAbsolutePath().toString());

        CsvIngestResult result = TenantContext.callAsSystem(() -> service.execute(form, 1L));

        assertEquals(1, result.rowsRead(),
            "the rows are committed — a failed archive must not fail the run");
        assertTrue(Files.exists(only), "a failed archive leaves the file on the source");
        verify(hooks).postProcess(isNull(), any(), any());
    }

    /**
     * A local-transport form with an explicit glob path — the one-shot remote (FileSource) pipeline.
     * {@code name} is deliberately left unset: each remote file derives its own dormant-connector
     * name from its filename, and a shared explicit name collides on
     * {@code connector_tenant_id_name_key} from the second file onward.
     */
    private CsvIngestForm remoteForm(Path dir) {
        CsvIngestForm form = new CsvIngestForm();
        form.scope      = "system";
        form.transport  = new HashMap<>(Map.of(
            "kind",          "local",
            "path",          dir.toAbsolutePath() + "/*.csv",
            "after_process", "delete",
            "max_file_size", 1024L
        ));
        return form;
    }

    private void writeOversized(Path p) throws Exception {
        try (BufferedWriter out = Files.newBufferedWriter(p)) {
            out.write("id,email\n");
            for (int i = 0; i < 200; i++) out.write(i + ",padpadpadpadpadpadpadpad@x\n");
        }
        assertTrue(Files.size(p) > 1024L, "sanity: the second file must exceed max_file_size");
    }
}

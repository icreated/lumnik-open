package io.lumnik.hub.source.csv;

import io.lumnik.common.tenant.CurrentTenantHolder;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.api.sources.CsvIngestForm;
import io.lumnik.hub.api.sources.CsvIngestResult;
import io.lumnik.hub.ask.SchemaCardStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CsvIngestServiceTest {

    @Inject CsvIngestService service;
    @Inject EntityManager em;
    @Inject UserTransaction utx;
    @Inject SchemaCardStore schemaCards;

    @AfterEach
    void clearTenant() {
        CurrentTenantHolder.clear();
    }

    @Test
    void ingest_creates_dormant_connector_and_writes_rows() throws Exception {
        Path csv = Files.createTempFile("test_ingest_", ".csv");
        Files.writeString(csv,
            "id,email,amount\n" +
            "1,alice@x.io,100\n" +
            "2,bob@x.io,200\n" +
            "3,charlie@x.io,300\n");

        String name = "test_customers_" + System.nanoTime();
        CsvIngestForm form = new CsvIngestForm();
        form.file = csv.toFile();
        form.name = name;
        form.scope = "system";
        form.tags = "demo";
        form.sampleRows = 10;
        form.limit = -1;

        CsvIngestResult result = TenantContext.callAsSystem(() -> {
            utx.begin();
            try {
                CsvIngestResult r = service.execute(form, 1L);
                utx.commit();
                return r;
            } catch (Exception e) {
                utx.rollback();
                throw e;
            }
        });

        assertEquals(3, result.rowsRead());
        assertEquals(3, result.rowsWritten());
        assertNotNull(result.connectorId());
        assertNotNull(result.endpointId());
        assertTrue(result.tableName().startsWith("ext."));
        assertFalse(result.promoteColumns().isEmpty(), "should have inferred promoted columns");

        // Verify connector and endpoint were persisted and are dormant (enabled=false).
        Number connCount = (Number) em.createNativeQuery(
            "SELECT count(*) FROM connector.connector WHERE id = '" + result.connectorId() + "'")
            .getSingleResult();
        assertEquals(1L, connCount.longValue(), "connector row should exist");

        Number epCount = (Number) em.createNativeQuery(
            "SELECT count(*) FROM connector.connector_endpoint WHERE id = '" + result.endpointId() + "'")
            .getSingleResult();
        assertEquals(1L, epCount.longValue(), "endpoint row should exist");

        // Verify rows landed in ext.* table.
        Number rowCount = (Number) em.createNativeQuery(
            "SELECT count(*) FROM " + result.tableName())
            .getSingleResult();
        assertEquals(3L, rowCount.longValue(), "3 rows should be in ext table");

        Files.deleteIfExists(csv);
    }

    @Test
    void ingest_auto_detects_name_from_filename_when_name_not_given() throws Exception {
        Path csv = Files.createTempFile("products_export", ".csv");
        Files.writeString(csv, "sku,price\nABC,9.99\nDEF,19.99\n");

        CsvIngestForm form = new CsvIngestForm();
        form.file = csv.toFile();
        // name intentionally null — should derive from filename
        CsvIngestResult result = TenantContext.callAsSystem(() -> {
            utx.begin();
            try {
                CsvIngestResult r = service.execute(form, 1L);
                utx.commit();
                return r;
            } catch (Exception e) {
                utx.rollback();
                throw e;
            }
        });

        assertEquals(2, result.rowsWritten());
        assertTrue(result.tableName().startsWith("ext."), "table must be in ext schema");
        assertTrue(result.tableName().contains("products"), "table name should derive from filename");

        Files.deleteIfExists(csv);
    }

    @Test
    void ingest_respects_row_limit() throws Exception {
        Path csv = Files.createTempFile("limit_test_", ".csv");
        StringBuilder sb = new StringBuilder("id,val\n");
        for (int i = 1; i <= 20; i++) sb.append(i).append(",v").append(i).append("\n");
        Files.writeString(csv, sb.toString());

        CsvIngestForm form = new CsvIngestForm();
        form.file = csv.toFile();
        form.name = "limit_test_" + System.nanoTime();
        form.limit = 5;

        CsvIngestResult result = TenantContext.callAsSystem(() -> {
            utx.begin();
            try {
                CsvIngestResult r = service.execute(form, 1L);
                utx.commit();
                return r;
            } catch (Exception e) {
                utx.rollback();
                throw e;
            }
        });

        assertEquals(5, result.rowsRead());
        assertEquals(5, result.rowsWritten());

        Files.deleteIfExists(csv);
    }

    @Test
    void promotes_csv_header_containing_a_dot() throws Exception {
        // A dotted header is common in real CSV exports. The promote path was built as "$." + header,
        // so "order.id" became JsonPath $.order.id — read as a NESTED order.id, not the flat key →
        // the promoted column silently stays NULL (data loss). It must extract the literal key.
        Path csv = Files.createTempFile("dotted_", ".csv");
        Files.writeString(csv, "ref,order.id\nA,1001\nB,1002\n");

        CsvIngestForm form = new CsvIngestForm();
        form.file = csv.toFile();
        form.name = "dotted_test_" + System.nanoTime();
        CsvIngestResult result = TenantContext.callAsSystem(() -> {
            utx.begin();
            try {
                CsvIngestResult r = service.execute(form, 1L);
                utx.commit();
                return r;
            } catch (Exception e) {
                utx.rollback();
                throw e;
            }
        });

        Number nonNull = (Number) em.createNativeQuery(
            "SELECT count(*) FROM " + result.tableName() + " WHERE \"order.id\" IS NOT NULL")
            .getSingleResult();
        assertEquals(2L, nonNull.longValue(),
            "dotted-header column must be populated, not silently null from a nested-path read");

        Files.deleteIfExists(csv);
    }

    @Test
    void ingest_infers_types_for_numeric_and_date_columns() throws Exception {
        Path csv = Files.createTempFile("typed_", ".csv");
        Files.writeString(csv,
            "id,amount,active,birthday\n" +
            "1,100,true,2020-01-15\n" +
            "2,200,false,2021-06-01\n");

        CsvIngestForm form = new CsvIngestForm();
        form.file = csv.toFile();
        form.name = "typed_test_" + System.nanoTime();
        CsvIngestResult result = TenantContext.callAsSystem(() -> {
            utx.begin();
            try {
                CsvIngestResult r = service.execute(form, 1L);
                utx.commit();
                return r;
            } catch (Exception e) {
                utx.rollback();
                throw e;
            }
        });

        var byName = new java.util.HashMap<String, String>();
        result.promoteColumns().forEach(c -> byName.put(c.name(), c.type()));
        // "id" is a reserved HybridJsonb column — it is used as uniqueKeyPath but not promoted.
        assertFalse(byName.containsKey("id"), "id must not be in promoted columns (reserved)");
        assertEquals("bigint",  byName.get("amount"));
        assertEquals("boolean", byName.get("active"));
        // DATE/TIMESTAMPTZ are stored as TEXT in v1 to avoid ObjectMapper java.time serialization
        // issues in RowHasher. Temporal promotion via typed config is available via REST connector.
        assertEquals("text", byName.get("birthday"));

        Files.deleteIfExists(csv);
    }

    /**
     * The one-shot writes no RAG chunks — its MappingTarget carries {@code rag = null}
     * ({@link CsvIngestService}), and MultiTargetWriter indexes only when {@code target.rag() != null}.
     * It used to persist {@code rag: {enabled, scope}} into the endpoint config anyway, which
     * {@code lm connector get} shows to a user — a declared intent nothing honours: the csv-file
     * connector's buildSpec carries no rag target, and its validator has no rag block at all.
     */
    @Test
    void endpoint_config_declares_no_rag_intent_it_cannot_honour() throws Exception {
        Path csv = Files.createTempFile("test_noragcfg_", ".csv");
        Files.writeString(csv, "id,email\n1,alice@x.io\n2,bob@x.io\n");

        CsvIngestForm form = new CsvIngestForm();
        form.file = csv.toFile();
        form.name = "test_noragcfg_" + System.nanoTime();
        form.scope = "billing";
        form.sampleRows = 10;
        form.limit = -1;

        CsvIngestResult result = TenantContext.callAsSystem(() -> {
            utx.begin();
            try {
                CsvIngestResult r = service.execute(form, 1L);
                utx.commit();
                return r;
            } catch (Exception e) {
                utx.rollback();
                throw e;
            }
        });

        String cfg = (String) em.createNativeQuery(
            "SELECT config::text FROM connector.connector_endpoint WHERE id = :id")
            .setParameter("id", result.endpointId())
            .getSingleResult();

        assertFalse(cfg.contains("\"rag\""),
            () -> "the endpoint config declares a rag intent nothing consumes: " + cfg);
        assertTrue(cfg.contains("\"target\""),
            () -> "the target block must survive — lm endpoint purge reads target.table: " + cfg);

        Files.deleteIfExists(csv);
    }


    /**
     * After a one-shot the CLI printed {@code Ready to chat: lm ask "…" --scope <scope>}.
     * `lm ask` hardcodes {@code mode: "analytic"}, and AskToSqlAgent's very first act is
     * {@code store.load(tenant, scope).orElseThrow(NoSchemaException::new)} — before any model is
     * touched. Cards are written only by DiscoveryService.rediscover, whose callers are a completed
     * connector run, `lm entity apply`, or a manual POST. Since C4/P18 PR1 HybridJsonbWriter
     * ATTEMPTS to register the {@code ext.*} table — but on the one-shot path the write runs
     * inside the still-uncommitted transaction that creates the connector row, so the registry
     * FK (admin DS, outside that tx) refuses it and the fail-soft guard skips registration
     * (see registerIfNeeded). No registry row, no run, no rediscover: the scope holds no card.
     * This pins the real behaviour so the promise cannot come back.
     */
    @Test
    void one_shot_leaves_its_scope_with_no_schema_card_so_the_ask_cannot_answer() throws Exception {
        Path csv = Files.createTempFile("test_readytochat_", ".csv");
        Files.writeString(csv, "id,email\n1,alice@x.io\n2,bob@x.io\n");

        String scope = "demo_readytochat_" + System.nanoTime();
        CsvIngestForm form = new CsvIngestForm();
        form.file = csv.toFile();
        form.name = "test_readytochat_" + System.nanoTime();
        form.scope = scope;
        form.sampleRows = 10;
        form.limit = -1;

        CsvIngestResult result = TenantContext.callAsSystem(() -> {
            utx.begin();
            try {
                CsvIngestResult r = service.execute(form, 1L);
                utx.commit();
                return r;
            } catch (Exception e) {
                utx.rollback();
                throw e;
            }
        });
        assertEquals(2, result.rowsWritten(), "the ingest itself must have worked");

        TenantContext.callAsSystem(() -> {
            assertTrue(schemaCards.load(1L, scope).isEmpty(),
                () -> "the one-shot left a schema card for " + scope
                    + " — if this ever passes, the ask CAN answer and the CLI may promise it again");
            return null;
        });

        Files.deleteIfExists(csv);
    }
}

package io.lumnik.hub.process;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.ask.SchemaCardStore;
import io.lumnik.hub.testsupport.YamlBody;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * #1838: a Process on an Entity reads each source by its match key; the hub builds the index in the background and
 * says so — a {@code warnings} entry for as long as an expected index is not valid, on the Process apply and on the
 * apply of the Entity it watches (a changed key expression needs a new index with no new Process).
 */
@QuarkusTest
class MatchIndexWarningsIT {

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject SchemaCardStore cards;
    @Inject MatchIndexReconciler reconciler;

    private static final String ERP = "t_mxw_erp";
    private static final String CRM = "t_mxw_crm";
    private static final String VIEW = "connector.v_mxwfused";

    private void exec(String... sql) throws Exception {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            for (String s : sql) st.execute(s);
        }
    }

    @BeforeEach
    void seed() throws Exception {
        exec("DELETE FROM core.process_manifest WHERE tenant_id = 1 AND name = 'mxw-gold'",
            "DROP VIEW IF EXISTS " + VIEW,
            "DROP TABLE IF EXISTS connector." + ERP, "DROP TABLE IF EXISTS connector." + CRM,
            "CREATE TABLE connector." + ERP + " (tenant_id bigint NOT NULL, code text, alt text, credit text, qty int)",
            "CREATE TABLE connector." + CRM + " (tenant_id bigint NOT NULL, customer text, segment text, qty2 numeric)",
            "DELETE FROM connector.dynamic_table WHERE tenant_id = 1 AND mapping_id IN ('mxw-erp', 'mxw-crm')",
            "INSERT INTO connector.dynamic_table (tenant_id, mapping_id, table_schema, table_name, schema_inferred) "
                + "VALUES (1, 'mxw-erp', 'connector', '" + ERP + "', '{}'::jsonb), (1, 'mxw-crm', 'connector', '" + CRM + "', '{}'::jsonb)");
        applyEntity("code").statusCode(200);
        List<SchemaCard.Column> cols = List.of(new SchemaCard.Column("code", "text", "Code"),
            new SchemaCard.Column("segment", "text", "Segment"));
        cards.save(1L, "registry", new SchemaCard("mxwdesk", List.of(new SchemaCard.Table(VIEW, "MxwFused", cols))));
    }

    private ValidatableResponse applyEntity(String erpKeyColumn) {
        return applyEntity(erpKeyColumn, "customer");
    }

    private ValidatableResponse applyEntity(String erpKeyColumn, String crmKeyColumn) {
        return given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml")
            .body("kind: Entity\nname: MxwFused\nmatch: [code]\nscopes: [mxwdesk]\nsources:\n"
                + "  - table: " + ERP + "\n    map: { " + erpKeyColumn + ": code, credit: credit }\n"
                + "  - table: " + CRM + "\n    map: { " + crmKeyColumn + ": code, segment: segment }\n")
            .post("/api/entities/apply").then();
    }

    private ValidatableResponse applyProcess() {
        return given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml")
            .body("apiVersion: apps.lumnik.io/v1\nkind: Process\nmetadata: { name: mxw-gold }\nspec:\n  scope: mxwdesk\n"
                + "  on: { event: data.row.changed, table: " + VIEW + " }\n  when: [ segment=GOLD ]\n"
                + "  emit: { type: decision.mxw, message: \"GOLD\" }\n")
            .post("/api/processes/apply").then();
    }

    private int validIndexes() throws Exception {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             var rs = st.executeQuery("SELECT count(*) FROM pg_index i JOIN pg_class ic ON ic.oid = i.indexrelid "
                 + "JOIN pg_class t ON t.oid = i.indrelid WHERE t.relname IN ('" + ERP + "', '" + CRM + "') "
                 + "AND ic.relname LIKE '%\\_mx\\_%' AND i.indisvalid")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** The build that apply started in the background may hold the lock a pass wants: pass until the indexes are there. */
    private void sweepUntil(int valid) throws Exception {
        for (int i = 0; i < 200 && validIndexes() < valid; i++) {
            reconciler.sweep();
            Thread.sleep(100);
        }
        assertThat(validIndexes()).isEqualTo(valid);
    }

    private static String pending(String table) {
        return "managed index on connector." + table + " is pending; Entity Process reads may be slower until it is ready";
    }

    @Test
    void theProcessApplySaysWhichIndexesArePending_thenNothingOnceTheyAreBuilt() throws Exception {
        applyProcess().statusCode(200)
            .body("warnings", hasItem(pending(ERP)))
            .body("warnings", hasItem(pending(CRM)));

        sweepUntil(2);

        applyProcess().statusCode(200).body("warnings", nullValue());
    }

    @Test
    void aProcessOnATableEntityBuiltLaterHasNothingToWarnAbout() throws Exception {
        // a source that is not there yet cannot be indexed, nor can it be read: no warning, no refusal
        exec("DROP TABLE connector." + CRM + " CASCADE");
        applyProcess().statusCode(200).body("warnings", not(hasItem(pending(CRM))));
    }

    @Test
    void reApplyingTheEntityWithAnotherKeyColumnWarnsAgain() throws Exception {
        applyProcess().statusCode(200);
        sweepUntil(2);
        applyEntity("code").statusCode(200).body("warnings", nullValue());

        // the Process is unchanged, the expression is not: (tenant_id, alt) is a new index
        applyEntity("alt").statusCode(200).body("warnings", hasItem(pending(ERP)));

        sweepUntil(3);
        applyEntity("alt").statusCode(200).body("warnings", nullValue());
    }

    /** The Process stays enabled when its Entity is re-applied: the new shape must not leave it on a read that can only scan. */
    @Test
    void reApplyingAWatchedEntityWithAKeyNoIndexCanServeIsRefusedAndChangesNothing() throws Exception {
        applyProcess().statusCode(200);

        applyEntity("qty", "qty2").statusCode(400)
            .body("error", containsString("connector." + ERP))
            .body("error", containsString("match key"));

        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            var stored = st.executeQuery("SELECT yaml_text FROM connector.entity_manifest WHERE lower(name) = 'mxwfused'");
            stored.next();
            assertThat(stored.getString(1)).contains("code: code").doesNotContain("qty");
            var view = st.executeQuery("SELECT pg_get_viewdef('connector.v_mxwfused'::regclass)");
            view.next();
            assertThat(view.getString(1)).doesNotContain("qty");
        }
    }

    /** A process applied before the refusal existed sits on a key no index can serve: it keeps running, and the hub builds nothing for it. */
    @Test
    void aProcessAlreadyOnAKeyNoIndexCanServeGetsNoIndex() throws Exception {
        applyProcess().statusCode(200);
        sweepUntil(2);

        exec("UPDATE connector.entity_manifest SET yaml_text = replace(replace(yaml_text, 'code: code', 'qty: code'), 'customer: code', 'qty2: code') "
            + "WHERE lower(name) = 'mxwfused'");
        for (int i = 0; i < 3; i++) reconciler.sweep();

        assertThat(validIndexes()).as("the old (code) indexes stay, nothing is built on qty / qty2").isEqualTo(2);
    }

    @Test
    void anEntityNobodyWatchesMayHaveAnyKey() throws Exception {
        applyEntity("qty", "qty2").statusCode(200);
    }

    @Test
    void anEntityNobodyWatchesGetsNoWarning() throws Exception {
        applyEntity("code").statusCode(200).body("warnings", nullValue());
    }

    @Test
    void theReconcilerBuildsTheIndexesOfAWatchedEntity() throws Exception {
        applyProcess().statusCode(200);

        sweepUntil(2);

        assertThat(validIndexes()).isEqualTo(2);
    }
}

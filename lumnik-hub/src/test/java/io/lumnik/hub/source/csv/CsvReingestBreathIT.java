package io.lumnik.hub.source.csv;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.api.sources.CsvIngestForm;
import io.lumnik.hub.api.sources.CsvIngestResult;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The breath by re-ingestion (memory: breathing-not-kind): re-running the SAME one-shot
 * command is one more inspiration — the connector is reused, rows dedup by hash, the
 * card and descriptor follow — never the 409 the first day's refusal answered. The 409
 * survives for what it truly guards: a name that belongs to a CONFIGURED connector.
 */
@QuarkusTest
class CsvReingestBreathIT {

    @Inject CsvIngestService service;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    private String lastIngestedFile;

    private CsvIngestResult ingest(String name, String scope, String content) throws Exception {
        Path csv = Files.createTempFile("breath", ".csv");
        Files.writeString(csv, content, StandardCharsets.UTF_8);
        csv.toFile().deleteOnExit();
        lastIngestedFile = csv.toFile().getName();
        CsvIngestForm form = new CsvIngestForm();
        form.file = csv.toFile();
        form.name = name;
        form.scope = scope;
        return QuarkusTransaction.requiringNew().call(() ->
            TenantContext.callAsSystem(() -> service.execute(form, 1L)));
    }

    @Test
    void the_same_command_twice_is_one_more_breath_not_a_refusal() throws Exception {
        CsvIngestResult first = ingest("breath_probe", "breathscope",
            "code,amount\nA1,10.5\nB2,20\n");
        assertThat(first.refreshed()).isFalse();

        // Second breath: one row unchanged, one changed, one new — and a NEW column.
        CsvIngestResult second = ingest("breath_probe", "breathscope",
            "code,amount,label\nA1,10.5,alpha\nB2,25,beta\nC3,30,gamma\n");

        assertThat(second.refreshed()).as("the second breath says so").isTrue();
        assertThat(second.connectorId()).as("same connector, one more inspiration")
            .isEqualTo(first.connectorId());

        assertThat(Jdbc.queryOne(adminDs, "breath.count", "SELECT count(*) FROM ext.breath_probe",
                b -> { }, rs -> rs.getLong(1)))
            .as("rows dedup by key, never duplicate").contains(3L);

        // The breath's config write must SURVIVE the commit: a stale managed entity
        // (loaded by findPrior, dirtied by setTags) once flushed its old config over the
        // fresh one — full-column UPDATE, no @DynamicUpdate. Found by review, pinned here.
        assertThat(Jdbc.queryOne(adminDs, "breath.config",
                "SELECT config->>'path' FROM connector.connector WHERE id = '" + first.connectorId() + "'",
                b -> { }, rs -> rs.getString(1)))
            .as("the connector's config names the LAST breathed file")
            .contains(lastIngestedFile);

        assertThat(Jdbc.queryOne(adminDs, "breath.card",
                "SELECT card::text FROM connector.schema_card WHERE tenant_id = 1 AND scope = 'breathscope'",
                b -> { }, rs -> rs.getString(1)))
            .as("the card follows the drift").hasValueSatisfying(card -> assertThat(card).contains("label"));
    }

    @Test
    void a_new_scope_republishes_and_the_old_card_lets_go() throws Exception {
        ingest("breath_move", "scope_before", "code\nA1\n");
        CsvIngestResult second = ingest("breath_move", "scope_after", "code\nA1\n");

        // The trap only arms when tags CHANGE: setTags then dirties the entity findPrior
        // loaded with the OLD config, and the commit flush (full-column, no @DynamicUpdate)
        // once wrote that stale config over the upsert's fresh one. Same-scope breaths never
        // dirty the entity, which is why the sibling test could not catch this.
        assertThat(Jdbc.queryOne(adminDs, "breath.move.config",
                "SELECT config->>'path' FROM connector.connector WHERE id = '" + second.connectorId() + "'",
                b -> { }, rs -> rs.getString(1)))
            .as("the config write must survive the tag-dirtied flush")
            .contains(lastIngestedFile);

        assertThat(Jdbc.queryOne(adminDs, "breath.after",
                "SELECT card::text FROM connector.schema_card WHERE tenant_id = 1 AND scope = 'scope_after'",
                b -> { }, rs -> rs.getString(1)))
            .hasValueSatisfying(card -> assertThat(card).contains("breath_move"));

        Jdbc.queryOne(adminDs, "breath.before",
                "SELECT card::text FROM connector.schema_card WHERE tenant_id = 1 AND scope = 'scope_before'",
                b -> { }, rs -> rs.getString(1))
            .ifPresent(card -> assertThat(card)
                .as("the old scope's card no longer lists a table that moved away")
                .doesNotContain("breath_move"));
    }

    @Test
    void a_configured_connectors_name_still_refuses() throws Exception {
        QuarkusTransaction.requiringNew().run(() -> TenantContext.callAsSystem(() -> {
            adminExec("INSERT INTO connector.connector (id, tenant_id, org_id, name, type_id, config, enabled) "
                + "VALUES ('11111111-1111-1111-1111-111111111111', 1, 0, 'breath_taken', 'jdbc-generic', '{}'::jsonb, true)");
            return null;
        }));
        try {
            assertThatThrownBy(() ->
                    ingest("breath_taken", "system", "code\nA1\n"))
                .isInstanceOf(CsvIngestService.DuplicateIngestName.class)
                .hasMessageContaining("breath_taken");
        } finally {
            QuarkusTransaction.requiringNew().run(() -> TenantContext.callAsSystem(() -> {
                adminExec("DELETE FROM connector.connector WHERE id = '11111111-1111-1111-1111-111111111111'");
                return null;
            }));
        }
    }

    private void adminExec(String sql) {
        Jdbc.update(adminDs, "CsvReingestBreathIT", sql, b -> { });
    }
}

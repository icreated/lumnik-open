package io.lumnik.hub.entity;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** #1836: the fingerprint moves on every write to {@code connector.entity_manifest}: an insert, an update, a delete. */
@QuarkusTest
class EntityRegistryFingerprintIT {

    private static final String YAML = "kind: Entity\\nname: FpOne\\nmatch: [code]\\nsources:\\n  - table: t_a\\n    map: { code: code }\\n";

    @Inject EntityRegistry registry;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    private void exec(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** The registry is shared by the whole run: what other ITs put in memory without a stored row survives a refresh. */
    private List<EntityManifest> shared;

    @BeforeEach
    void remember() {
        shared = registry.all();
    }

    @AfterEach
    void clean() {
        shared.forEach(registry::put);
        exec("DELETE FROM connector.entity_manifest WHERE name = 'fpone'");
        registry.remove("fpone");
    }

    @Test
    void anInsertAnUpdateAndADeleteEachMoveIt() {
        String start = registry.fingerprint();

        exec("INSERT INTO connector.entity_manifest (name, yaml_text) VALUES ('fpone', E'" + YAML + "')");
        String inserted = registry.fingerprint();
        exec("UPDATE connector.entity_manifest SET yaml_text = yaml_text || E'\\n', updated_at = now() WHERE name = 'fpone'");
        String updated = registry.fingerprint();
        exec("DELETE FROM connector.entity_manifest WHERE name = 'fpone'");
        String deleted = registry.fingerprint();

        assertThat(inserted).isNotEqualTo(start);
        assertThat(updated).isNotEqualTo(inserted);
        assertThat(deleted).isNotEqualTo(updated);
    }

    @Test
    void aRefreshAfterAnotherNodesInsertServesTheNewEntity() {
        registry.refreshIfChanged();
        exec("INSERT INTO connector.entity_manifest (name, yaml_text) VALUES ('fpone', E'" + YAML + "')");

        registry.refreshIfChanged();

        assertThat(registry.find("fpone")).isNotNull();
    }
}

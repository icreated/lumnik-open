package io.lumnik.hub.connector.jdbc;

import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Layer 2 of the DDL-injection fix (see {@code RlsDdl}): {@code dumpSpec} prefixes the manifest's
 * {@code name} config value with {@code t_} to build the destination hub table — refuse it here,
 * before it can reach the DDL {@code DynamicTableWriter} executes on the BYPASSRLS admin datasource.
 */
class JdbcDumpSpecValidationTest {

    @Test
    void rejects_a_table_name_containing_a_single_quote() {
        assertThatThrownBy(() -> JdbcConnectorType.dumpSpec("o'r", List.of(), Map.of()))
            .isInstanceOf(ConfigValidationException.class)
            .hasMessageContaining("name");
    }

    @Test
    void rejects_a_table_name_containing_a_dollar_quote_tag() {
        assertThatThrownBy(() -> JdbcConnectorType.dumpSpec("orders$$evil", List.of(), Map.of()))
            .isInstanceOf(ConfigValidationException.class)
            .hasMessageContaining("name");
    }

    // ── rename: reaches the mapping spec (jdbc runtime side of the collision fix) ──

    @Test
    void dumpSpec_withoutRename_hasNoHooks() {
        io.lumnik.hub.connector.mapping.spec.MappingSpec spec =
            JdbcConnectorType.dumpSpec("invoices", List.of(), Map.of());
        assertThat(spec.row()).isEmpty();
    }

    @Test
    void dumpSpec_withRename_emitsOneMapColumnsHook() {
        io.lumnik.hub.connector.mapping.spec.MappingSpec spec =
            JdbcConnectorType.dumpSpec("invoices", List.of(), Map.of("id", "source_id"));
        assertThat(spec.row()).hasSize(1);
        assertThat(spec.row().get(0).name()).isEqualTo("map-columns");
        assertThat(spec.row().get(0).params().path("rename").path("id").asText()).isEqualTo("source_id");
    }

    // ── dumpSpec must refuse the same rename it would otherwise silently mis-write — it, not
    //    validateConfig, sits on the path writer(ctx) actually calls (see the class comment on
    //    dumpSpec: validateConfig has no production caller for jdbc-generic) ──

    @Test
    void dumpSpec_rejectsRenameTargetThatIsASystemColumn() {
        assertThatThrownBy(() -> JdbcConnectorType.dumpSpec("invoices", List.of(), Map.of("legacy", "tenant_id")))
            .isInstanceOf(ConfigValidationException.class)
            .hasMessageContaining("tenant_id");
    }

    @Test
    void dumpSpec_rejectsTwoSourcesRenamedOntoTheSameTarget() {
        Map<String, String> rename = new java.util.LinkedHashMap<>();
        rename.put("a", "same");
        rename.put("b", "same");
        assertThatThrownBy(() -> JdbcConnectorType.dumpSpec("invoices", List.of(), rename))
            .isInstanceOf(ConfigValidationException.class)
            .hasMessageContaining("same");
    }

    @Test
    void validateConfig_rejectsRenameTargetThatIsASystemColumn() {
        assertThatThrownBy(() -> JdbcConnectorType.validateRename(Map.of("legacy", "tenant_id")))
            .isInstanceOf(ConfigValidationException.class)
            .hasMessageContaining("tenant_id");
    }

    @Test
    void validateConfig_rejectsRenameTargetThatIsNotAPlainIdentifier() {
        assertThatThrownBy(() -> JdbcConnectorType.validateRename(Map.of("legacy", "a-b")))
            .isInstanceOf(ConfigValidationException.class);
    }

    @Test
    void validateConfig_rejectsTwoSourcesRenamedOntoTheSameTarget() {
        Map<String, Object> rename = new java.util.LinkedHashMap<>();
        rename.put("a", "same");
        rename.put("b", "same");
        assertThatThrownBy(() -> JdbcConnectorType.validateRename(rename))
            .isInstanceOf(ConfigValidationException.class)
            .hasMessageContaining("same");
    }

    @Test
    void validateConfig_acceptsRenamingAwayFromASystemColumn() {
        assertThatCode(() -> JdbcConnectorType.validateRename(Map.of("id", "source_id")))
            .doesNotThrowAnyException();
    }

    // ── a null rename value ("rename:\n  id:" — a manifest typo, not a deliberate blank) must
    //    not be stringified into the literal column name "null" (finding 1) ──

    @Test
    void validateRename_rejectsNullTargetDirectly() {
        Map<String, String> rename = new java.util.LinkedHashMap<>();
        rename.put("id", null);
        assertThatThrownBy(() -> JdbcConnectorType.validateRename(rename))
            .isInstanceOf(ConfigValidationException.class)
            .hasMessageContaining("rename.id")
            .hasMessageContaining("null");
    }

    @Test
    void validateConfig_rejectsNullRenameValue() {
        Map<String, Object> cfg = baseConfig("invoices");
        Map<String, Object> rename = new HashMap<>();
        rename.put("id", null);
        cfg.put("rename", rename);
        assertThatThrownBy(() -> type.validateConfig(cfg))
            .isInstanceOf(ConfigValidationException.class)
            .hasMessageContaining("rename.id");
    }

    // ── validateConfig must refuse the same names dumpSpec refuses (finding 5: validator == runtime) ──

    private final JdbcConnectorType type = new JdbcConnectorType();

    private static Map<String, Object> baseConfig(String name) {
        Map<String, Object> cfg = new HashMap<>(Map.of(
            "url", "jdbc:postgresql://localhost:5432/piscine",
            "user", "legacy_erp",
            "schema", "legacy_erp",
            "mode", "full",
            "pk_columns", List.of("c_order_id")));
        cfg.put("name", name);
        return cfg;
    }

    @Test
    void validateConfig_rejects_a_name_containing_a_single_quote() {
        // Without this, `lm apply` accepts a manifest that only fails mid-run, inside dumpSpec.
        assertThatThrownBy(() -> type.validateConfig(baseConfig("o'r")))
            .isInstanceOf(ConfigValidationException.class)
            .hasMessageContaining("name");
    }

    @Test
    void validateConfig_rejects_a_name_containing_a_dollar_quote_tag() {
        assertThatThrownBy(() -> type.validateConfig(baseConfig("orders$$evil")))
            .isInstanceOf(ConfigValidationException.class)
            .hasMessageContaining("name");
    }

    @Test
    void validateConfig_accepts_a_plain_name() {
        assertThatCode(() -> type.validateConfig(baseConfig("c_order")))
            .doesNotThrowAnyException();
    }

    // ── a renamed column must keep its source type hint (finding 3): JdbcChunkReader stashes
    //    hints keyed by the SOURCE column name, but SchemaInferrer looks them up by the
    //    POST-rename name — so a hint declared for "id" must follow the column to "source_id" ──

    @Test
    void renameHintKeys_translatesARenamedColumnsHintToItsNewName() {
        Map<String, String> hints = Map.of("id", "BIGINT", "amount", "NUMERIC(18,4)");
        Map<String, String> rename = Map.of("id", "source_id");
        assertThat(JdbcConnectorType.renameHintKeys(hints, rename))
            .containsEntry("source_id", "BIGINT")
            .containsEntry("amount", "NUMERIC(18,4)")
            .doesNotContainKey("id");
    }

    @Test
    void renameHintKeys_isANoOpWhenRenameIsEmpty() {
        Map<String, String> hints = Map.of("id", "BIGINT");
        assertThat(JdbcConnectorType.renameHintKeys(hints, Map.of())).isEqualTo(hints);
    }
}

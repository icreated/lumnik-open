package io.lumnik.hub.connector.jdbc;

import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code validateConfig} uses no CDI fields, so it is unit-testable directly.
 * Covers the declarative-manifest convenience: {@code dialect} may be omitted and is
 * inferred from a {@code jdbc:postgresql:} url; a non-inferable url without an explicit
 * dialect is rejected.
 */
class JdbcConnectorTypeDialectTest {

    private final JdbcConnectorType type = new JdbcConnectorType();

    private static Map<String, Object> base() {
        return new HashMap<>(Map.of(
            "url", "jdbc:postgresql://localhost:5432/piscine",
            "user", "legacy_erp",
            "schema", "legacy_erp",
            "name", "c_order",
            "mode", "full",
            "pk_columns", List.of("c_order_id")));
    }

    @Test
    void dialect_inferred_from_postgres_url_when_absent() {
        assertThatCode(() -> type.validateConfig(base()))
            .as("dialect omitted but inferable from jdbc:postgresql url")
            .doesNotThrowAnyException();
    }

    @Test
    void explicit_dialect_is_accepted() {
        Map<String, Object> cfg = base();
        cfg.put("dialect", "postgres");
        assertThatCode(() -> type.validateConfig(cfg)).doesNotThrowAnyException();
    }

    @Test
    void postgresql_alias_resolves_to_the_registered_postgres_id() {
        Map<String, Object> cfg = base();
        cfg.put("dialect", "postgresql");
        Assertions.assertThat(JdbcConnectorType.resolveDialectId(cfg))
            .as("the validator accepts 'postgresql'; the runtime registry only knows 'postgres' — "
                + "the alias must normalize or validator==runtime breaks")
            .isEqualTo("postgres");
    }

    @Test
    void dialect_is_trimmed_and_case_insensitive() {
        Map<String, Object> cfg = base();
        cfg.put("dialect", "  PostgreSQL ");
        Assertions.assertThat(JdbcConnectorType.resolveDialectId(cfg))
            .as("registry keys are lowercase by convention — user casing/whitespace must not leak")
            .isEqualTo("postgres");
    }

    @Test
    void missing_dialect_and_non_inferable_url_is_rejected() {
        Map<String, Object> cfg = base();
        cfg.put("url", "jdbc:db2://host:50000/x");   // no explicit dialect, not inferable
        assertThatThrownBy(() -> type.validateConfig(cfg))
            .isInstanceOf(ConfigValidationException.class)
            .hasMessageContaining("dialect");
    }
}

package io.lumnik.hub.connector.jdbc;

import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code password_env} reads the hub's environment, then the tenant's {@code lm secret} registry
 * (#1349): a manifest connector gets its password without a hub restart, and a name the
 * environment already holds keeps resolving to the same value.
 */
class JdbcConnectorTypePasswordTest {

    private static final String NAME = "JDBC_PASSWORD_TEST_1349";

    private final JdbcConnectorType type = new JdbcConnectorType();

    /** An OS variable cannot be cleared in-process: a machine exporting the name would skew every case. */
    @BeforeEach
    void nameIsNotInTheEnvironment() { assumeTrue(System.getenv(NAME) == null); }

    @AfterEach
    void clearProperty() { System.clearProperty(NAME); }

    private static ConnectorContext ctx() {
        return new ConnectorContext(null, null, null, 1L, "c", JdbcConnectorType.TYPE_ID,
            Map.of("password_env", NAME), null, null, null, null, null, null);
    }

    @Test
    void a_name_only_lm_secret_holds_resolves_from_the_registry() {
        type.secrets = name -> NAME.equals(name) ? Optional.of("from-registry") : Optional.empty();
        assertThat(type.resolvePassword(ctx())).isEqualTo("from-registry");
    }

    @Test
    void the_environment_still_wins_over_a_secret_of_the_same_name() {
        System.setProperty(NAME, "from-environment");
        type.secrets = name -> Optional.of("from-registry");
        assertThat(type.resolvePassword(ctx())).isEqualTo("from-environment");
    }

    @Test
    void a_name_neither_holds_fails_the_run_naming_both_places() {
        type.secrets = name -> Optional.empty();
        assertThatThrownBy(() -> type.resolvePassword(ctx()))
            .isInstanceOf(ConfigValidationException.class)
            .hasMessage("password_env '" + NAME + "' is neither in the server environment nor in lm secret");
    }
}

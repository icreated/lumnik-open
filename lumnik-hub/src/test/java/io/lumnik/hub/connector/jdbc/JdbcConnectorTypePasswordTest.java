package io.lumnik.hub.connector.jdbc;

import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import io.lumnik.hub.connector.secret.SecretRefs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code password_env} names a secret in the tenant's {@code lm secret} registry, and only there (#2000): the hub's
 * environment holds the hub's own secrets, the admin database password among them.
 */
class JdbcConnectorTypePasswordTest {

    private static final String NAME = "JDBC_PASSWORD_TEST_2000";

    private final JdbcConnectorType type = new JdbcConnectorType();

    @AfterEach
    void clearProperty() { System.clearProperty(NAME); }

    private static ConnectorContext ctx(String name) {
        return new ConnectorContext(null, null, null, 1L, "c", JdbcConnectorType.TYPE_ID,
            Map.of("password_env", name), null, null, null, null, null, null);
    }

    @Test
    void a_name_lm_secret_holds_resolves_from_the_registry() {
        type.secrets = SecretRefs.of(name -> NAME.equals(name) ? "from-registry" : null);
        assertThat(type.resolvePassword(ctx(NAME))).isEqualTo("from-registry");
    }

    @Test
    void a_jvm_property_of_that_name_is_not_read() {
        System.setProperty(NAME, "from-a-jvm-property");
        type.secrets = new SecretRefs(name -> Optional.empty());
        assertThatThrownBy(() -> type.resolvePassword(ctx(NAME)))
            .isInstanceOf(ConfigValidationException.class)
            .hasMessage("password_env '" + NAME + "' is not in lm secret");
    }

    @Test
    void an_os_variable_of_that_name_is_not_read() {
        assumeTrue(System.getenv("PATH") != null);
        type.secrets = new SecretRefs(name -> Optional.empty());
        assertThatThrownBy(() -> type.resolvePassword(ctx("PATH")))
            .isInstanceOf(ConfigValidationException.class)
            .hasMessage("password_env 'PATH' is not in lm secret");
    }
}

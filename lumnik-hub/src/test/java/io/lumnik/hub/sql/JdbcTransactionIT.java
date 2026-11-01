package io.lumnik.hub.sql;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #1649, on the real admin pool: a transaction left open is not lost but committed — given back to the pool, the
 * connection is reset with {@code setAutoCommit(true)}, on which pgjdbc COMMITs. A mock can show which calls
 * {@link Jdbc#transaction} makes; only PostgreSQL shows what lands.
 */
@QuarkusTest
class JdbcTransactionIT {

    private static final String PROBE = "connector.t_jdbc_tx_probe";

    @Inject @DataSource("admin") AgroalDataSource adminDs;

    /** The rollback fails before it reaches the server, the work still healthy: the session is ended, never committed. */
    @Test
    void a_rollback_that_fails_commits_nothing() throws Exception {
        try {
            try (Connection pooled = adminDs.getConnection()) {
                assertThatThrownBy(() -> Jdbc.transaction(rollbackFails(pooled), "JdbcTransactionIT.probe", c -> {
                    Jdbc.update(c, "JdbcTransactionIT.create", "CREATE TABLE " + PROBE + " (x int)", b -> {});
                    throw new IllegalStateException("halfway");
                })).hasMessage("halfway");
            }   // back to the pool: had the transaction stayed open, the reset would have committed it here

            assertThat(Jdbc.queryOne(adminDs, "JdbcTransactionIT.exists", "SELECT to_regclass(?) IS NOT NULL",
                b -> b.setString(1, PROBE), rs -> rs.getBoolean(1))).contains(false);
        } finally {
            Jdbc.update(adminDs, "JdbcTransactionIT.drop", "DROP TABLE IF EXISTS " + PROBE, b -> {});
        }
    }

    /** The pooled connection, whose rollback fails on the client side — as an OutOfMemoryError in the driver would. */
    private static Connection rollbackFails(Connection pooled) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
                if (method.getName().equals("rollback") && args == null)
                    throw new SQLException("rollback lost on the client side");
                try {
                    return method.invoke(pooled, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            });
    }
}

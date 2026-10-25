package io.lumnik.hub.connector.jdbc;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.pool.HikariPool.PoolInitializationException;
import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.exceptions.SourceUnreachableException;
import io.lumnik.hub.connector.jdbc.dialect.JdbcDialectRegistry;
import io.lumnik.jdbc.postgresql.PostgresDialect;
import io.lumnik.jdbc.spi.JdbcDialect;
import org.junit.jupiter.api.Test;

import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A source whose host is gone fails the run with ONE sentence, whichever way the pool said it
 * (#1389): the table, the SQLSTATE and the failure's kind — never the pool's name (it carries the
 * user), its counters, or the driver's text (it can carry the host).
 */
class JdbcConnectorTypeUnreachableTest {

    private static final String SENTENCE =
        "could not reach public.workorder (SQLSTATE 08001): host not resolved (UnknownHostException)";

    private static SQLException hostGone() {
        return new SQLException("The connection attempt failed.", "08001",
            new UnknownHostException("zombie-cmms-gone"));
    }

    private static JdbcConnectorType typeWhosePool(RuntimeException onAcquire, SQLException onConnect) {
        JdbcConnectorType type = new JdbcConnectorType();
        type.dialectRegistry = JdbcDialectRegistry.of(List.of(new PostgresDialect()));
        type.poolRegistry = new HikariPoolRegistry() {
            @Override
            public HikariDataSource acquire(String url, String user, String password, long tenantId, JdbcDialect dialect) {
                if (onAcquire != null) throw onAcquire;
                return new HikariDataSource() {
                    @Override
                    public Connection getConnection() throws SQLException { throw onConnect; }
                };
            }
        };
        return type;
    }

    private static ConnectorContext ctx() {
        return new ConnectorContext(null, null, null, 1L, "c", JdbcConnectorType.TYPE_ID,
            Map.of("url", "jdbc:postgresql://zombie-cmms-gone:5432/maximo", "user", "cmms_reader",
                "password", "x", "schema", "public", "name", "workorder", "mode", "full",
                "pk_columns", List.of("wonum")),
            null, null, null, null, null, null);
    }

    @Test
    void a_pool_that_was_open_says_the_host_is_gone_not_its_own_counters() {
        JdbcConnectorType type = typeWhosePool(null, new SQLTransientConnectionException(
            "jdbc-src-4c97e61c-cmms_reader - Connection is not available, request timed out after 5000ms "
                + "(total=0, active=0, idle=0, waiting=0)", "08001", hostGone()));

        assertThatThrownBy(() -> type.runStart(ctx(), null))
            .isInstanceOf(SourceUnreachableException.class)
            .hasMessage(SENTENCE);
    }

    @Test
    void a_pool_that_cannot_start_is_the_same_sentence_and_ends_the_run_there() {
        JdbcConnectorType type = typeWhosePool(new PoolInitializationException(hostGone()), null);

        assertThatThrownBy(() -> type.runStart(ctx(), null))
            .isInstanceOf(SourceUnreachableException.class)
            .hasMessage(SENTENCE);
    }

    @Test
    void a_failure_that_is_not_about_the_connection_keeps_the_forgiving_path() {
        JdbcConnectorType type = typeWhosePool(null, new SQLException("permission denied for table workorder", "42501"));

        assertThatThrownBy(() -> type.runStart(ctx(), null))
            .isNotInstanceOf(SourceUnreachableException.class)
            .hasMessageContaining("could not measure the run's target on public.workorder");
    }

    /** The source can also go between two chunks: the reader says the same sentence. */
    private static JdbcChunkReader readerWhosePool(RuntimeException onAcquire, SQLException onConnect) {
        JdbcConnectorType type = typeWhosePool(onAcquire, onConnect);
        return new JdbcChunkReader("jdbc:postgresql://zombie-cmms-gone:5432/maximo", "cmms_reader", "x", "postgres",
            "public", "workorder", null, List.of("wonum"), JdbcChunkReader.Mode.FULL,
            new PostgresDialect(), type.poolRegistry, ZoneOffset.UTC);
    }

    @Test
    void a_chunk_after_the_source_went_down_says_the_same_sentence() {
        JdbcChunkReader reader = readerWhosePool(null, new SQLTransientConnectionException(
            "jdbc-src-4c97e61c-cmms_reader - Connection is not available, request timed out after 5000ms "
                + "(total=0, active=0, idle=0, waiting=0)", "08001", hostGone()));

        assertThatThrownBy(() -> reader.read(500, null, ctx()))
            .isInstanceOf(SourceUnreachableException.class)
            .hasMessage(SENTENCE);
    }

    @Test
    void a_chunk_whose_pool_cannot_start_says_the_same_sentence() {
        JdbcChunkReader reader = readerWhosePool(new PoolInitializationException(hostGone()), null);

        assertThatThrownBy(() -> reader.read(500, null, ctx()))
            .isInstanceOf(SourceUnreachableException.class)
            .hasMessage(SENTENCE);
    }

    @Test
    void a_pool_start_failure_that_is_not_about_the_connection_is_left_as_it_was() {
        PoolInitializationException wrongPassword = new PoolInitializationException(
            new SQLException("password authentication failed", "28P01"));
        JdbcConnectorType type = typeWhosePool(wrongPassword, null);

        assertThatThrownBy(() -> type.runStart(ctx(), null)).isSameAs(wrongPassword);
    }
}

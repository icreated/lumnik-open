package io.lumnik.hub.connector.mapping.writer;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.mapping.spec.MappingTarget;
import io.lumnik.hub.sql.SqlFailureText;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * #1648: a writer's database failure becomes the run's served {@code error_message} —
 * {@code ChunkExecutor} stores {@link SqlFailureText#withCause} of it. The wrapper used to paste the
 * driver's words, and class 22/23 words quote the row: {@code invalid input syntax … "<value>"},
 * {@code Key (id)=(<value>) already exists}. The wrapper now says the label and the SQLSTATE; the
 * cause stays attached for the log, and withCause still adds a structural (class 42) sentence.
 */
class WriterFailureTextTest {

    private static final String VALUE = "4111-1111";

    @Test
    void a_data_exception_reaches_the_run_without_the_value_it_quotes() throws Exception {
        ConnectorException failure = removeFailingWith(
            new SQLException("ERROR: invalid input syntax for type bigint: \"" + VALUE + "\"", "22P02"));

        assertThat(failure).hasMessage("UnseenRowSweeper.remove failed (SQLSTATE 22P02)");
        assertThat(failure.getCause()).isInstanceOf(SQLException.class);   // the log keeps every word
        assertThat(SqlFailureText.withCause(failure)).doesNotContain(VALUE).contains("22P02");
    }

    @Test
    void a_structural_failure_still_tells_the_operator_what_to_fix() throws Exception {
        ConnectorException failure = removeFailingWith(
            new SQLException("ERROR: column \"_seen_run\" does not exist", "42703"));

        assertThat(SqlFailureText.withCause(failure))
            .isEqualTo("UnseenRowSweeper.remove failed (SQLSTATE 42703) — ERROR: column \"_seen_run\" does not exist");
    }

    private static ConnectorException removeFailingWith(SQLException driver) throws SQLException {
        UnseenRowSweeper sweeper = new UnseenRowSweeper();
        sweeper.adminDs = mock(AgroalDataSource.class);
        when(sweeper.adminDs.getConnection()).thenThrow(driver);
        return catchThrowableOfType(ConnectorException.class,
            () -> sweeper.remove(mock(MappingTarget.Table.class), 1L, "src", UUID.randomUUID()));
    }
}

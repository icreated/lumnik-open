package io.lumnik.hub.search.projection;

import io.quarkus.narayana.jta.QuarkusTransactionException;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class SearchProjectionWorkerDiagnosticTest {

    @Test
    void the_diagnostic_names_the_failure_inside_the_transaction_wrappers() {
        var failure = new IllegalStateException("sink refused");
        var wrapped = new QuarkusTransactionException(new QuarkusTransactionException(failure));
        assertThat(SearchProjectionWorker.shortDiagnostic(wrapped)).isEqualTo("IllegalStateException: sink refused");
    }

    /** #1299: last_error is served by an API. A raw database failure — the scan's own read, under the
     *  transaction wrapper — keeps its SQLSTATE, never a message whose class can quote a stored value. */
    @Test
    void a_database_failure_names_its_sqlstate_not_the_value_it_quotes() {
        var failure = new SQLException("ERROR: invalid input syntax for type numeric: \"abc\"", "22P02");
        String diagnostic = SearchProjectionWorker.shortDiagnostic(new QuarkusTransactionException(failure));
        assertThat(diagnostic).startsWith("SQLException: SQLSTATE 22P02").doesNotContain("abc");
    }

    @Test
    void a_wrapper_with_no_cause_is_the_diagnostic() {
        assertThat(SearchProjectionWorker.shortDiagnostic(new QuarkusTransactionException("rolled back")))
            .isEqualTo("QuarkusTransactionException: rolled back");
    }

    /** {@code initCause} refuses only self-causation: A→B→A is constructible, and the walk must end on it (#1289). */
    @Test
    void a_cyclic_chain_of_wrappers_still_returns() {
        var a = new QuarkusTransactionException("a");
        var b = new QuarkusTransactionException("b", a);
        a.initCause(b);
        String diagnostic = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> SearchProjectionWorker.shortDiagnostic(a));
        assertThat(diagnostic).startsWith("QuarkusTransactionException: ");
    }
}

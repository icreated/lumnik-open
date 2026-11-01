package io.lumnik.hub.entity.resolution;

import io.agroal.api.AgroalDataSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #1649: analysing is only to be sooner than autovacuum. A table whose statistics read fails is logged and
 * settled, whatever form the failure takes — it never fails the resolution pass that asked.
 */
class CrosswalkStatisticsFailureTest {

    @Test
    void a_failed_statistics_read_is_logged_and_settled_never_thrown() throws Exception {
        Connection c = mock(Connection.class);
        when(c.prepareStatement(anyString())).thenThrow(new SQLException("ERROR: permission denied", "42501"));
        AgroalDataSource ds = mock(AgroalDataSource.class);
        when(ds.getConnection()).thenReturn(c);
        CrosswalkStatistics statistics = new CrosswalkStatistics();
        statistics.adminDs = ds;

        assertThatCode(() -> statistics.analyseNeverAnalysed("connector", List.of("t_a"))).doesNotThrowAnyException();
        statistics.analyseNeverAnalysed("connector", List.of("t_a"));

        // Settled: the second call asks nothing.
        verify(ds, times(1)).getConnection();
        verify(c, never()).createStatement();
    }
}

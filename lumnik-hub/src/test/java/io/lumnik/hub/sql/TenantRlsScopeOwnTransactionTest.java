package io.lumnik.hub.sql;

import org.junit.jupiter.api.Test;

import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** #1595: without the connection's own transaction, SET LOCAL would be discarded and RLS go unenforced. */
class TenantRlsScopeOwnTransactionTest {

    @Test
    void an_autocommit_connection_is_refused_before_any_statement() throws Exception {
        Connection c = mock(Connection.class);
        when(c.getAutoCommit()).thenReturn(true);
        assertThatThrownBy(() -> TenantRlsScope.applyToOwnTransaction(c, 7L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("own transaction");
        verify(c).getAutoCommit();
        verifyNoMoreInteractions(c);
    }
}

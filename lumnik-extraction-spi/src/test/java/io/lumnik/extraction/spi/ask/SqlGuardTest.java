package io.lumnik.extraction.spi.ask;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlGuardTest {

    final SqlGuard guard = new SqlGuard(500);

    @Test
    void acceptsPlainSelectAndInjectsLimit() {
        String out = guard.check("SELECT documentno FROM c_order");
        assertThat(out).isEqualToIgnoringCase("SELECT documentno FROM c_order LIMIT 500");
    }

    @Test
    void keepsExistingLimitWhenSmaller() {
        String out = guard.check("select 1 from c_order limit 10");
        assertThat(out).containsIgnoringCase("limit 10");
        assertThat(out).doesNotContainIgnoringCase("limit 500");
    }

    @Test
    void acceptsWithCte() {
        String out = guard.check("WITH x AS (SELECT 1) SELECT * FROM x");
        assertThat(out).containsIgnoringCase("LIMIT 500");
    }

    @Test
    void rejectsNonSelect() {
        assertThatThrownBy(() -> guard.check("UPDATE c_order SET grandtotal=0"))
            .isInstanceOf(SqlGuard.SqlRejectedException.class);
    }

    @Test
    void rejectsMultipleStatements() {
        assertThatThrownBy(() -> guard.check("SELECT 1; DROP TABLE c_order"))
            .isInstanceOf(SqlGuard.SqlRejectedException.class);
    }

    @Test
    void rejectsBannedTokens() {
        assertThatThrownBy(() -> guard.check("SELECT pg_sleep(10)"))
            .isInstanceOf(SqlGuard.SqlRejectedException.class);
        assertThatThrownBy(() -> guard.check("SELECT * FROM pg_catalog.pg_user"))
            .isInstanceOf(SqlGuard.SqlRejectedException.class);
        assertThatThrownBy(() -> guard.check("COPY c_order TO '/tmp/x'"))
            .isInstanceOf(SqlGuard.SqlRejectedException.class);
    }

    @Test
    void stripsTrailingSemicolonAndCodeFences() {
        String out = guard.check("```sql\nSELECT 1 FROM c_order;\n```");
        assertThat(out).startsWith("SELECT 1 FROM c_order");
        assertThat(out).doesNotContain("`");
        assertThat(out).doesNotContain(";");
    }
}

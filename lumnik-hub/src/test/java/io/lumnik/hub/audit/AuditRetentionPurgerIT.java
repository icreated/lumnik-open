package io.lumnik.hub.audit;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.lumnik.hub.event.consumer.SchedulerPausedProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P9 (register #286): the audit log had NO retention — login/email snapshots kept forever,
 * the GDPR storage-limitation tension named on the security page. The purger bounds it:
 * rows older than the window die, recent rows stay, and retention-days <= 0 disables the
 * purge entirely (the documented keep-forever opt-out — an operator choice, never silent).
 */
@QuarkusTest
@TestProfile(SchedulerPausedProfile.class)
class AuditRetentionPurgerIT {

    private static final long TENANT = 8042L;

    @Inject AuditRetentionPurger purger;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    @BeforeEach
    void seed() throws Exception {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM core.audit_event WHERE tenant_id = " + TENANT);
            st.execute("INSERT INTO core.audit_event (tenant_id, occurred_at, entity_type, entity_key, operation, changes) VALUES "
                + "(" + TENANT + ", NOW() - INTERVAL '400 days', 'User', '1', 'UPDATE', '{}'::jsonb), "
                + "(" + TENANT + ", NOW() - INTERVAL '10 days',  'User', '2', 'UPDATE', '{}'::jsonb)");
        }
    }

    private long count() throws Exception {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM core.audit_event WHERE tenant_id = " + TENANT)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void purges_rows_older_than_the_window_and_keeps_recent_ones() throws Exception {
        purger.purge();
        assertThat(count()).as("the 400-day row dies (default 365d), the 10-day row stays").isEqualTo(1);
    }

    @Test
    void zero_or_negative_retention_disables_the_purge() throws Exception {
        purger.purgeWithRetention(0);
        assertThat(count()).as("retention 0 = keep forever, nothing deleted").isEqualTo(2);
    }
}

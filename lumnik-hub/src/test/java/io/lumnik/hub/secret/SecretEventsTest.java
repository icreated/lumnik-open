package io.lumnik.hub.secret;

import io.lumnik.common.tenant.CurrentTenantHolder;
import io.lumnik.hub.tenant.TenantContextFilter;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class SecretEventsTest {

    @Inject SecretEvents events;
    @Inject EntityManager em;
    @Inject UserTransaction utx;

    @BeforeEach
    void setTenant() {
        TenantContextFilter.CURRENT_TENANT.set(1L);
        CurrentTenantHolder.set(1L);
    }

    @AfterEach
    void clearTenant() {
        TenantContextFilter.CURRENT_TENANT.remove();
        CurrentTenantHolder.clear();
    }

    @Test
    void emitCreated_writes_outbox_row() throws Exception {
        String testName = "TEST_EMIT_KEY_" + System.nanoTime();

        utx.begin();
        em.createNativeQuery("SET LOCAL app.current_tenant = 1").executeUpdate();
        events.emitCreated(1L, testName, null);
        utx.commit();

        @SuppressWarnings("unchecked")
        Number count = (Number) em.createNativeQuery(
            "SELECT count(*) FROM core.event_outbox " +
            "WHERE event_type = 'platform.secret.created' AND payload::text LIKE '%" + testName + "%'"
        ).getSingleResult();
        assertTrue(count.longValue() >= 1,
            "expected at least 1 outbox row with topic 'platform.secret.created' for key " + testName);
    }

    @Test
    void emitDeleted_writes_outbox_row() throws Exception {
        String testName = "TEST_DEL_KEY_" + System.nanoTime();

        utx.begin();
        em.createNativeQuery("SET LOCAL app.current_tenant = 1").executeUpdate();
        events.emitDeleted(1L, testName, 42L);
        utx.commit();

        @SuppressWarnings("unchecked")
        Number count = (Number) em.createNativeQuery(
            "SELECT count(*) FROM core.event_outbox " +
            "WHERE event_type = 'platform.secret.deleted' AND payload::text LIKE '%" + testName + "%'"
        ).getSingleResult();
        assertTrue(count.longValue() >= 1,
            "expected at least 1 outbox row with topic 'platform.secret.deleted' for key " + testName);
    }
}

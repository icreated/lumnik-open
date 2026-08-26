package io.lumnik.hub.connector.rls;

import io.lumnik.common.tenant.CurrentTenantHolder;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.event.EventOutboxRow;
import io.lumnik.hub.event.consumer.OutboxSweeper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Under the enforced lumnik_app role: the '0' sentinel must reveal all tenants'
 * events to the sweep (event_outbox policy V4/V21), and the per-row tenant
 * switch must scope the processor to that row's tenant.
 *
 * <p>Real proof, not a tautology: drop the {@code '0'} sentinel branch from the
 * policy and the sweep — running inside {@code runAsSystem} with the GUC set to
 * '0' — would see zero rows (no tenant '0' exists), so both assertions fail.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class RlsEnforcedSweepIT {

  @Inject OutboxSweeper sweeper;
  @Inject Seeder seeder;

  /**
   * Seeds outbox rows as the tenant itself. {@code @Transactional} must be on a
   * CDI bean (not a self-invoked test method) so the {@code TenantConnectionInterceptor}
   * actually fires and the interceptor's connection participates in the same tx as
   * the explicit {@code SET LOCAL}.
   */
  @ApplicationScoped
  static class Seeder {
    @Inject EntityManager em;

    @Transactional
    void seed(long tenantId, String type) {
      // event_outbox RLS (V4/V21) allows tenant_id = current_tenant on INSERT
      // (USING doubles as WITH CHECK when no explicit WITH CHECK is declared).
      em.createNativeQuery("SET LOCAL app.current_tenant = '" + tenantId + "'").executeUpdate();
      em.persist(EventOutboxRow.of(tenantId, type, Map.of("k", "v")));
    }
  }

  @Test
  void sentinelSweepSeesAllTenantsAndSwitchesContext() {
    seeder.seed(1L, "rls.sweep.t1");
    seeder.seed(2L, "rls.sweep.t2");

    List<String> seen = new CopyOnWriteArrayList<>();
    TenantContext.runAsSystem(() ->
        sweeper.sweep("rls-it", 100, r ->
            seen.add(r.getEventType() + "@" + CurrentTenantHolder.get())));

    assertTrue(seen.contains("rls.sweep.t1@1"), "tenant 1 event under tenant-1 context: " + seen);
    assertTrue(seen.contains("rls.sweep.t2@2"), "tenant 2 event under tenant-2 context: " + seen);
  }
}

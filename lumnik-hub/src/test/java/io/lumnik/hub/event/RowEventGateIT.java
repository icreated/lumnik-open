package io.lumnik.hub.event;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.lumnik.hub.testsupport.EventItSupport.execSql;
import static io.lumnik.hub.testsupport.EventItSupport.purgeWebhookEndpoints;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gate matching matrix. No test profile: the gate reads the admin datasource, not a
 * tenant session. Every test cleans BOTH tables first — the gate is global per tenant
 * (it reads all endpoints/rules), so a foreign subscriber would poison the matrix.
 */
@QuarkusTest
class RowEventGateIT {

    @Inject RowEventGate gate;
    @Inject EntityManager em;

    @Inject
    @io.quarkus.agroal.DataSource("admin")
    io.agroal.api.AgroalDataSource adminDs;

    @BeforeEach
    void clean() {
        // Endpoints go through purgeWebhookEndpoints, which locks them before deleting: these are
        // subscribed to data.row.changed, so WebhookConsumer's 5 s tick can be writing a delivery
        // right now, and a teardown that throws leaves the gate ON for every sibling IT — the
        // 2026-07-24 nightly, where this class alone lost five tests to someone else's leftover.
        purgeWebhookEndpoints(adminDs, "gate-%");
        // Same datasource for the rules: it is BYPASSRLS, so the tenant GUC the em path needed is
        // moot, and a second @Transactional method called on `this` would silently skip the
        // interceptor anyway.
        execSql(adminDs, "DELETE FROM core.notification_rule WHERE recipient LIKE '%@gatetest.local'");
    }

    @Transactional
    void seedEndpoint(String name, String eventTypes, boolean enabled, String entityFilter, boolean deleted) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.createNativeQuery("""
            INSERT INTO core.webhook_endpoint
                (tenant_id, name, target_url, platform, auth_mode, event_types, enabled, entity_filter, deleted_at)
            VALUES (1, :name, 'http://gate.test/x', 'generic', 'NONE',
                    CAST(:types AS TEXT[]), :enabled, :filter,
                    CASE WHEN :deleted THEN NOW() ELSE NULL END)
            """)
          .setParameter("name", name)
          .setParameter("types", eventTypes)
          .setParameter("enabled", enabled)
          .setParameter("filter", entityFilter)
          .setParameter("deleted", deleted)
          .executeUpdate();
    }

    @Transactional
    void seedRule(String pattern, boolean enabled) {
        seedRule(pattern, enabled, null);
    }

    @Transactional
    void seedRule(String pattern, boolean enabled, String entityFilter) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.createNativeQuery("""
            INSERT INTO core.notification_rule (tenant_id, event_pattern, recipient, enabled, entity_filter)
            VALUES (1, :pattern, 'r@gatetest.local', :enabled, :filter)
            """)
          .setParameter("pattern", pattern)
          .setParameter("enabled", enabled)
          .setParameter("filter", entityFilter)
          .executeUpdate();
    }

    @Test
    void exactMatchEnablesForOwningTenantOnly() {
        seedEndpoint("gate-exact", "{data.row.changed}", true, null, false);
        assertTrue(gate.enabled(1L, "erp.fact"), "owning tenant with exact subscription");
        assertFalse(gate.enabled(2L, "erp.fact"), "other tenant must not see it");
    }

    @Test
    void prefixPatternEnables() {
        seedEndpoint("gate-prefix", "{data.*}", true, null, false);
        assertTrue(gate.enabled(1L, "erp.fact"), "prefix pattern data.* covers data.row.changed");
    }

    @Test
    void disabledEndpointDoesNotEnable() {
        seedEndpoint("gate-disabled", "{data.row.changed}", false, null, false);
        assertFalse(gate.enabled(1L, "erp.fact"));
    }

    @Test
    void deletedEndpointDoesNotEnable() {
        seedEndpoint("gate-deleted", "{data.row.changed}", true, null, true);
        assertFalse(gate.enabled(1L, "erp.fact"));
    }

    @Test
    void entityFilterNarrowsToMatchingTable() {
        seedEndpoint("gate-filtered", "{data.row.changed}", true, "erp.fact", false);
        assertTrue(gate.enabled(1L, "erp.fact"), "filter matches the table");
        assertFalse(gate.enabled(1L, "erp.other"), "filter excludes other tables (no other subscriber)");
    }

    @Test
    void notificationRuleEnablesWithoutEndpoint() {
        seedRule("data.row.changed", true);
        assertTrue(gate.enabled(1L, "erp.fact"));
    }

    @Test
    void notificationRuleEntityFilterNarrowsToMatchingTable() {
        seedRule("data.row.changed", true, "erp.fact");
        assertTrue(gate.enabled(1L, "erp.fact"), "rule filter matches the table");
        assertFalse(gate.enabled(1L, "erp.other"), "rule filter excludes other tables (no other subscriber)");
    }

    @Test
    void nothingSeededIsClosed() {
        assertFalse(gate.enabled(1L, "erp.fact"));
    }
}

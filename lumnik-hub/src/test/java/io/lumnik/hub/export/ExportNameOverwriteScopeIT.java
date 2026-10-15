package io.lumnik.hub.export;

import io.lumnik.hub.api.ScopeGuardedUpsert;
import io.lumnik.common.tenant.CurrentTenantHolder;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mirror of SavedViewNameOverwriteScopeIT (#582), for kind:Export's own guarded upsert.
 * Calls the store directly — as if ExportApplyResource's ordered ApplyScopeBoundary check had
 * been jumped entirely by a concurrent request — so the property under test is "the store refuses
 * on its own", not "the door refuses".
 *
 * <p>tenant_id must be bound manually here (no HTTP request to carry it) — CurrentTenantHolder is
 * set, and each call below runs inside a real transaction, so TenantConnectionInterceptor DOES
 * fire SET LOCAL app.current_tenant just as it would for a real request. What makes RLS inert
 * here is not an absent GUC: this class runs as a plain @QuarkusTest, not RlsEnforcedProfile, so
 * the default datasource connects as a role that owns the table (BYPASSRLS-equivalent) and the
 * policy never gets consulted regardless of the GUC. RLS enforcement itself is proven separately,
 * by ExportManifestTenantIsolationIT (Task 7) — this class is only about the scope guard.
 *
 * <p>Every store call goes through {@link QuarkusTransaction#requiringNew()}: the store's methods
 * are {@code @Transactional(MANDATORY)}, because outside a transaction SET LOCAL never fires and
 * RLS fails closed in a way that reads like a scope refusal or a 404. This test bypasses the
 * DOOR's ordered check on purpose — it must not also bypass the transaction the door would have
 * held, or it would be measuring a plumbing state no request can ever be in. {@code requiringNew}
 * rather than a method-wide {@code @TestTransaction}, so each call commits and the next one reads
 * what the previous one actually wrote — the seed-then-race shape needs two real transactions.
 */
@QuarkusTest
class ExportNameOverwriteScopeIT {

    private static final long TENANT = 1L;
    private static final String OWNER = "proprietaire-export-676";
    private static final String INTRUDER = "intrus-export-676";
    private static final String YAML = "apiVersion: apps.lumnik.io/v1\nkind: Export\n";

    @Inject ExportManifestStore store;

    @BeforeEach
    void bindTenant() {
        CurrentTenantHolder.set(TENANT);
    }

    @AfterEach
    void clearTenant() {
        CurrentTenantHolder.clear();
    }

    /** One store upsert, inside its own transaction — what an @Transactional door would hold. */
    private ScopeGuardedUpsert upsert(String name, String scope, String marker, Set<String> grants) {
        return QuarkusTransaction.requiringNew().call(() ->
            store.upsert(TENANT, name, scope, "EntityX", "sftp", null, YAML + marker, grants));
    }

    private ExportManifestStore.Row row(String name) {
        return QuarkusTransaction.requiringNew().call(() -> store.find(TENANT, name).orElseThrow());
    }

    private ExportManifestStore.Row given(String name) {
        upsert(name, OWNER, "# owner", null);
        return row(name);
    }

    @Test
    void aBoundCallerCannotOverwriteANameHeldOutsideItsGrants() {
        ExportManifestStore.Row before = given("export-nom-676");

        ScopeGuardedUpsert outcome = upsert("export-nom-676", INTRUDER, "# stolen", Set.of(INTRUDER));

        assertThat(outcome)
            .as("the conflict arm's own WHERE refuses, with no help from the door's ordered "
                + "findScope check")
            .isInstanceOf(ScopeGuardedUpsert.NameHeldOutOfScope.class);

        assertThat(row("export-nom-676"))
            .as("refused AND untouched — the row must not have been re-homed on the way out")
            .isEqualTo(before);
    }

    @Test
    void anUnscopedCallerStillOverwritesFreely() {
        given("export-relais-676");

        ScopeGuardedUpsert outcome = upsert("export-relais-676", INTRUDER, "# rehomed", null);

        assertThat(outcome).isInstanceOf(ScopeGuardedUpsert.Applied.class);
        ExportManifestStore.Row after = row("export-relais-676");
        assertThat(after.scope()).isEqualTo(INTRUDER);
    }

    @Test
    void aBoundCallerStillCreatesAFreeName() {
        ScopeGuardedUpsert outcome =
            upsert("export-neuve-676", INTRUDER, "# neuve", Set.of(INTRUDER));

        assertThat(outcome).isInstanceOf(ScopeGuardedUpsert.Applied.class);
        assertThat(row("export-neuve-676").scope()).isEqualTo(INTRUDER);
    }

    @Test
    void aBoundCallerMayOverwriteANameInsideItsGrants() {
        given("export-sienne-676");
        ScopeGuardedUpsert promote = upsert("export-sienne-676", OWNER, "# v2", Set.of(OWNER));

        assertThat(promote).isInstanceOf(ScopeGuardedUpsert.Applied.class);
        assertThat(row("export-sienne-676").yaml()).contains("# v2");
    }
}

package io.lumnik.hub.export.run;

import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.lumnik.hub.export.run.ExportRunLedger.Claimed;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.lumnik.hub.export.run.ExportRunLedgerIT.inTenant;
import static io.lumnik.hub.export.run.ExportRunLedgerIT.manual;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tenant B cannot see tenant A's runs, even when it asks with A's tenant id and A's run id. Under
 * RlsEnforcedProfile the default datasource is the non-owner lumnik_app, so this measures the
 * V54 policy itself rather than the ledger's belt-and-braces tenant_id filter — the same reason
 * ExportManifestTenantIsolationIT runs under it.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class ExportRunTenantIsolationIT {

    @Inject ExportRunLedger ledger;

    @Test
    void tenantBSeesNoneOfTenantAsRuns() {
        String name = "ledger-rls-680";
        UUID run = ((Claimed) inTenant(1, () -> ledger.claim(manual(1, name)))).runId();

        assertThat(inTenant(1, () -> ledger.find(1, name, run))).isPresent();
        assertThat(inTenant(2, () -> ledger.find(1, name, run))).isEmpty();
        assertThat(inTenant(2, () -> ledger.list(1, name, 50))).isEmpty();
        assertThat(inTenant(2, () -> ledger.list(2, name, 50))).isEmpty();
        // Tenant B's own run of the same name is independent: A's in-flight row does not block it.
        assertThat(inTenant(2, () -> ledger.claim(manual(2, name)))).isInstanceOf(Claimed.class);
    }
}

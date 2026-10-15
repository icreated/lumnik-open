package io.lumnik.hub.search.contract;

import io.lumnik.hub.auth.NoBypassOidcProfile;
import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link SearchProviderContractIT}'s own profile (#674): the first test in this codebase to need
 * real OIDC/JWT auth AND real RLS enforcement together over full HTTP — every other consumer of
 * either needs only one of the two (grep confirms no existing {@code @TestProfile} combines them).
 *
 * <p>Composes rather than duplicates: {@link NoBypassOidcProfile} is already documented as
 * "Public: used across packages" for its {@code token(...)} minting, so delegating to its own
 * {@code getConfigOverrides()} here reuses the exact OIDC issuer/audience/public-key it configures —
 * the same values {@link RestHubSearchContractDriver}'s minted tokens are signed against — without
 * copying the embedded test RSA keypair. The RLS half is NOT composed the same way: {@code
 * RlsEnforcedProfile}/{@code RlsEnforcedPgResource} (io.lumnik.hub.connector.rls) back ~15 other ITs
 * and their schema-grant allowlist predates the #705 {@code search_pg} module, so this suite gets its
 * own small, local {@link SearchProviderContractRlsPgResource} instead of widening shared infra for a
 * need only this package has.
 */
public class SearchProviderContractRlsProfile implements QuarkusTestProfile {

    @Override
    public List<TestResourceEntry> testResources() {
        // The backend is here, not class-restricted on the two classes, so they share one application (#1072).
        return List.of(new TestResourceEntry(SearchProviderContractRlsPgResource.class),
            new TestResourceEntry(SearchBackendContainerResource.class));
    }

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(new NoBypassOidcProfile().getConfigOverrides());
        // RlsEnforcedProfile's own javadoc explains why: the readonly datasource's %test-only
        // jdbc.transactions=DISABLED pin is harmless only when the plain DevServices container
        // connects as an RLS-bypassing owner. Once this profile's resource connects it as the
        // genuine non-owner lumnik_readonly role instead, DISABLED would silently unbind the tenant
        // (TenantRlsScope.applyTo) and every RLS-scoped readonly read — SearchProjectionWorker's
        // rebuild scan and catch-up batch read both go through readonlyDs — would fail closed
        // (zero rows for every tenant) rather than proving real isolation.
        overrides.put("quarkus.datasource.\"readonly\".jdbc.transactions", "enabled");
        return overrides;
    }
}

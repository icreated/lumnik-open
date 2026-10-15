package io.lumnik.hub.search.contract;

import io.lumnik.hub.auth.NoBypassOidcProfile;
import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Pins {@code lumnik.search.provider} to {@link OutageDoubleSearchProvider#NAME} so
 *  {@code SearchGate.resolve()} deterministically selects the double even with a real provider also
 *  installed under {@code -Psearch} — without this, {@code resolve()} would see two installed
 *  providers with no configured name and answer {@code AMBIGUOUS_PROVIDER}, silently exercising the
 *  wrong failure path (#674, §2.2).
 *
 * <p>Composes {@link NoBypassOidcProfile}'s own {@code getConfigOverrides()} (same idiom as
 * {@link SearchProviderContractRlsProfile}) rather than duplicating it: every consumer of a real
 * HTTP endpoint in this package needs {@code lumnik.security.dev-bypass=false} plus the OIDC
 * issuer/audience/public-key wiring — without it {@code TenantContextFilter} takes the dev-bypass
 * header path instead of validating the bearer JWT this IT sends, and {@code RequireTenant.current()}
 * then 401s with "no tenant in security context" (observed empirically before this fix). */
public class SearchOutageApiContractProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(new NoBypassOidcProfile().getConfigOverrides());
        overrides.put("lumnik.search.provider", OutageDoubleSearchProvider.NAME);
        return overrides;
    }

    @Override
    public Set<Class<?>> getEnabledAlternatives() {
        return Set.of(OutageDoubleSearchProvider.class);
    }
}

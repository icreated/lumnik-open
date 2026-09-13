package io.lumnik.hub.connector.rest.auth;

import io.lumnik.hub.connector.secret.SecretRefs;
import io.lumnik.hub.connector.rest.RestRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class BearerAuthProviderFactory implements AuthProviderFactory {

    private final SecretRefs secrets;

    @Inject
    public BearerAuthProviderFactory(SecretRefs secrets) { this.secrets = secrets; }

    @Override public String kind() { return "bearer"; }

    @Override public AuthProvider build(AuthSpec spec) {
        String envName = spec.config().get("token_env");
        String inline  = spec.config().get("token_value");
        // An inline `token_value` is the fallback for a MISSING reference, not for an unresolved
        // one: naming a secret that nothing holds is a misconfiguration, and silently using the
        // literal beside it would hide exactly that.
        String token = envName == null ? inline : secrets.lookup(envName);
        return new AuthProvider() {
            @Override public RestRequest apply(RestRequest r) {
                return token == null ? r : r.withHeader("Authorization", "Bearer " + token);
            }
            @Override public void probe() {
                if (token == null || token.isBlank())
                    throw new AuthConfigException("Bearer token unavailable (env=" + envName + ")");
            }
        };
    }
}

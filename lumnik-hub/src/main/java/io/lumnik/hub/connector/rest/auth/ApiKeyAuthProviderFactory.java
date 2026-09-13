package io.lumnik.hub.connector.rest.auth;

import io.lumnik.hub.connector.secret.SecretRefs;
import io.lumnik.hub.connector.rest.RestRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ApiKeyAuthProviderFactory implements AuthProviderFactory {

    private final SecretRefs secrets;

    @Inject
    public ApiKeyAuthProviderFactory(SecretRefs secrets) { this.secrets = secrets; }

    @Override public String kind() { return "api-key"; }

    @Override public AuthProvider build(AuthSpec spec) {
        String in    = spec.config().getOrDefault("in", "header");
        String name  = spec.config().get("name");
        String envName = spec.config().get("value_env");
        String value = secrets.lookup(envName);
        return new AuthProvider() {
            @Override public RestRequest apply(RestRequest r) {
                if (value == null) return r;
                return "query".equalsIgnoreCase(in) ? r.withQuery(name, value) : r.withHeader(name, value);
            }
            @Override public void probe() {
                if (value == null || value.isBlank())
                    throw new AuthConfigException("API key unavailable (env=" + envName + ")");
            }
        };
    }
}

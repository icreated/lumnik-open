package io.lumnik.hub.connector.rest.auth;

import io.lumnik.hub.connector.api.helpers.EnvSecrets;
import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.secret.SecretResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.function.Function;

@ApplicationScoped
public class ApiKeyAuthProviderFactory implements AuthProviderFactory {

    private final Function<String, String> envLookup;
    private final SecretResolver secrets;

    @Inject
    public ApiKeyAuthProviderFactory(SecretResolver secrets) { this(EnvSecrets::lookup, secrets); }

    /** Test ctor: stubbed env + resolver. */
    public ApiKeyAuthProviderFactory(Function<String, String> envLookup, SecretResolver secrets) {
        this.envLookup = envLookup;
        this.secrets = secrets;
    }

    @Override public String kind() { return "api-key"; }

    @Override public AuthProvider build(AuthSpec spec) {
        String in    = spec.config().getOrDefault("in", "header");
        String name  = spec.config().get("name");
        String envName = spec.config().get("value_env");
        String value = resolve(envName);
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

    private String resolve(String envName) {
        if (envName == null) return null;
        return secrets.resolve(envName)
            .or(() -> Optional.ofNullable(envLookup.apply(envName)))
            .orElse(null);
    }
}
